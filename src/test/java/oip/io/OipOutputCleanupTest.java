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
package oip.io;

import oip.ObjectIntensityProfiling;
import oip.OipParameters;
import oip.profile.ObjectProfileFigureWriter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

public class OipOutputCleanupTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void cancellationPreventsStaleFigureDeletion() throws Exception {
        File directory = temporary.newFolder("figures");
        File stale = new File(directory, "OIP_stale.png");
        Files.write(stale.toPath(), "stale".getBytes(StandardCharsets.UTF_8));

        boolean cancelled = false;
        try {
            ObjectProfileFigureWriter.clearFigures(directory, cancelled());
        } catch (ObjectIntensityProfiling.AnalysisCancelledException expected) {
            cancelled = true;
        }

        assertTrue(cancelled);
        assertTrue(stale.isFile());
    }

    @Test
    public void cancellationPreventsStaleMapDeletion() throws Exception {
        File directory = temporary.newFolder("maps");
        File stale = new File(directory, "OIP_stale.tif");
        Files.write(stale.toPath(), "stale".getBytes(StandardCharsets.UTF_8));

        boolean cancelled = false;
        try {
            OipOutputWriter.clearTiffMaps(directory, cancelled());
        } catch (ObjectIntensityProfiling.AnalysisCancelledException expected) {
            cancelled = true;
        }

        assertTrue(cancelled);
        assertTrue(stale.isFile());
    }

    @Test
    public void fixedFigureTempLinkCannotOverwriteExternalFile() throws Exception {
        File directory = temporary.newFolder("figure-temp-link");
        File external = temporary.newFile("figure-external.txt");
        Files.write(external.toPath(), "outside".getBytes(StandardCharsets.UTF_8));
        if (!createLink(new File(directory, ".OIP_figures_manifest.txt.tmp"), external)) {
            return;
        }

        ObjectProfileFigureWriter.clearFigures(directory);

        assertTrue("outside".equals(new String(
                Files.readAllBytes(external.toPath()), StandardCharsets.UTF_8)));
    }

    @Test
    public void fixedMapTempLinkCannotOverwriteExternalFile() throws Exception {
        File directory = temporary.newFolder("map-temp-link");
        File external = temporary.newFile("map-external.txt");
        Files.write(external.toPath(), "outside".getBytes(StandardCharsets.UTF_8));
        if (!createLink(new File(directory, ".OIP_maps_manifest.txt.tmp"), external)) {
            return;
        }

        OipOutputWriter.clearTiffMaps(directory, null);

        assertTrue("outside".equals(new String(
                Files.readAllBytes(external.toPath()), StandardCharsets.UTF_8)));
    }

    @Test
    public void figureManifestCannotClaimSvgThatPluginNeverCreates() throws Exception {
        File directory = temporary.newFolder("svg-manifest");
        File svg = new File(directory, "OIP_source__group__Radial.svg");
        Files.write(svg.toPath(), "unrelated".getBytes(StandardCharsets.UTF_8));
        Files.write(ObjectProfileFigureWriter.figureManifest(directory).toPath(),
                java.util.Collections.singletonList(svg.getName()),
                StandardCharsets.UTF_8);

        boolean rejected = false;
        try {
            ObjectProfileFigureWriter.clearFigures(directory);
        } catch (java.io.IOException expected) {
            rejected = true;
        }

        assertTrue(rejected);
        assertTrue(svg.isFile());
    }

    private static boolean createLink(File link, File target) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath());
            return true;
        } catch (UnsupportedOperationException unsupported) {
            return false;
        } catch (java.io.IOException unavailableWithoutPrivilege) {
            return false;
        } catch (SecurityException unavailableWithoutPrivilege) {
            return false;
        }
    }

    private static OipParameters.CancellationToken cancelled() {
        return new OipParameters.CancellationToken() {
            @Override
            public boolean isCancelled() {
                return true;
            }
        };
    }
}
