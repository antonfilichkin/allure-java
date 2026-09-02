# Sanitize AspectJ-shadowed source frames in Playwright traces

**Status:** draft implementation guide, not yet started. Written from `aurora-light` (a downstream
consumer of `allure-playwright`), where this exact fix was implemented, verified against a real trace, and
confirmed working live in Trace Viewer. This doc ports that proven fix into `allure-playwright` itself so
every consumer gets it, not just one project's local workaround.

Per `AGENTS.md`: **no PR or branch push without explicit user confirmation.** This file is a plan to review
first.

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

## Relationship to the two changes already in this fork

This fork already has (see `git log`/`git diff`):
- `0e414c33` — `AllurePlaywright.attachTrace()` content-type fix (`application/vnd.allure.playwright-trace`
  instead of generic `application/zip`), landed as a commit.
- an uncommitted change adding `.setSources(true)` to `AllurePlaywright.startTracing()`'s own default
  `Tracing.StartOptions` (currently only sets `screenshots`/`snapshots`).

This sanitization fix is a natural third piece of the same story — the `setSources(true)` change makes the
library actually try to embed source; without this fix, that embedded source is mostly useless for any
consumer running the aspect (i.e. almost everyone, since `steps.enabled` defaults to `true`). Consider
whether to land these as one coherent PR ("make embedded trace sources actually usable") or as separate,
independently-reviewable PRs referencing each other — separate is probably easier to review given they
touch different files/concerns, but say so explicitly in each PR description so reviewers see the full
picture.
