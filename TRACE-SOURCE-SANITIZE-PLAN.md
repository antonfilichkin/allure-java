# Sanitize AspectJ-shadowed source frames in Playwright traces

**Status:** implemented on `feat/playwright-trace-sources-flag` (`TraceSourceSanitizer`, wired into
`DefaultTraceSession.stop()`, gated by `allure.playwright.trace.sanitize-sources`). Not yet merged or in a PR.
Written from `aurora-light` (a downstream consumer of `allure-playwright`), where this exact fix was first
implemented, verified against a real trace, and confirmed working live in Trace Viewer; the rest of this doc
is the original design write-up, kept as-is below except where a correction is called out inline — see the
"Concrete before/after example" and "Relationship to other changes" sections for what actually shipped and
how it differs from the original sketch.

Per `AGENTS.md`: **no PR or branch push without explicit user confirmation.**

## Problem

`Tracing.StartOptions.setSources(true)` is supposed to embed real Java source into a trace so Trace
Viewer's Source tab can show the exact line that performed each action. It doesn't deliver on that when
`AllurePlaywrightAspect` is woven in (which is the normal case for any consumer using
`allure.playwright.steps.enabled=true`, the default) — every advised Playwright call gets a synthetic
`org.aspectj.runtime.reflect.JoinPointImpl.proceed` frame inserted in front of the real caller.

## Evidence

Confirmed empirically (not theoretical) against real traces from `aurora-light`, `allure-playwright`
2.35.4, `aspectjweaver` 1.9.25, Playwright Java 1.62.0:

- Inspected a real trace's `trace.stacks` zip entry directly (the JSON payload Playwright's Java client
  writes when `setSources(true)` is on): **81 of 82** recorded action stacks had
  `org.aspectj.runtime.reflect.JoinPointImpl.proceed` as **frame 0** — the frame Trace Viewer treats as
  "the" source location for that action (frame IDs in `trace.stacks` correlate 1:1 with `callId` numbers in
  `trace.trace`; confirmed on 150/151 IDs in a second trace, with the one mismatch being a tracing-lifecycle
  bookkeeping call that isn't a recorded "call" at all).
- The **real caller frame is still present** in the same captured stack, just deeper — e.g. for a
  `Locator.fill()` call: frames 0-14 are AspectJ/`AllurePlaywrightAspect`/Playwright-impl
  (`$AjcClosure`/`_aroundBody`) glue, frame 15 is the actual project method that made the call, with a
  correct file path and line number.
- Root cause is **not** load-time-weaving-specific: `AllurePlaywrightAspect` is `@Aspect`-annotated with
  `@Around(ProceedingJoinPoint)` advice, and AspectJ's own devguide states LTW and CTW produce equivalent
  woven bytecode — the reflective `JoinPointImpl`/`AroundClosure` machinery is required by the `@AJ`
  advice-signature contract itself (binding a full `ProceedingJoinPoint` as a method parameter), regardless
  of when weaving happens. So this can't be fixed by a weaving-mode switch; it has to be fixed by not
  trusting frame 0 blindly.
- Fix verified twice: once by direct JSON inspection (a corrupted trace's frame-0 stats went from 1/82 real
  source to 82/82 after sanitizing; a second, larger trace came back 151/151), and once live — a
  deliberately-failing test's trace opened in the real Trace Viewer (via
  `com.microsoft.playwright.CLI show-trace`) resolved a real project class for the Source tab.
- **Known residual gap, not fixed by this:** Java's `StackTraceElement` carries no column information at
  all (unlike JS/TS stack traces), so every frame's column is always `0` — confirmed across 12,776 frames
  in one trace, including untouched deep frames, not just frame 0. If Trace Viewer's line-highlight
  rendering wants a non-zero column to draw its marker, that's a pre-existing Java-client limitation,
  unrelated to and not addressed by this fix.

## Concrete before/after example, captured from this repo

