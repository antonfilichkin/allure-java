/*
 *  Copyright 2016-2026 Qameta Software Inc
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.qameta.allure.playwright;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Trims the AspectJ/Playwright-weaving frames that {@code AllurePlaywrightAspect} inserts in front of every
 * advised Playwright call before a trace's {@code trace.stacks} entry is used by Trace Viewer.
 *
 * <p>Without this, {@code Tracing.StartOptions.setSources(true)} embeds source that's technically correct but
 * practically useless: Trace Viewer's Source tab reads frame 0 of each action's captured stack, and frame 0 is
 * always a synthetic frame the weaving machinery inserted, not the real caller. The real caller is still
 * present a few frames deeper in the same stack; this only drops the frames in front of it.</p>
 *
 * <p>Fails open: any problem reading, parsing, or rewriting the trace leaves the original file untouched and
 * just logs a warning. A bug here must never be the reason a trace fails to attach at all.</p>
 */
final class TraceSourceSanitizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceSourceSanitizer.class);

    private static final String STACKS_ENTRY = "trace.stacks";

    private static final Pattern SYNTHETIC_FRAME = Pattern.compile(
            "^org\\.aspectj\\.runtime\\.reflect\\.JoinPointImpl\\."
                    + "|^io\\.qameta\\.allure\\.playwright\\.AllurePlaywrightAspect\\."
                    + "|_aroundBody\\d+"
                    + "|\\$AjcClosure\\d+"
    );

    private TraceSourceSanitizer() {
    }

    /**
     * Rewrites {@code trace.stacks} inside the given trace archive in place, if present.
     *
     * @param trace path to a Playwright trace zip, as produced by {@code Tracing.stop()}.
     */
    static void sanitize(final Path trace) {
        Path rewritten = null;
        try {
            rewritten = Files.createTempFile("allure-playwright-trace-sanitized-", ".zip");
            if (rewrite(trace, rewritten)) {
                Files.move(rewritten, trace, StandardCopyOption.REPLACE_EXISTING);
                rewritten = null;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not sanitize Playwright trace sources, attaching the trace unchanged", e);
        } finally {
            delete(rewritten);
        }
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static boolean rewrite(final Path trace, final Path rewritten) throws IOException {
        boolean stacksFound = false;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(trace));
                ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(rewritten))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                final byte[] content = readAll(zis);
                zos.putNextEntry(new ZipEntry(entry.getName()));
                if (STACKS_ENTRY.equals(entry.getName())) {
                    stacksFound = true;
                    zos.write(sanitizeStacksJson(content));
                } else {
                    zos.write(content);
                }
                zos.closeEntry();
            }
        }
        return stacksFound;
    }

    /**
     * Pure JSON transform: trims each recorded stack down to its first real-looking frame.
     *
     * @param stacksJson the raw {@code trace.stacks} entry content, shaped as
     *         {@code {"files": [...], "stacks": [[callId, [[fileIndex, line, column, name], ...]], ...]}}.
     * @return the rewritten content, same shape, synthetic leading frames removed from each stack.
     */
    static byte[] sanitizeStacksJson(final byte[] stacksJson) {
        final JsonObject root = JsonParser.parseString(new String(stacksJson, StandardCharsets.UTF_8))
                .getAsJsonObject();
        final JsonArray files = root.getAsJsonArray("files");
        final JsonArray stacks = root.getAsJsonArray("stacks");
        for (JsonElement stackElement : stacks) {
            final JsonArray stackEntry = stackElement.getAsJsonArray();
            final JsonArray frames = stackEntry.get(1).getAsJsonArray();
            final int cut = firstUsableFrameIndex(files, frames);
            if (cut > 0) {
                stackEntry.set(1, trim(frames, cut));
            }
        }
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Finds where a stack should be cut, combining two signals.
     *
     * <p><b>Primary:</b> the first frame anywhere in the stack that's file-resolvable. This is authoritative
     * wherever it fires — once {@code PLAYWRIGHT_JAVA_SRC} is configured, only frames under the consumer's own
     * source root ever resolve, so the shallowest resolved frame is the real caller, no matter how many
     * library-internal frames sit between it and frame 0.</p>
     *
     * <p><b>Backstop:</b> if no frame anywhere resolves a file (e.g. {@code sources(true)} was set without
     * ever configuring {@code PLAYWRIGHT_JAVA_SRC}), fall back to trimming the leading run of frames
     * recognizable as synthetic AspectJ/Playwright-weaving glue. If that run reaches the end of the stack with
     * nothing left after it, leave the stack untouched instead of trimming it down to nothing.</p>
     */
    private static int firstUsableFrameIndex(final JsonArray files, final JsonArray frames) {
        for (int i = 0; i < frames.size(); i++) {
            if (isFileResolved(files, frames.get(i).getAsJsonArray())) {
                return i;
            }
        }
        int i = 0;
        while (i < frames.size() && isSyntheticFrame(frames.get(i).getAsJsonArray())) {
            i++;
        }
        return i < frames.size() ? i : 0;
    }

    private static boolean isFileResolved(final JsonArray files, final JsonArray frame) {
        final int fileIndex = frame.get(0).getAsInt();
        return fileIndex >= 0
                && fileIndex < files.size()
                && !files.get(fileIndex).getAsString().isEmpty();
    }

    private static boolean isSyntheticFrame(final JsonArray frame) {
        return SYNTHETIC_FRAME.matcher(frame.get(3).getAsString()).find();
    }

    private static JsonArray trim(final JsonArray frames, final int cut) {
        final JsonArray trimmed = new JsonArray();
        for (int i = cut; i < frames.size(); i++) {
            trimmed.add(frames.get(i));
        }
        return trimmed;
    }

    private static byte[] readAll(final InputStream in) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static void delete(final Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.debug("Could not delete temporary file {}", path, e);
        }
    }
}
