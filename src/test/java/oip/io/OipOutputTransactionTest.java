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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OipOutputTransactionTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void failedStandalonePromotionRestoresPreviousCompleteOutput()
            throws Exception {
        File output = temporary.newFolder("output");
        File liveProfiles = new File(output, "Profiles");
        assertTrue(liveProfiles.mkdir());
        File previous = new File(liveProfiles, "Per_Object_Profiles.csv");
        Files.write(previous.toPath(), "previous".getBytes(StandardCharsets.UTF_8));
        File staging = new File(output, ".OIP_test_staging");
        assertTrue(staging.mkdir());
        createStagedTree(staging, "replacement");
        final AtomicBoolean injected = new AtomicBoolean();

        boolean failed = false;
        try {
            OipOutputWriter.promoteStagedOutput(output, staging, null,
                    new OipOutputWriter.PromotionFault() {
                        @Override
                        public void afterInstall(File live, int installedCount)
                                throws IOException {
                            if (installedCount == 1) {
                                injected.set(true);
                                throw new IOException("Injected publication failure");
                            }
                        }

                        @Override
                        public void beforeRestore(File backup, File live)
                                throws IOException {
                        }
                    });
        } catch (IOException expected) {
            failed = true;
        }

        assertTrue(failed);
        assertTrue(injected.get());
        assertEquals("previous", new String(
                Files.readAllBytes(previous.toPath()), StandardCharsets.UTF_8));
        assertTrue(!new File(output, "Profiles/Object_Summaries.csv").exists());
        assertTrue(findChild(output, ".OIP_backup_") == null);
    }

    @Test
    public void caseOnlyFigureAndMapRenamesRemainOwned() throws Exception {
        File output = temporary.newFolder("case-output");
        File liveFigures = new File(output, "Figures");
        File liveMaps = new File(output, "Maps");
        assertTrue(liveFigures.mkdir());
        assertTrue(liveMaps.mkdir());
        String oldFigure = "OIP_Signal__group__Radial.png";
        String newFigure = "OIP_signal__group__Radial.png";
        String oldMap = "OIP_Signal_Texture_Classes.tif";
        String newMap = "OIP_signal_Texture_Classes.tif";
        Files.write(new File(liveFigures, oldFigure).toPath(),
                "old figure".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(liveFigures, ".OIP_figures_manifest.txt").toPath(),
                java.util.Collections.singletonList(oldFigure), StandardCharsets.UTF_8);
        Files.write(new File(liveMaps, oldMap).toPath(),
                "old map".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(liveMaps, ".OIP_maps_manifest.txt").toPath(),
                java.util.Collections.singletonList(oldMap), StandardCharsets.UTF_8);

        File staging = new File(output, ".OIP_case_staging");
        assertTrue(staging.mkdir());
        createStagedTree(staging, "new table");
        Files.write(new File(staging, "Figures/" + newFigure).toPath(),
                "new figure".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(staging, "Figures/.OIP_figures_manifest.txt").toPath(),
                java.util.Collections.singletonList(newFigure), StandardCharsets.UTF_8);
        Files.write(new File(staging, "Maps/" + newMap).toPath(),
                "new map".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(staging, "Maps/.OIP_maps_manifest.txt").toPath(),
                java.util.Collections.singletonList(newMap), StandardCharsets.UTF_8);

        OipOutputWriter.promoteStagedOutput(output, staging, null, null);

        assertEquals("new figure", new String(Files.readAllBytes(
                new File(liveFigures, newFigure).toPath()), StandardCharsets.UTF_8));
        assertEquals("new map", new String(Files.readAllBytes(
                new File(liveMaps, newMap).toPath()), StandardCharsets.UTF_8));
        assertEquals(newFigure, Files.readAllLines(
                new File(liveFigures, ".OIP_figures_manifest.txt").toPath(),
                StandardCharsets.UTF_8).get(0));
        assertEquals(newMap, Files.readAllLines(
                new File(liveMaps, ".OIP_maps_manifest.txt").toPath(),
                StandardCharsets.UTF_8).get(0));
    }

    private static void createStagedTree(File staging, String value) throws Exception {
        String[] directories = {"Profiles", "Texture", "Aggregate", "Figures", "Maps"};
        for (String directory : directories) {
            assertTrue(new File(staging, directory).mkdir());
        }
        String[] tables = {
            "Profiles/Per_Object_Profiles.csv",
            "Profiles/Object_Summaries.csv",
            "Texture/Object_Texture.csv",
            "Texture/Quantization_Ranges.csv",
            "Aggregate/Aggregate_Profiles.csv"
        };
        for (String table : tables) {
            Files.write(new File(staging,
                    table.replace('/', File.separatorChar)).toPath(),
                    value.getBytes(StandardCharsets.UTF_8));
        }
        Files.write(new File(staging, "Figures/.OIP_figures_manifest.txt").toPath(),
                new byte[0]);
        Files.write(new File(staging, "Maps/.OIP_maps_manifest.txt").toPath(),
                new byte[0]);
    }

    private static File findChild(File output, String prefix) {
        File[] children = output.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.getName().startsWith(prefix)) return child;
        }
        return null;
    }
}