Not just `aurora-light` evidence — reproduced directly against this module's own test fixture (a temporary probe
built on the same page/context setup as `AllurePlaywrightTest`, with `PLAYWRIGHT_JAVA_SRC` pointed at
`allure-playwright/src/test/java`, then deleted; not part of the committed suite). `context.tracing().start()`
with `setSources(true)`, then `page.setContent("<button>Trace</button>"); page.click("button");` under the same
`-javaagent:aspectjweaver` weaving this module's tests already run with.

**Before** — raw `trace.stacks` entry for the `click("button")` call (frame `file`/`line` shown only where
`files[]` resolved a path):

```
0  org.aspectj.runtime.reflect.JoinPointImpl.proceed                                                    (no file)
1  io.qameta.allure.playwright.AllurePlaywrightAspect.logPlaywrightStep                                 (no file)
2  com.microsoft.playwright.impl.PageImpl.click                                                         (no file)
3  com.microsoft.playwright.Page.click_aroundBody6                                                      (no file)
4  com.microsoft.playwright.Page$AjcClosure7.run                                                        (no file)
5  org.aspectj.runtime.reflect.JoinPointImpl.proceed                                                    (no file)
6  io.qameta.allure.playwright.AllurePlaywrightAspect.runStep                                           (no file)
7  io.qameta.allure.playwright.AllurePlaywrightAspect.ajc$inlineAccessMethod$...$runStep                (no file)
8  io.qameta.allure.playwright.AllurePlaywrightAspect.logPlaywrightStep                                 (no file)
9  com.microsoft.playwright.Page.click                                                                  (no file)
10 io.qameta.allure.playwright.ScratchTraceCapture.captureTrace   ScratchTraceCapture.java:34   <- real caller
11 jdk.internal.reflect.DirectMethodHandleAccessor.invoke                                               (no file)
    ... (JUnit/ForkJoinPool plumbing continues below)
```

Trace Viewer's Source tab reads frame **0** as *the* location for this action — `JoinPointImpl.proceed`, which
resolves to nothing useful. The real call site is sitting right there at frame 10, untouched, just buried under
nine frames of AspectJ/Playwright-`_aroundBody`/`$AjcClosure` glue.

**After** — same stack, trimmed by the actual implemented algorithm (`TraceSourceSanitizer`, since built —
see below): scan the *whole* stack for the first frame anywhere that's file-resolvable, and cut there:

```
0  io.qameta.allure.playwright.ScratchTraceCapture.captureTrace   ScratchTraceCapture.java:34   <- real caller
1  jdk.internal.reflect.DirectMethodHandleAccessor.invoke
    ... (unchanged from here down)
```

Frames 0-9 are dropped; frame 10 becomes the new frame 0, and Trace Viewer's Source tab now has a real,
resolvable file and line to show. This matches the plan's evidence from `aurora-light` almost exactly (same
`JoinPointImpl.proceed`-at-frame-0 pattern, same fix), just reproduced with this module's own test harness
instead of a downstream consumer's.

**Correction to the original algorithm sketch above:** frames 2 and 9 here (`PageImpl.click` / `Page.click`)
are real `com.microsoft.playwright` classes, not AspectJ-generated ones — they don't match the
synthetic-frame denylist. A per-frame "first frame that's either file-resolvable *or not on the denylist*"
scan (what was originally written above) stops dead at frame 2, since it's non-synthetic — it never reaches
frame 10 at all. The implementation that actually shipped fixes this: the file-resolution signal scans the
**entire** stack first and is authoritative wherever it fires, regardless of what non-file-resolved,
non-denylisted frames sit in between (frame 10 wins even though frames 2 and 9 are in the way). The denylist
is only consulted as a backstop, and only when *no* frame anywhere in the stack resolves a file at all — in
that case, the leading run of denylist-matching frames from frame 0 is trimmed instead (and if that run
reaches the end of the stack with nothing real after it, the stack is left alone rather than emptied).

