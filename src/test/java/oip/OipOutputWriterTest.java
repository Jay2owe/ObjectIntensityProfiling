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
package oip;

import ij.ImagePlus;
import oip.profile.OipConfig;
import oip.profile.ObjectProfileFigureWriter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.awt.image.IndexColorModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OipOutputWriterTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void autoSaveCreatesExpectedOutputTree() throws Exception {
        ImagePlus labels = SyntheticImages.image("labels", 16, 16, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return 1;
                    }
                });
        ImagePlus raw = SyntheticImages.image("raw", 16, 16, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x + y;
                    }
                });
        OipConfig config = new OipConfig();
        config.doGlcm = true;
        config.doTextureClasses = true;
        config.minimumTextureVoxels = 16;
        File output = temporary.newFolder("output");
        File figures = new File(output, "Figures");
        File maps = new File(output, "Maps");
        assertTrue(figures.mkdirs());
        assertTrue(maps.mkdirs());
        Files.write(new File(figures, "keep.png").toPath(),
                "unrelated".getBytes(StandardCharsets.UTF_8));
        String ownedFigure = "OIP_source__group__Radial.png";
        Files.write(new File(figures, ownedFigure).toPath(),
                "stale".getBytes(StandardCharsets.UTF_8));
        Files.write(ObjectProfileFigureWriter.figureManifest(figures).toPath(),
                java.util.Collections.singletonList(ownedFigure), StandardCharsets.UTF_8);
        Files.write(new File(figures, "OIP_notes.png").toPath(),
                "unrelated".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(maps, "keep.tif").toPath(),
                "unrelated".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(maps, "OIP_notes.tif").toPath(),
                "unrelated".getBytes(StandardCharsets.UTF_8));

        OipResult result = ObjectIntensityProfiling.run(OipParameters.builder(labels)
                .addRawImage("raw", raw)
                .config(config)
                .saveFigures(false)
                .saveClassMaps(true)
                .autoSave(output)
                .build());

        assertTrue(new File(output, "Profiles/Per_Object_Profiles.csv").isFile());
        assertTrue(new File(output, "Profiles/Object_Summaries.csv").isFile());
        assertTrue(new File(output, "Texture/Object_Texture.csv").isFile());
        String textureCsv = new String(Files.readAllBytes(
                new File(output, "Texture/Object_Texture.csv").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(textureCsv.startsWith(
                "Label,VoxelCount,Partner,Suppressed,GLCMReliable,FeatureValid,"
                        + "FeatureReliable,"));
        assertTrue(new File(output, "Texture/Quantization_Ranges.csv").isFile());
        assertTrue(new File(output, "Aggregate/Aggregate_Profiles.csv").isFile());
        assertTrue(new File(output, "Figures").isDirectory());
        assertTrue(new File(output, "Maps").isDirectory());
        assertTrue(new File(output, "Maps/OIP_raw_Texture_Classes.tif").isFile());
        assertTrue(new File(output, "Figures/keep.png").isFile());
        assertTrue(!new File(output, "Figures/" + ownedFigure).exists());
        assertTrue(new File(output, "Figures/OIP_notes.png").isFile());
        assertTrue(new File(output, "Maps/keep.tif").isFile());
        assertTrue(new File(output, "Maps/OIP_notes.tif").isFile());
        ImagePlus classMap = result.getClassMaps().get("raw");
        assertTrue(classMap.getProcessor().getColorModel() instanceof IndexColorModel);
        assertEquals(8, classMap.getBitDepth());
        IndexColorModel palette = TextureClassMapRenderer.colors();
        assertTrue((palette.getRGB(16) & 0x00ffffff) != 0);
        assertTrue(palette.getRGB(16) != palette.getRGB(32));
    }

    @Test
    public void invalidTextureFeaturesAreFlaggedAndWrittenAsBlank() throws Exception {
        ImagePlus labels = SyntheticImages.image("labels", 16, 16, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return 1;
                    }
                });
        ImagePlus raw = SyntheticImages.image("raw", 16, 16, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return Float.NaN;
                    }
                });
        OipConfig config = new OipConfig();
        config.doTextureClasses = true;
        config.minimumTextureVoxels = 1;
        File output = temporary.newFolder("invalid-output");

        OipResult result = ObjectIntensityProfiling.run(OipParameters.builder(labels)
                .addRawImage("raw", raw)
                .config(config)
                .saveFigures(false)
                .saveClassMaps(false)
                .autoSave(output)
                .build());

        assertEquals(0.0,
                OipTables.textures(result).getValue("Feature valid", 0), 0.0);
        assertTrue(Double.isNaN(
                OipTables.textures(result).getValue("Gabor 0", 0)));
        String csv = new String(Files.readAllBytes(
                new File(output, "Texture/Object_Texture.csv").toPath()),
                StandardCharsets.UTF_8);
        String[] lines = csv.split("\\R");
        String[] values = lines[1].split(",", -1);
        assertEquals("false", values[5]);
        assertEquals("false", values[6]);
        assertEquals("", values[13]);
    }

    @Test
    public void unmanifestedExactClassMapTargetIsNotOverwritten() throws Exception {
        ImagePlus labels = SyntheticImages.image("labels", 16, 16, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return 1;
                    }
                });
        ImagePlus raw = SyntheticImages.image("raw", 16, 16, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x + y;
                    }
                });
        File output = temporary.newFolder("map-collision-output");
        File maps = new File(output, "Maps");
        assertTrue(maps.mkdirs());
        File target = new File(maps, "OIP_raw_Texture_Classes.tif");
        Files.write(target.toPath(), "unrelated".getBytes(StandardCharsets.UTF_8));
        OipConfig config = new OipConfig();
        config.doTextureClasses = true;
        config.textureClasses = 1;
        config.minimumTextureVoxels = 1;

        boolean rejected = false;
        try {
            ObjectIntensityProfiling.run(OipParameters.builder(labels)
                    .addRawImage("raw", raw)
                    .config(config)
                    .saveFigures(false)
                    .saveClassMaps(true)
                    .autoSave(output)
                    .build());
        } catch (IllegalStateException expected) {
            rejected = true;
        }

        assertTrue(rejected);
        assertEquals("unrelated", new String(
                Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        labels.close();
        raw.close();
    }

    @Test
    public void linkedCsvTargetIsRejectedWithoutTouchingExternalFile() throws Exception {
        ImagePlus labels = SyntheticImages.image("labels", 8, 8, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return 1;
                    }
                });
        ImagePlus raw = SyntheticImages.image("raw", 8, 8, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x + y;
                    }
                });
        File output = temporary.newFolder("linked-csv-output");
        File profiles = new File(output, "Profiles");
        assertTrue(profiles.mkdir());
        File external = temporary.newFile("external.csv");
        Files.write(external.toPath(), "outside".getBytes(StandardCharsets.UTF_8));
        File linked = new File(profiles, "Per_Object_Profiles.csv");
        try {
            Files.createSymbolicLink(linked.toPath(), external.toPath());
        } catch (UnsupportedOperationException unavailable) {
            labels.close();
            raw.close();
            return;
        } catch (java.io.IOException unavailableWithoutPrivilege) {
            labels.close();
            raw.close();
            return;
        } catch (SecurityException unavailableWithoutPrivilege) {
            labels.close();
            raw.close();
            return;
        }

        boolean rejected = false;
        try {
            ObjectIntensityProfiling.run(OipParameters.builder(labels)
                    .addRawImage("raw", raw)
                    .saveFigures(false)
                    .saveClassMaps(false)
                    .autoSave(output)
                    .build());
        } catch (IllegalStateException expected) {
            rejected = true;
        }

        assertTrue(rejected);
        assertEquals("outside", new String(
                Files.readAllBytes(external.toPath()), StandardCharsets.UTF_8));
        labels.close();
        raw.close();
    }

    @Test
    public void cancelledStandaloneSaveLeavesPreviousOutputUntouched() throws Exception {
        ImagePlus labels = SyntheticImages.image("labels", 8, 8, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return 1;
                    }
                });
        ImagePlus raw = SyntheticImages.image("raw", 8, 8, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x + y;
                    }
                });
        File output = temporary.newFolder("cancelled-save-output");
        File profiles = new File(output, "Profiles");
        assertTrue(profiles.mkdir());
        File previous = new File(profiles, "Per_Object_Profiles.csv");
        Files.write(previous.toPath(), "previous".getBytes(StandardCharsets.UTF_8));
        final AtomicBoolean cancelled = new AtomicBoolean();

        boolean stopped = false;
        try {
            ObjectIntensityProfiling.run(OipParameters.builder(labels)
                    .addRawImage("raw", raw)
                    .saveFigures(false)
                    .saveClassMaps(false)
                    .autoSave(output)
                    .progressListener(new OipParameters.ProgressListener() {
                        @Override
                        public void onProgress(double fraction, String message) {
                            if ("Saving results".equals(message)) cancelled.set(true);
                        }
                    })
                    .cancellationToken(new OipParameters.CancellationToken() {
                        @Override
                        public boolean isCancelled() {
                            return cancelled.get();
                        }
                    })
                    .build());
        } catch (ObjectIntensityProfiling.AnalysisCancelledException expected) {
            stopped = true;
        }

        assertTrue(stopped);
        assertEquals("previous", new String(
                Files.readAllBytes(previous.toPath()), StandardCharsets.UTF_8));
        assertTrue(findOutputChild(output, ".OIP_staging_") == null);
        assertTrue(findOutputChild(output, ".OIP_backup_") == null);
        labels.close();
        raw.close();
    }

    private static File findOutputChild(File output, String prefix) {
        File[] children = output.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.getName().startsWith(prefix)) return child;
        }
        return null;
    }
}
