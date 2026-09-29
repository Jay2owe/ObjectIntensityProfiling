/*-
 * #%L
 * Per-object, cross-channel intensity profiles and texture measurements for ImageJ and Fiji
 * %%
 * Copyright (C) 2026 Jamie Malcolm
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the UK Dementia Research Institute at Imperial College London nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package oip.golden;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Guards every CSV (and class-map TIFF) the plugin writes against silent change.
 *
 * <p>Compare mode (default) checks each output against
 * {@code src/test/resources/oip/golden/digests.txt}; on mismatch the canonical dumps are
 * written to {@code target/golden-dumps/<case>/} so the difference can be diffed. Update mode
 * ({@code -Doip.golden.update=true}, optionally with {@code -Doip.golden.reason="stage NN: why"})
 * rewrites the digest file. Every case runs serially ({@code oip.parallelism=1}) and with
 * the default worker count; both must produce identical outputs.</p>
 */
public class GoldenOutputTest {

    static final String RESOURCE = "/oip/golden/digests.txt";
    private static final String PARALLELISM = "oip.parallelism";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void everyCaseMatchesCommittedDigests() throws Exception {
        Map<String, String> actual = new TreeMap<String, String>();
        Map<String, String> dumps = new TreeMap<String, String>();
        for (GoldenCases.Case c : GoldenCases.all()) {
            long start = System.nanoTime();
            Map<String, String> serialDumps = run(c, "1");
            long serialDone = System.nanoTime();
            Map<String, String> parallelDumps = run(c, null);
            System.out.printf("golden %-40s serial %6d ms, parallel %6d ms%n", c.name,
                    (serialDone - start) / 1000000L, (System.nanoTime() - serialDone) / 1000000L);
            assertTrue(c.name + " produced no outputs", !serialDumps.isEmpty());
            Map<String, String> serial = digests(serialDumps);
            Map<String, String> parallel = digests(parallelDumps);
            if (!serial.equals(parallel)) {
                writeDumps(serialDumps, "serial");
                writeDumps(parallelDumps, "parallel");
            }
            assertEquals(c.name + " serial vs parallel", serial, parallel);
            actual.putAll(serial);
            dumps.putAll(serialDumps);
        }

        if (Boolean.getBoolean("oip.golden.update")) {
            write(actual);
            return;
        }
        Map<String, String> expected = readExpected();
        List<String> problems = new ArrayList<String>();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String got = actual.get(entry.getKey());
            if (got == null) problems.add("missing output: " + entry.getKey());
            else if (!got.equals(entry.getValue())) problems.add("changed: " + entry.getKey());
        }
        for (String key : actual.keySet()) {
            if (!expected.containsKey(key)) problems.add("unexpected output: " + key);
        }
        if (!problems.isEmpty()) {
            writeDumps(dumps, null);
            fail("Golden outputs differ (canonical dumps in target/golden-dumps/):\n  "
                    + String.join("\n  ", problems));
        }
    }

    private Map<String, String> run(GoldenCases.Case c, String parallelism) throws Exception {
        String previous = System.getProperty(PARALLELISM);
        File output = tmp.newFolder();
        File scratch = tmp.newFolder();
        try {
            if (parallelism == null) System.clearProperty(PARALLELISM);
            else System.setProperty(PARALLELISM, parallelism);
            c.runner.run(output, scratch);
        } finally {
            if (previous == null) System.clearProperty(PARALLELISM);
            else System.setProperty(PARALLELISM, previous);
        }
        return canonicalOutputs(c.name, output.toPath());
    }

    /** Canonical dump per output file, keyed {@code case/relative/path}. */
    static Map<String, String> canonicalOutputs(String caseName, Path root) throws IOException {
        Map<String, String> out = new TreeMap<String, String>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        for (Path file : files) {
            Path relative = root.relativize(file);
            if (hidden(relative)) continue;
            String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            String key = caseName + "/" + relative.toString().replace(File.separatorChar, '/');
            if (name.endsWith(".csv")) out.put(key, CanonicalDump.canonicalCsv(file));
            else if (name.endsWith(".tif") || name.endsWith(".tiff")) {
                out.put(key, CanonicalDump.canonicalTiff(file));
            }
        }
        return out;
    }

    private static boolean hidden(Path relative) {
        for (Path part : relative) {
            if (part.toString().startsWith(".")) return true;
        }
        return false;
    }

    private static Map<String, String> digests(Map<String, String> dumps) {
        Map<String, String> out = new TreeMap<String, String>();
        for (Map.Entry<String, String> entry : dumps.entrySet()) {
            out.put(entry.getKey(), CanonicalDump.sha256(entry.getValue()));
        }
        return out;
    }

    private static void writeDumps(Map<String, String> dumps, String variant) throws IOException {
        Path base = Paths.get(basedir(), "target", "golden-dumps");
        for (Map.Entry<String, String> entry : dumps.entrySet()) {
            String key = entry.getKey();
            if (variant != null) {
                int slash = key.indexOf('/');
                key = key.substring(0, slash) + "/" + variant + key.substring(slash);
            }
            Path target = base.resolve(key + ".txt");
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
    }

    static Map<String, String> readExpected() throws IOException {
        Map<String, String> out = new TreeMap<String, String>();
        InputStream stream = GoldenOutputTest.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            fail("Missing " + RESOURCE + "; run once with -Doip.golden.update=true");
        }
        String text;
        try (InputStream in = stream) {
            text = new String(readAll(in), StandardCharsets.UTF_8);
        }
        for (String line : text.split("\\R")) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.trim().split("\\s+");
            out.put(parts[0], parts[1]);
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) >= 0) buffer.write(chunk, 0, read);
        return buffer.toByteArray();
    }

    private static void write(Map<String, String> actual) throws IOException {
        Path file = Paths.get(basedir(), "src", "test", "resources", "oip", "golden", "digests.txt");
        List<String> changeLog = new ArrayList<String>();
        String provenance = null;
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("# change-log: ") && !line.contains("(append one line")) {
                    changeLog.add(line);
                }
                // Keep the baseline provenance so an update's diff shows only what changed.
                if (line.startsWith("# source-commit: ")) provenance = line;
            }
        }
        String reason = System.getProperty("oip.golden.reason");
        if (reason != null && !reason.trim().isEmpty()) {
            changeLog.add("# change-log: " + LocalDate.now() + " " + reason.trim()
                    + " (at " + sourceCommit() + ", jdk " + System.getProperty("java.version") + ")");
        }
        List<String> lines = new ArrayList<String>();
        lines.add("# Object Intensity Profiling golden output digests (SHA-256 of canonical dumps).");
        lines.add(provenance != null ? provenance
                : "# source-commit: " + sourceCommit() + "  jdk: " + System.getProperty("java.version")
                + "  date: " + LocalDate.now());
        lines.add("# Regenerate: sh ./mvnw -B test -Dtest=GoldenOutputTest -Doip.golden.update=true"
                + " -Doip.golden.reason=\"stage NN: why\"");
        lines.add("# change-log: (append one line per intended update: date, stage, reason)");
        lines.addAll(changeLog);
        for (Map.Entry<String, String> entry : actual.entrySet()) {
            lines.add(entry.getKey() + "  " + entry.getValue());
        }
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static String sourceCommit() {
        String supplied = System.getProperty("oip.golden.commit");
        if (supplied != null && !supplied.trim().isEmpty()) return supplied.trim();
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .directory(new File(basedir()))
                    .redirectErrorStream(true)
                    .start();
            String out = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 && !out.isEmpty() ? out : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String basedir() {
        String basedir = System.getProperty("basedir");
        return basedir == null ? System.getProperty("user.dir") : basedir;
    }
}