**Untrimmed example, same repo, same run:** not every recorded call goes through the aspect at all —
`AllurePlaywrightAspect`'s pointcut only advises `Page`/`Frame`/`Locator`/`ElementHandle`/assertion methods, not
`BrowserContext`/`Tracing`. The very same capture's `context.tracing().start(...)` call (a `Tracing` method) has
no synthetic frame in front of it to begin with:

```
0  io.qameta.allure.playwright.ScratchTraceCapture2.captureTrace   ScratchTraceCapture2.java:26   <- real caller
1  jdk.internal.reflect.DirectMethodHandleAccessor.invoke
    ... (unchanged from here down)
```

`TraceSourceSanitizer` leaves this one alone: frame 0 already resolves (`isFileResolved` is true at `i == 0`),
so the cut index is `0` and nothing gets trimmed — exactly the "don't touch what's already fine" case the unit
tests (`shouldLeaveAlreadyResolvedStackUnchanged`, `shouldLeaveNonSyntheticStackUnchanged`) exercise directly.

**Also implemented, not just a nuance:** `TraceSourceSanitizer.sanitize()` checks for a `trace.stacks` entry
before doing any unzip/rewrite at all. Without `PLAYWRIGHT_JAVA_SRC` configured *anywhere* (not per-frame —
at all), Playwright's Java client never collects a stack per call in the first place (confirmed by decompiling
`com.microsoft.playwright.impl.StackTraceCollector.createFromEnv`: it returns `null`, and nothing downstream
ever calls `currentStackTrace()`, when the env var resolves to `null`), so the entry is simply absent and
there's nothing to sanitize — checking `System.getenv("PLAYWRIGHT_JAVA_SRC")` directly from
`allure-playwright`'s own process was considered and rejected, since a consumer can also set it via
`Playwright.CreateOptions().setEnv(...)` (as this repo's own end-to-end test does), which never touches the
real OS environment our code would be checking.

## Where this belongs in `allure-playwright`

The equivalent of `aurora-light`'s workaround (`PlaywrightTraceSourcesLifecycle` — a whole custom
`TestLifecycleListener` reimplementing start/stop/attach) exists there only because `allure-playwright`'s
own classes are `final`/package-private with no extension point. Inside `allure-playwright` itself, the fix
belongs directly in the real code path, no reimplementation needed:

- `allure-playwright/src/main/java/io/qameta/allure/playwright/DefaultTraceSession.java` — `stop(boolean
  attach)` is where `context.tracing().stop(new Tracing.StopOptions().setPath(trace))` produces the zip,
  right before `AllurePlaywright.attachTrace(name, trace)` reads it back off disk. This is the single choke
  point every trace-attach path goes through (both `attachCloseTraces` and `attachFailureTraces` end up
  here via `DefaultTraceSession.attach()`), so fixing it here fixes both close-time and failure-time traces
  in one place.
- New sanitization step goes between `context.tracing().stop(...)` returning and `AllurePlaywright
  .attachTrace(name, trace)` being called — rewrite `trace` in place (or write to a second temp file and
  swap) before it's read for attachment.

## Design differences vs. the `aurora-light` workaround

The downstream fix's heuristic ("first frame whose file index resolves to a non-blank `files[]` entry") is
good enough for one project's own code, but relies entirely on `PLAYWRIGHT_JAVA_SRC` being configured — if
a library consumer sets `sources(true)` without ever setting that env var, *every* frame has a blank file
and the heuristic is a safe no-op (nothing to trim), but it also doesn't help them skip the AspectJ frame
for any other purpose (e.g. a future "call stack" panel, if Trace Viewer ever adds one). For the upstream
fix, prefer combining two signals:

1. **Primary:** same file-presence heuristic (works today, zero config needed beyond what
   `setSources(true)` already requires).
