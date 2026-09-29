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

import oip.SmokeRuns;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Compares the Fiji smoke run's 2D tables with the same run under JUnit, by canonical digest.
 * Skipped unless {@code -Doip.smoke.dir=<SMOKE_DIR of run-smoke.sh>} is given:
 *
 * <pre>sh ./mvnw -B -q test -Dtest=SmokeParityTest -Doip.smoke.dir=/path/to/smoke</pre>
 */
public class SmokeParityTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void fijiAndJUnitGiveIdentical2dTables() throws Exception {
        String dir = System.getProperty("oip.smoke.dir");
        Assume.assumeTrue("set -Doip.smoke.dir to compare with a Fiji smoke run",
                dir != null && dir.trim().length() > 0);
        File smoke = new File(dir);
        File inputs = new File(smoke, "inputs");
        File fiji = new File(smoke, "out2d");
        assertTrue("run src/test/fiji/run-smoke.sh first: " + fiji, fiji.isDirectory());
        File junit = temporary.newFolder("out2d");
        String path = inputs.getAbsolutePath().replace('\\', '/') + "/";
        // Exactly the options oip-smoke.ijm uses for its 2D run.
        SmokeRuns.runHeadless("labels_path=[" + path + "labels2d.tif] "
                + "raw1_path=[" + path + "centre2d.tif] raw1_name=[A] "
                + "raw2_path=[" + path + "rim2d.tif] raw2_name=[B] reference=[A] "
                + "glcm texture_classes glcm_levels=16 texture_k=2 minimum_texture_voxels=16 "
                + "zernike profile_classes profile_k=2 radial_bins=6 "
                + "no_figures no_maps auto_save output=["
                + junit.getAbsolutePath().replace('\\', '/') + "/] hide_display");
        Map<String, String> expected = digests(junit.toPath());
        Map<String, String> actual = digests(fiji.toPath());
        assertTrue(expected.size() >= 7);
        assertEquals(expected, actual);
    }

    private static Map<String, String> digests(Path root) throws IOException {
        Map<String, String> out = new TreeMap<String, String>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!path.toString().endsWith(".csv")) continue;
                out.put(root.relativize(path).toString().replace('\\', '/'),
                        CanonicalDump.sha256(CanonicalDump.canonicalCsv(path)));
            }
        }
        return out;
    }
}