2. **Belt-and-suspenders:** also explicitly skip frames whose declaring class matches known synthetic
   patterns this library itself introduces — `org.aspectj.runtime.reflect.JoinPointImpl`,
   `io.qameta.allure.playwright.AllurePlaywrightAspect`, and Playwright's own
   `*_aroundBody\d+`/`*\$AjcClosure\d+` method-name patterns (these are AspectJ-compiler-generated, not
   `com.microsoft.playwright`'s real API surface). This catches the case where a consumer's own build
   doesn't populate `PLAYWRIGHT_JAVA_SRC` for every frame in the chain (e.g. a mixed
   Kotlin/Java/multi-module project where only some source roots are listed) but the *first* real-looking
   frame still isn't source-resolvable for unrelated reasons — skip the frames we know are ours to skip,
   not just the ones we can prove aren't useful.

Gate behind the existing `sources` flag, not a new config property: only run this when
`Tracing.StartOptions.sources` was `true` for that session — if the caller never asked for embedded source,
don't touch `trace.stacks` at all (stay a no-op, zero behavior change for existing consumers who don't use
this feature).

## Implementation steps

1. Add a package-private helper (e.g. `TraceSourceSanitizer` in `io.qameta.allure.playwright`) with:
   - `static void sanitize(Path trace)` — opens `trace` as a zip (`ZipInputStream`/`ZipOutputStream`),
     copies every entry through unchanged except `trace.stacks`, which gets rewritten via step 2. Fails
     open: any `IOException`/parse failure leaves `trace` untouched and just logs (this must never be the
     reason a trace fails to attach at all — same principle `aurora-light`'s version follows).
   - JSON rewrite logic: parse `{"files": [...], "stacks": [[id, [[fileIdx, line, col, name], ...]], ...]}`,
     for each stack find the first frame that's either (a) file-resolvable per the primary heuristic, or
     (b) the first frame *after* a run of frames matching the synthetic-pattern denylist, trim the frame
     list to start there. `aurora-light`'s `PlaywrightTraceSourcesLifecycle.sanitizeStacksJson` /
     `indexOfFirstSourcedFrame` (in `shared/src/main/java/com/devexperts/uitests/shared/core/extensions/`)
     is a working reference implementation for the primary-heuristic-only version — port the structure,
     add the denylist check as an additional early-exit condition inside the same frame loop.
   - **JSON library — checked, not just assumed:** `allure-playwright/build.gradle.kts` currently depends on
     nothing but `allure-java-commons` (`api`), Playwright/AspectJ (`compileOnly`), and test deps — no JSON
     library. `allure-java-commons` does pull in `jackson-databind`, but as an `internal(...)` configuration
     that gets **shaded/relocated** to `io.qameta.allure.internal.shadowed.jackson.*` at build time
     (`allure-java-commons/build.gradle.kts`'s `relocate(...)`) — not something `allure-playwright` should
     casually depend on across module boundaries; it's packaging-internal to `allure-java-commons`, not a
     published API. Two real options, pick one deliberately rather than assuming either:
     1. Add a normal, direct Jackson dependency scoped to `allure-playwright` itself (the module already
        isn't dependency-light — it pulls in Playwright and AspectJ — so one more well-known library isn't
        a huge departure, but it does mean Jackson on this module's own compile/runtime classpath).
     2. Hand-roll a minimal parser/rewriter for just this narrow, fixed shape (`{"files":[...strings...],
        "stacks":[[int,[[int,int,int,string],...]],...]}`) — no new dependency at all, more code to
        maintain but self-contained. Given `trace.stacks` is Playwright's own stable wire format (not
        something `allure-playwright` controls or that changes often), this is a reasonable trade for a
        library that otherwise stays lean.
2. Call `TraceSourceSanitizer.sanitize(trace)` from `DefaultTraceSession.stop(boolean attach)`, immediately
   after `context.tracing().stop(...)` succeeds, guarded by `attach` (only worth doing when the trace is
   actually going to be attached) and by whatever this session's own `sources` option was (may need to
   thread that boolean through `DefaultTraceSession`'s constructor, since `Tracing.StartOptions` isn't
   retained today per the class's current fields — check before assuming it's already available).
3. Add a `AllurePlaywrightConfig` property to allow opting out (e.g.
   `allure.playwright.sanitize-trace-sources`, default `true`) in case some consumer relies on the raw,
   unmodified stack for something else — cheap insurance, matches this library's existing pattern of one
   boolean flag per opt-in/out behavior (`close.trace`, `steps.enabled`, etc.).

## Testing / verification

- Unit test the pure JSON transform (`TraceSourceSanitizer`'s frame-trimming logic) directly against a
  small fixture `trace.stacks` payload — no browser needed, this part is pure data transformation.
- Integration-level verification (what actually caught the real bug, not just a plausible-looking unit
  test): capture a real trace against a page with an aspect-woven `Page`/`Locator` call, unzip it, parse
  `trace.stacks`, and assert frame 0 of a known action's stack resolves to a real, non-blank file — this is
  exactly the manual technique used to find and confirm the fix in `aurora-light` (unzip → `json.load` →
  check `files[frames[0][0]]` is non-empty for every stack), worth keeping as an actual test rather than a
  one-off script.
- Before considering this ready to propose: run the module-scoped quality gate per `AGENTS.md`
  (`:allure-playwright:spotlessCheck checkstyleMain pmdMain spotbugsMain`, or the aggregate command if this
  touches shared build logic) and fix or `spotlessApply` as needed.

## Relationship to other changes around this feature (updated as of this fork's current state)

- `0e414c33` (branch `fix/playwright-trace-content-type`) — `AllurePlaywright.attachTrace()` content-type fix
  (`application/vnd.allure.playwright-trace` instead of generic `application/zip`), landed as a commit.
- `.setSources(true)` on `AllurePlaywright.startTracing()`'s default `Tracing.StartOptions` is no longer this
  fork's own uncommitted change — it landed unconditionally from `upstream/main` itself (pulled in via the
  `main` fast-forward to `c588b137`).
- That unconditional `setSources(true)` has since been made configurable on `feat/playwright-trace-sources-flag`
  (pushed to `origin`): `allure.playwright.trace.sources` (default `false` — opt-in, not opt-out; flipped from
  this doc's original `true` suggestion) gates the option, and the resolved boolean is threaded into
  `DefaultTraceSession` for the sanitizer below to use.
- `TraceSourceSanitizer` itself is now built on the same branch — see "Concrete before/after example" above for
  what it actually does and how the shipped algorithm differs from this doc's original per-frame sketch. Also
  implemented: `allure.playwright.trace.sanitize-sources` (default `true`) as the opt-out this doc's
  "Implementation steps" section called for, and the `trace.stacks`-existence short-circuit described above.
  Uses Gson (`compileOnly`) rather than either option this doc originally weighed (a new direct Jackson
  dependency, or a hand-rolled parser) — `com.microsoft.playwright:playwright` already requires Gson at compile
  scope in its own POM, so any real consumer already has it on their classpath at zero added cost.
- Test coverage landed alongside it: `TraceSourceSanitizerTest`, covering the pure JSON transform (trimmed,
  untrimmed, multi-stack, fail-open-on-invalid-zip, skip-when-no-stacks-entry cases) plus one end-to-end test
  that captures a real trace via its own `Playwright` instance with `PLAYWRIGHT_JAVA_SRC` set through
  `Playwright.CreateOptions().setEnv(...)`, sanitizes it, and asserts frame 0 resolves to the real caller —
  matching the "Testing / verification" section below.

This sanitization fix is a natural next piece of the same story — embedding source only helps if the frame
Trace Viewer reads is trustworthy, and right now (per the reproduction above) it isn't for any consumer
running the aspect (i.e. almost everyone, since `steps.enabled` defaults to `true`). Consider whether to land
this as its own PR, or split further along the boundary already reflected in the two commits on
`feat/playwright-trace-sources-flag` (the config flag, then the sanitizer) — either way, still not opened as a
PR pending your go-ahead per `AGENTS.md`.
