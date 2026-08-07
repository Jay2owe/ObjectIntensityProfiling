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
import ij.io.FileSaver;
import oip.profile.OipConfig;
import oip.texture.QuantizationRange;
import oip.io.FileNameSupport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class OipBatchRunnerTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void pairsFilesAndUsesOneQuantisationRangeAcrossSamples() throws Exception {
        File labels = temporary.newFolder("labels");
        File raw = temporary.newFolder("raw");
        File reference = temporary.newFolder("reference");
        File output = temporary.newFolder("output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(labels, "B_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        save(new File(raw, "B_raw.tif"), checker(100));
        save(new File(reference, "A_reference.tif"), checker(20));
        save(new File(reference, "B_reference.tif"), checker(200));

        OipConfig config = new OipConfig();
        config.doGlcm = true;
        config.minimumTextureVoxels = 1;
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .addRawChannel("Reference", reference, "(.*)_reference\\.tif")
                .referenceChannel("Reference")
                .config(config)
                .saveFigures(false)
                .saveClassMaps(false)
                .build();

        String preview = OipBatchRunner.preview(parameters);
        assertTrue(preview.contains("A:"));
        assertTrue(preview.contains("B:"));
        OipBatchResult result = OipBatchRunner.run(parameters);

        assertEquals(2, result.getSampleCount());
        assertEquals(2, result.getObjectCount());
        assertTrue(new File(output, "Aggregate/Aggregate_Profiles.csv").isFile());
        assertTrue(new File(output, "Samples/A/Texture/Object_Texture.csv").isFile());
        File rangeFile = new File(output, "Samples/A/Texture/Quantization_Ranges.csv");
        String ranges = new String(Files.readAllBytes(rangeFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(ranges.contains("Signal,0.0,100.0"));
        File aggregateFile = new File(output, "Aggregate/Aggregate_Profiles.csv");
        String aggregate = new String(
                Files.readAllBytes(aggregateFile.toPath()), StandardCharsets.UTF_8);
        int combinedRows = 0;
        for (String line : aggregate.split("\\R")) {
            if (line.startsWith("Labels,Signal,Radial,(batch),")
                    && line.endsWith(",2")) {
                combinedRows++;
            }
        }
        assertTrue("Expected populated radial bins combining both samples", combinedRows > 0);
        assertTrue(!aggregate.contains("A,Signal,Radial,(batch)"));
        assertTrue(!aggregate.contains("B,Signal,Radial,(batch)"));
    }

    /**
     * The single-image writer refuses to publish over a figure the manifest does not list. Batch
     * promotion has to refuse too: it moves with ATOMIC_MOVE, which replaces an existing regular
     * file, and it backs up only manifested names — so an unmanifested collision was destroyed.
     * The manifest is a hidden dotfile and is easily lost by a copy or a zip round-trip while the
     * PNGs survive, which is how a live figure ends up unmanifested.
     */
    @Test
    public void batchPromotionRefusesToOverwriteAnUnmanifestedFigure() throws Exception {
        File labels = temporary.newFolder("fig-labels");
        File raw = temporary.newFolder("fig-raw");
        File output = temporary.newFolder("fig-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));

        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .referenceChannel("Signal")
                .saveFigures(true)
                .saveClassMaps(false)
                .build();
        OipBatchRunner.run(parameters);

        File figures = new File(output, "Figures");
        File[] published = figures.listFiles();
        File victim = null;
        for (File file : published) {
            if (file.getName().endsWith(".png")) { victim = file; break; }
        }
        assertNotNull("expected the first run to publish a figure", victim);

        // Lose the manifest but keep the PNGs, then overwrite one with a file we own.
        File manifest = new File(figures, ".OIP_figures_manifest.txt");
        assertTrue(manifest.isFile());
        assertTrue(manifest.delete());
        Files.write(victim.toPath(), "unrelated".getBytes(StandardCharsets.UTF_8));

        boolean rejected = false;
        try {
            OipBatchRunner.run(parameters);
        } catch (RuntimeException expected) {
            rejected = true;
        }

        assertTrue("rerun should refuse to replace an unmanifested figure", rejected);
        assertEquals("unrelated", new String(
                Files.readAllBytes(victim.toPath()), StandardCharsets.UTF_8));
    }

    /**
     * Promotion backs the live aggregate table up and deletes the backup on commit, so a
     * non-regular target at that path was moved away wholesale and destroyed with anything inside
     * it. The single-image writer already refused this; the batch path did not.
     */
    @Test
    public void batchPromotionRefusesANonRegularAggregateTarget() throws Exception {
        assertNonRegularPromotionTargetIsRefused("agg", "Aggregate/Aggregate_Profiles.csv");
    }

    /** The same guard has to cover the figures and the figure manifest, not just the CSV. */
    @Test
    public void batchPromotionRefusesANonRegularFigureTarget() throws Exception {
        assertNonRegularPromotionTargetIsRefused(
                "fig-nr", "Figures/OIP_Labels__%28batch%29__Radial.png");
    }

    @Test
    public void batchPromotionRefusesANonRegularFigureManifest() throws Exception {
        assertNonRegularPromotionTargetIsRefused(
                "figman", "Figures/.OIP_figures_manifest.txt");
    }

    /**
     * Runs a batch once so the target becomes plugin-owned, replaces that target with a directory
     * containing a user file, and requires the rerun to refuse without destroying it. Promotion
     * backs each target up and deletes the backup on commit, so an unguarded non-regular target is
     * moved away wholesale and lost.
     */
    private void assertNonRegularPromotionTargetIsRefused(String prefix, String relativeTarget)
            throws Exception {
        File labels = temporary.newFolder(prefix + "-labels");
        File raw = temporary.newFolder(prefix + "-raw");
        File output = temporary.newFolder(prefix + "-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));

        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .referenceChannel("Signal")
                .saveFigures(true)
                .saveClassMaps(false)
                .build();
        OipBatchRunner.run(parameters);

        File target = new File(output, relativeTarget);
        assertTrue("expected the first run to create " + relativeTarget, target.isFile());
        assertTrue(target.delete());
        assertTrue(target.mkdir());
        File inside = new File(target, "user_notes.txt");
        Files.write(inside.toPath(), "keep me".getBytes(StandardCharsets.UTF_8));

        boolean rejected = false;
        try {
            OipBatchRunner.run(parameters);
        } catch (RuntimeException expected) {
            rejected = true;
        }

        assertTrue("rerun should refuse the non-regular target " + relativeTarget, rejected);
        assertTrue("the directory must survive", target.isDirectory());
        assertEquals("keep me", new String(
                Files.readAllBytes(inside.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void preservesSurroundingWhitespaceInBatchRawAndReferenceChannelNames()
            throws Exception {
        File labels = temporary.newFolder("spaced-labels");
        File raw = temporary.newFolder("spaced-raw");
        File output = temporary.newFolder("spaced-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal ", raw, "(.*)_raw\\.tif")
                .referenceChannel("Signal ")
                .saveFigures(false)
                .saveClassMaps(false)
                .build();

        OipBatchResult result = OipBatchRunner.run(parameters);

        assertEquals("Signal ", parameters.getReferenceChannel());
        assertEquals(1, result.getSampleCount());
    }

    @Test
    public void manualBatchRangeOverridesAutomaticScanForThatChannel() throws Exception {
        File labels = temporary.newFolder("manual-labels");
        File raw = temporary.newFolder("manual-raw");
        File output = temporary.newFolder("manual-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(100));
        OipConfig config = new OipConfig();
        config.doGlcm = true;
        config.minimumTextureVoxels = 1;
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .config(config)
                .quantizationRange("Signal", new QuantizationRange(-5, 5))
                .saveFigures(false)
                .saveClassMaps(false)
                .build();
        OipBatchRunner.run(parameters);
        File rangeFile = new File(output,
                "Samples/A/Texture/Quantization_Ranges.csv");
        String ranges = new String(
                Files.readAllBytes(rangeFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(ranges.contains("Signal,-5.0,5.0"));
    }

    @Test
    public void savesGloballyAssignedTextureClassMapsWhenRequested() throws Exception {
        File labels = temporary.newFolder("map-labels");
        File raw = temporary.newFolder("map-raw");
        File output = temporary.newFolder("map-output");
        save(new File(labels, "A_labels.tif"), labels(16));
        save(new File(raw, "A_raw.tif"), checker(100, 16));
        OipConfig config = new OipConfig();
        config.doGlcm = true;
        config.doTextureClasses = true;
        config.textureClasses = 1;
        config.minimumTextureVoxels = 1;
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .config(config)
                .saveFigures(false)
                .saveClassMaps(true)
                .build();

        OipBatchRunner.run(parameters);

        assertTrue(new File(output,
                "Samples/A/Maps/OIP_Signal_Texture_Classes.tif").isFile());
        String textureCsv = new String(Files.readAllBytes(new File(output,
                "Samples/A/Texture/Object_Texture.csv").toPath()), StandardCharsets.UTF_8);
        String[] textureValues = textureCsv.split("\\R")[1].split(",", -1);
        assertEquals("1", textureValues[21]);
    }

    @Test
    public void recursiveRerunExcludesNestedOutputTree() throws Exception {
        File labels = temporary.newFolder("nested-labels");
        File raw = temporary.newFolder("nested-raw");
        File output = new File(labels, "Object Intensity Profiling Results");
        save(new File(labels, "A.tif"), labels(16));
        save(new File(raw, "A.tif"), checker(100, 16));
        OipConfig config = new OipConfig();
        config.doTextureClasses = true;
        config.textureClasses = 1;
        config.minimumTextureVoxels = 1;
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)\\.tif")
                .config(config)
                .saveFigures(false)
                .saveClassMaps(true)
                .build();

        OipBatchRunner.run(parameters);
        assertTrue(new File(output,
                "Samples/A/Maps/OIP_Signal_Texture_Classes.tif").isFile());
        OipBatchResult rerun = OipBatchRunner.run(parameters);

        assertEquals(1, rerun.getSampleCount());
        assertEquals(1, rerun.getObjectCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOutputDirectoryEqualToInputRoot() throws Exception {
        File labels = temporary.newFolder("same-output-labels");
        File raw = temporary.newFolder("same-output-raw");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", labels)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .build();

        OipBatchRunner.preview(parameters);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInputRootNestedInsideOutputDirectory() throws Exception {
        File output = temporary.newFolder("contains-input-output");
        File labels = new File(output, "Samples/A");
        assertTrue(labels.mkdirs());
        File raw = temporary.newFolder("contains-input-raw");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .build();

        OipBatchRunner.preview(parameters);
    }

    @Test
    public void batchMacroRunsWithoutInteractiveDialogs() throws Exception {
        File labels = temporary.newFolder("macro-labels");
        File raw = temporary.newFolder("macro-raw");
        File output = temporary.newFolder("macro-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        OipBatchMacroOptions options = new OipBatchMacroOptions();
        options.labelFolder = labels.getAbsolutePath();
        options.labelRegex = "(.*)_labels\\.tif";
        options.rawNames[0] = "Signal";
        options.rawFolders[0] = raw.getAbsolutePath();
        options.rawRegexes[0] = "(.*)_raw\\.tif";
        options.referenceChannel = "Signal";
        options.outputDirectory = output.getAbsolutePath();
        options.saveFigures = false;
        options.saveClassMaps = false;
        options.hideDisplay = true;
        new Object_Intensity_Profiling().runMacroOptions(options.toMacroOptions());
        assertTrue(new File(output,
                "Samples/A/Profiles/Object_Summaries.csv").isFile());
    }

    @Test
    public void unusedPrefilledOptionalRawFoldersAreIgnored() throws Exception {
        File labels = temporary.newFolder("default-labels");
        File raw = temporary.newFolder("default-raw");
        File output = temporary.newFolder("default-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        OipBatchMacroOptions options = new OipBatchMacroOptions();
        options.labelFolder = labels.getAbsolutePath();
        options.labelRegex = "(.*)_labels\\.tif";
        options.outputDirectory = output.getAbsolutePath();
        options.rawNames[0] = "Signal";
        options.rawFolders[0] = raw.getAbsolutePath();
        options.rawRegexes[0] = "(.*)_raw\\.tif";
        options.referenceChannel = "Signal";
        options.rawFolders[1] = labels.getAbsolutePath();
        options.rawFolders[2] = labels.getAbsolutePath();
        options.rawFolders[3] = labels.getAbsolutePath();
        OipBatchParameters parameters =
                new Object_Intensity_Profiling().batchParameters(options);
        assertEquals(1, parameters.getRawSpecs().size());
        assertTrue(OipBatchRunner.preview(parameters).contains("A:"));
        String recorded = options.toMacroOptions();
        assertTrue(!recorded.contains("raw2_folder"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void batchParametersRejectRangeForUnconfiguredRawSlot() throws Exception {
        File labels = temporary.newFolder("unused-range-labels");
        File raw = temporary.newFolder("unused-range-raw");
        File output = temporary.newFolder("unused-range-output");
        OipBatchMacroOptions options = new OipBatchMacroOptions();
        options.labelFolder = labels.getAbsolutePath();
        options.labelRegex = "(.*)_labels\\.tif";
        options.outputDirectory = output.getAbsolutePath();
        options.rawNames[0] = "Signal";
        options.rawFolders[0] = raw.getAbsolutePath();
        options.rawRegexes[0] = "(.*)_raw\\.tif";
        options.referenceChannel = "Signal";
        options.quantMin[1] = 0.0;
        options.quantMax[1] = 255.0;

        new Object_Intensity_Profiling().batchParameters(options);
    }

    @Test
    public void encodedOutputNamesCannotCollideAfterSanitizingCharacters() {
        assertNotEquals(FileNameSupport.encode("A B"), FileNameSupport.encode("A_B"));
        assertNotEquals(FileNameSupport.encode("CON"), FileNameSupport.encode("%43ON"));
        assertTrue(!FileNameSupport.encode("A_B").contains("__"));
        assertTrue(FileNameSupport.isCanonicalEncoding(FileNameSupport.encode("Notes. ")));
        assertTrue(!FileNameSupport.isCanonicalEncoding("Notes."));
        assertTrue(!FileNameSupport.isCanonicalEncoding("%2f"));
        assertTrue(!FileNameSupport.isCanonicalEncoding("%FF"));
        boolean rejected = false;
        try {
            FileNameSupport.requireUniqueEncoded(
                    java.util.Arrays.asList("A", "a"), "Sample");
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    @Test
    public void successfulRerunRemovesOnlyManifestedSamplesNoLongerPresent() throws Exception {
        File labels = temporary.newFolder("rerun-labels");
        File raw = temporary.newFolder("rerun-raw");
        File output = temporary.newFolder("rerun-output");
        File bLabel = new File(labels, "B_labels.tif");
        File bRaw = new File(raw, "B_raw.tif");
        save(new File(labels, "A_labels.tif"), labels());
        save(bLabel, labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        save(bRaw, checker(20));
        OipConfig config = new OipConfig();
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .config(config)
                .saveFigures(false)
                .saveClassMaps(false)
                .build();
        OipBatchRunner.run(parameters);
        File unrelated = new File(output, "Samples/notes");
        assertTrue(unrelated.mkdirs());
        Files.write(new File(unrelated, "keep.txt").toPath(),
                "keep".getBytes(StandardCharsets.UTF_8));
        Files.delete(bLabel.toPath());
        Files.delete(bRaw.toPath());
        OipBatchRunner.run(parameters);
        assertTrue(new File(output, "Samples/A").isDirectory());
        assertTrue(!new File(output, "Samples/B").exists());
        assertTrue(new File(unrelated, "keep.txt").isFile());
    }

    @Test
    public void caseOnlySampleRenameIsTreatedAsSamePortableDiskIdentity()
            throws Exception {
        File labels = temporary.newFolder("case-labels");
        File raw = temporary.newFolder("case-raw");
        File output = temporary.newFolder("case-output");
        File oldLabel = new File(labels, "SampleA_labels.tif");
        File oldRaw = new File(raw, "SampleA_raw.tif");
        save(oldLabel, labels());
        save(oldRaw, checker(10));
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .saveFigures(false)
                .saveClassMaps(false)
                .build();
        OipBatchRunner.run(parameters);

        File temporaryLabel = new File(labels, "rename_labels.tmp");
        File temporaryRaw = new File(raw, "rename_raw.tmp");
        Files.move(oldLabel.toPath(), temporaryLabel.toPath());
        Files.move(oldRaw.toPath(), temporaryRaw.toPath());
        Files.move(temporaryLabel.toPath(),
                new File(labels, "samplea_labels.tif").toPath());
        Files.move(temporaryRaw.toPath(),
                new File(raw, "samplea_raw.tif").toPath());

        OipBatchResult result = OipBatchRunner.run(parameters);

        assertEquals(1, result.getSampleCount());
        assertTrue(new File(output,
                "Samples/samplea/Profiles/Object_Summaries.csv").isFile());
        java.util.List<String> manifest = Files.readAllLines(
                new File(output, "Samples/.OIP_samples_manifest.txt").toPath(),
                StandardCharsets.UTF_8);
        assertEquals(java.util.Collections.singletonList("samplea"), manifest);
    }

    @Test
    public void cancelledRerunLeavesPreviousLiveBatchUntouched() throws Exception {
        File labels = temporary.newFolder("transaction-labels");
        File raw = temporary.newFolder("transaction-raw");
        File output = temporary.newFolder("transaction-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        OipBatchParameters baseline = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .saveFigures(false)
                .saveClassMaps(false)
                .build();
        OipBatchRunner.run(baseline);
        File liveSummary = new File(output,
                "Samples/A/Profiles/Object_Summaries.csv");
        byte[] before = Files.readAllBytes(liveSummary.toPath());
        File sentinel = new File(output, "Samples/A/previous.txt");
        Files.write(sentinel.toPath(), "keep".getBytes(StandardCharsets.UTF_8));

        save(new File(labels, "B_labels.tif"), labels());
        save(new File(raw, "B_raw.tif"), checker(20));
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        OipBatchParameters interrupted = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .saveFigures(false)
                .saveClassMaps(false)
                .progressListener(new OipParameters.ProgressListener() {
                    @Override
                    public void onProgress(double fraction, String message) {
                        if ("Analysing B".equals(message)) cancelled.set(true);
                    }
                })
                .cancellationToken(new OipParameters.CancellationToken() {
                    @Override
                    public boolean isCancelled() {
                        return cancelled.get();
                    }
                })
                .build();
        boolean wasCancelled = false;
        try {
            OipBatchRunner.run(interrupted);
        } catch (ObjectIntensityProfiling.AnalysisCancelledException expected) {
            wasCancelled = true;
        }

        assertTrue(wasCancelled);
        assertTrue(java.util.Arrays.equals(before,
                Files.readAllBytes(liveSummary.toPath())));
        assertTrue(sentinel.isFile());
        assertTrue(!new File(output, "Samples/B").exists());
        File[] outputChildren = output.listFiles();
        if (outputChildren != null) {
            for (File child : outputChildren) {
                assertTrue(!child.getName().startsWith(".OIP_staging_"));
                assertTrue(!child.getName().startsWith(".OIP_backup_"));
            }
        }
    }

    @Test
    public void promotionFailureAfterSampleInstallRestoresPreviousSample() throws Exception {
        File labels = temporary.newFolder("rollback-labels");
        File raw = temporary.newFolder("rollback-raw");
        File output = temporary.newFolder("rollback-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .saveFigures(false)
                .saveClassMaps(false)
                .build();
        OipBatchRunner.run(parameters);
        File sentinel = new File(output, "Samples/A/previous.txt");
        Files.write(sentinel.toPath(), "old".getBytes(StandardCharsets.UTF_8));

        File aggregateDirectory = new File(output, "Aggregate");
        Files.delete(new File(aggregateDirectory, "Aggregate_Profiles.csv").toPath());
        Files.delete(aggregateDirectory.toPath());
        Files.write(aggregateDirectory.toPath(), "blocks-directory"
                .getBytes(StandardCharsets.UTF_8));
        boolean failed = false;
        try {
            OipBatchRunner.run(parameters);
        } catch (IllegalStateException expected) {
            failed = true;
        }

        assertTrue(failed);
        assertTrue(sentinel.isFile());
        assertEquals("old", new String(
                Files.readAllBytes(sentinel.toPath()), StandardCharsets.UTF_8));
        assertTrue(aggregateDirectory.isFile());
        assertTrue(findOutputChild(output, ".OIP_staging_") == null);
        assertTrue(findOutputChild(output, ".OIP_backup_") == null);
    }

    @Test
    public void failedRestorationRetainsRecoveryBackup() throws Exception {
        File labels = temporary.newFolder("recovery-labels");
        File raw = temporary.newFolder("recovery-raw");
        File output = temporary.newFolder("recovery-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .saveFigures(false)
                .saveClassMaps(false)
                .build();
        OipBatchRunner.run(parameters);
        File sentinel = new File(output, "Samples/A/previous.txt");
        Files.write(sentinel.toPath(), "recover-me".getBytes(StandardCharsets.UTF_8));

        File staging = new File(output, ".OIP_manual_staging");
        assertTrue(staging.mkdir());
        File stagedSample = new File(staging, "Samples/A");
        assertTrue(stagedSample.mkdirs());
        Files.write(new File(stagedSample, "replacement.txt").toPath(),
                "new".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(stagedSample, ".OIP_owned_sample").toPath(),
                java.util.Arrays.asList(
                        "Object Intensity Profiling sample v1", "A"),
                StandardCharsets.UTF_8);
        Files.write(new File(staging, "Samples/.OIP_samples_manifest.txt").toPath(),
                java.util.Collections.singletonList("A"), StandardCharsets.UTF_8);
        File stagedAggregate = new File(staging, "Aggregate");
        assertTrue(stagedAggregate.mkdirs());
        Files.write(new File(stagedAggregate, "Aggregate_Profiles.csv").toPath(),
                "new aggregate".getBytes(StandardCharsets.UTF_8));

        boolean failed = false;
        try {
            OipBatchRunner.promoteStagedRun(parameters, staging,
                    java.util.Collections.singletonList("A"),
                    new OipBatchRunner.PromotionFault() {
                        @Override
                        public void afterInstall(File live, int installedCount)
                                throws java.io.IOException {
                            if (installedCount == 1) {
                                throw new java.io.IOException("Injected install failure");
                            }
                        }

                        @Override
                        public void beforeRestore(File backup, File live)
                                throws java.io.IOException {
                            throw new java.io.IOException("Injected restore failure");
                        }
                    });
        } catch (IllegalStateException expected) {
            failed = true;
        }

        assertTrue(failed);
        File recovery = findOutputChild(output, ".OIP_backup_");
        assertTrue(recovery != null && recovery.isDirectory());
        File recoveredSentinel = new File(recovery, "Samples/A/previous.txt");
        assertTrue(recoveredSentinel.isFile());
        assertEquals("recover-me", new String(
                Files.readAllBytes(recoveredSentinel.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void concurrentBatchPromotionsAreSerializedByOutputDirectory()
            throws Exception {
        File labels = temporary.newFolder("locked-labels");
        File raw = temporary.newFolder("locked-raw");
        File output = temporary.newFolder("locked-output");
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .saveFigures(false)
                .saveClassMaps(false)
                .build();
        File firstStaging = stagedPromotion(output, "first", "A");
        File secondStaging = stagedPromotion(output, "second", "A");
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        Thread first = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    OipBatchRunner.promoteStagedRun(parameters, firstStaging,
                            java.util.Collections.singletonList("A"),
                            new OipBatchRunner.PromotionFault() {
                                @Override
                                public void afterInstall(File live, int installedCount)
                                        throws IOException {
                                    if (installedCount == 1) {
                                        firstEntered.countDown();
                                        try {
                                            releaseFirst.await();
                                        } catch (InterruptedException interrupted) {
                                            Thread.currentThread().interrupt();
                                            throw new IOException(interrupted);
                                        }
                                    }
                                }

                                @Override
                                public void beforeRestore(File backup, File live) {
                                }
                            });
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            }
        }, "oip-first-promotion");
        Thread second = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    OipBatchRunner.promoteStagedRun(parameters, secondStaging,
                            java.util.Collections.singletonList("A"),
                            new OipBatchRunner.PromotionFault() {
                                @Override
                                public void afterInstall(File live, int installedCount) {
                                    secondEntered.countDown();
                                }

                                @Override
                                public void beforeRestore(File backup, File live) {
                                }
                            });
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            }
        }, "oip-second-promotion");

        first.start();
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
        second.start();
        boolean secondWasBlocked = !secondEntered.await(250, TimeUnit.MILLISECONDS);
        releaseFirst.countDown();
        first.join(5000);
        second.join(5000);

        assertTrue(secondWasBlocked);
        assertTrue(!first.isAlive());
        assertTrue(!second.isAlive());
        if (failure.get() != null) {
            throw new AssertionError("Concurrent promotion failed", failure.get());
        }
        assertTrue(secondEntered.getCount() == 0);
        assertEquals("second", new String(Files.readAllBytes(
                new File(output, "Samples/A/version.txt").toPath()),
                StandardCharsets.UTF_8));
    }

    @Test
    public void unmanifestedCurrentSampleCollisionIsRejectedWithoutDeletion()
            throws Exception {
        File labels = temporary.newFolder("collision-labels");
        File raw = temporary.newFolder("collision-raw");
        File output = temporary.newFolder("collision-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        File unrelated = new File(output, "Samples/A");
        assertTrue(unrelated.mkdirs());
        File sentinel = new File(unrelated, "unrelated.txt");
        Files.write(sentinel.toPath(), "do-not-delete".getBytes(StandardCharsets.UTF_8));
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .saveFigures(false)
                .saveClassMaps(false)
                .build();

        boolean failed = false;
        try {
            OipBatchRunner.run(parameters);
        } catch (IllegalStateException expected) {
            failed = true;
        }

        assertTrue(failed);
        assertTrue(sentinel.isFile());
        assertEquals("do-not-delete", new String(
                Files.readAllBytes(sentinel.toPath()), StandardCharsets.UTF_8));
        assertTrue(findOutputChild(output, ".OIP_staging_") == null);
        assertTrue(findOutputChild(output, ".OIP_backup_") == null);
    }

    @Test
    public void forgedManifestEntryCannotClaimUnrelatedStaleDirectory()
            throws Exception {
        File labels = temporary.newFolder("forged-labels");
        File raw = temporary.newFolder("forged-raw");
        File output = temporary.newFolder("forged-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .saveFigures(false)
                .saveClassMaps(false)
                .build();
        OipBatchRunner.run(parameters);
        File unrelated = new File(output, "Samples/Notes");
        assertTrue(unrelated.mkdirs());
        File sentinel = new File(unrelated, "keep.txt");
        Files.write(sentinel.toPath(), "unrelated".getBytes(StandardCharsets.UTF_8));
        File manifest = new File(output, "Samples/.OIP_samples_manifest.txt");
        Files.write(manifest.toPath(), java.util.Arrays.asList("A", "Notes"),
                StandardCharsets.UTF_8);

        boolean failed = false;
        try {
            OipBatchRunner.run(parameters);
        } catch (IllegalStateException expected) {
            failed = true;
        }

        assertTrue(failed);
        assertTrue(sentinel.isFile());
        assertEquals("unrelated", new String(
                Files.readAllBytes(sentinel.toPath()), StandardCharsets.UTF_8));
        assertTrue(findOutputChild(output, ".OIP_staging_") == null);
        assertTrue(findOutputChild(output, ".OIP_backup_") == null);
    }

    @Test
    public void linkedLiveSamplesContainerCannotWriteOutsideOutput() throws Exception {
        File labels = temporary.newFolder("linked-labels");
        File raw = temporary.newFolder("linked-raw");
        File output = temporary.newFolder("linked-output");
        File external = temporary.newFolder("linked-external");
        File sentinel = new File(external, "keep.txt");
        Files.write(sentinel.toPath(), "outside".getBytes(StandardCharsets.UTF_8));
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        try {
            Files.createSymbolicLink(new File(output, "Samples").toPath(),
                    external.toPath());
        } catch (UnsupportedOperationException unsupported) {
            return;
        } catch (java.io.IOException unavailableWithoutPrivilege) {
            return;
        } catch (SecurityException unavailableWithoutPrivilege) {
            return;
        }
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .saveFigures(false)
                .saveClassMaps(false)
                .build();

        boolean failed = false;
        try {
            OipBatchRunner.run(parameters);
        } catch (IllegalStateException expected) {
            failed = true;
        }

        assertTrue(failed);
        assertTrue(sentinel.isFile());
        assertEquals("outside", new String(
                Files.readAllBytes(sentinel.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void linkedBatchOutputRootIsRejectedBeforeCanonicalization()
            throws Exception {
        File labels = temporary.newFolder("root-link-labels");
        File raw = temporary.newFolder("root-link-raw");
        File external = temporary.newFolder("root-link-external");
        File sentinel = new File(external, "keep.txt");
        Files.write(sentinel.toPath(), "outside".getBytes(StandardCharsets.UTF_8));
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        File linkedOutput = new File(temporary.getRoot(), "batch-output-link");
        try {
            Files.createSymbolicLink(linkedOutput.toPath(), external.toPath());
        } catch (UnsupportedOperationException unsupported) {
            return;
        } catch (java.io.IOException unavailableWithoutPrivilege) {
            return;
        } catch (SecurityException unavailableWithoutPrivilege) {
            return;
        }
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", linkedOutput)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .saveFigures(false)
                .saveClassMaps(false)
                .build();

        boolean rejected = false;
        try {
            OipBatchRunner.preview(parameters);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }

        assertTrue(rejected);
        assertEquals("outside", new String(
                Files.readAllBytes(sentinel.toPath()), StandardCharsets.UTF_8));
        assertTrue(new File(external, "Samples").exists() == false);
    }

    @Test
    public void recursiveInputLinkCycleIsRejected() throws Exception {
        File labels = temporary.newFolder("cycle-labels");
        File raw = temporary.newFolder("cycle-raw");
        File output = temporary.newFolder("cycle-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        try {
            Files.createSymbolicLink(new File(labels, "cycle").toPath(),
                    labels.toPath());
        } catch (UnsupportedOperationException unsupported) {
            return;
        } catch (java.io.IOException unavailableWithoutPrivilege) {
            return;
        } catch (SecurityException unavailableWithoutPrivilege) {
            return;
        }
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .recursive(true)
                .build();

        boolean rejected = false;
        try {
            OipBatchRunner.preview(parameters);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }

        assertTrue(rejected);
    }

    @Test(expected = ObjectIntensityProfiling.AnalysisCancelledException.class)
    public void batchPairingCanBeCancelledDuringDirectoryCollection() throws Exception {
        File labels = temporary.newFolder("cancel-labels");
        File raw = temporary.newFolder("cancel-raw");
        File output = temporary.newFolder("cancel-output");
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .cancellationToken(new OipParameters.CancellationToken() {
                    @Override
                    public boolean isCancelled() {
                        return true;
                    }
                })
                .build();
        OipBatchRunner.preview(parameters);
    }

    @Test(expected = IllegalArgumentException.class)
    public void previewRejectsMismatchedImageDimensionsBeforeAnalysis() throws Exception {
        File labels = temporary.newFolder("preflight-labels");
        File raw = temporary.newFolder("preflight-raw");
        File output = temporary.newFolder("preflight-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"),
                SyntheticImages.image("raw", 7, 8, 1, new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x + y;
                    }
                }));
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .build();

        OipBatchRunner.preview(parameters);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonParticipatingCaptureGroupSampleKey() throws Exception {
        File labels = temporary.newFolder("capture-labels");
        File raw = temporary.newFolder("capture-raw");
        File output = temporary.newFolder("capture-output");
        save(new File(labels, "A.tif"), labels());
        save(new File(raw, "A.tif"), checker(10));
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(?:A|(B))\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)\\.tif")
                .build();

        OipBatchRunner.preview(parameters);
    }

    @Test(expected = IllegalArgumentException.class)
    public void previewRejectsFractionalLabelPixels() throws Exception {
        File labels = temporary.newFolder("fractional-labels");
        File raw = temporary.newFolder("fractional-raw");
        File output = temporary.newFolder("fractional-output");
        save(new File(labels, "A_labels.tif"),
                SyntheticImages.image("labels", 8, 8, 1, new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return 1.25f;
                    }
                }));
        save(new File(raw, "A_raw.tif"), checker(10));
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .build();

        OipBatchRunner.preview(parameters);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBatchRawChannelNamesThatCollideOnDisk() throws Exception {
        File labels = temporary.newFolder("channel-labels");
        File raw = temporary.newFolder("channel-raw");
        File output = temporary.newFolder("channel-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .addRawChannel("signal", raw, "(.*)_raw\\.tif")
                .referenceChannel("Signal")
                .build();

        OipBatchRunner.preview(parameters);
    }

    @Test
    public void streamsProfileOutputsAcrossManySamples() throws Exception {
        File labels = temporary.newFolder("many-labels");
        File raw = temporary.newFolder("many-raw");
        File output = temporary.newFolder("many-output");
        for (int i = 0; i < 12; i++) {
            String key = String.format(java.util.Locale.ROOT, "S%02d", i);
            save(new File(labels, key + "_labels.tif"), labels());
            save(new File(raw, key + "_raw.tif"), checker(10 + i));
        }
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .saveFigures(false)
                .saveClassMaps(false)
                .build();

        OipBatchResult result = OipBatchRunner.run(parameters);

        assertEquals(12, result.getSampleCount());
        assertEquals(12, result.getObjectCount());
        assertTrue(new File(output,
                "Samples/S00/Profiles/Object_Summaries.csv").isFile());
        assertTrue(new File(output,
                "Samples/S11/Profiles/Object_Summaries.csv").isFile());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRawSampleWithoutMatchingLabel() throws Exception {
        File labels = temporary.newFolder("orphan-labels");
        File raw = temporary.newFolder("orphan-raw");
        File output = temporary.newFolder("orphan-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        save(new File(raw, "B_raw.tif"), checker(20));
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .build();

        OipBatchRunner.preview(parameters);
    }

    @Test(expected = IllegalArgumentException.class)
    public void batchRejectsExcessiveProfileBinCount() throws Exception {
        File labels = temporary.newFolder("bins-labels");
        File raw = temporary.newFolder("bins-raw");
        File output = temporary.newFolder("bins-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(raw, "A_raw.tif"), checker(10));
        OipConfig config = new OipConfig();
        config.radialBins = 10001;
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .config(config)
                .build();

        OipBatchRunner.preview(parameters);
    }

    @Test(expected = IllegalArgumentException.class)
    public void previewRejectsLaterAllBackgroundLabelImage() throws Exception {
        File labels = temporary.newFolder("empty-labels");
        File raw = temporary.newFolder("empty-raw");
        File output = temporary.newFolder("empty-output");
        save(new File(labels, "A_labels.tif"), labels());
        save(new File(labels, "B_labels.tif"),
                SyntheticImages.image("empty", 8, 8, 1, new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return 0;
                    }
                }));
        save(new File(raw, "A_raw.tif"), checker(10));
        save(new File(raw, "B_raw.tif"), checker(20));
        OipBatchParameters parameters = OipBatchParameters.builder(
                        labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .build();

        OipBatchRunner.preview(parameters);
    }

    private static ImagePlus labels() {
        return labels(8);
    }

    private static ImagePlus labels(int size) {
        return SyntheticImages.image("labels", size, size, 1, new SyntheticImages.Pixel() {
            @Override
            public float value(int x, int y, int z) {
                return 1;
            }
        });
    }

    private static ImagePlus checker(final int high) {
        return checker(high, 8);
    }

    private static ImagePlus checker(final int high, int size) {
        return SyntheticImages.image("raw", size, size, 1, new SyntheticImages.Pixel() {
            @Override
            public float value(int x, int y, int z) {
                return ((x + y) & 1) == 0 ? 0 : high;
            }
        });
    }

    private static void save(File file, ImagePlus image) {
        assertTrue(new FileSaver(image).saveAsTiff(file.getAbsolutePath()));
        image.close();
    }

    private static File findOutputChild(File output, String prefix) {
        File[] children = output.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.getName().startsWith(prefix)) return child;
        }
        return null;
    }

    private static File stagedPromotion(File output, String version, String sample)
            throws Exception {
        File staging = new File(output, ".OIP_test_staging_" + version);
        assertTrue(staging.mkdir());
        File stagedSample = new File(staging, "Samples/" + sample);
        assertTrue(stagedSample.mkdirs());
        Files.write(new File(stagedSample, "version.txt").toPath(),
                version.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(stagedSample, ".OIP_owned_sample").toPath(),
                java.util.Arrays.asList(
                        "Object Intensity Profiling sample v1", sample),
                StandardCharsets.UTF_8);
        Files.write(new File(staging, "Samples/.OIP_samples_manifest.txt").toPath(),
                java.util.Collections.singletonList(sample), StandardCharsets.UTF_8);
        File aggregate = new File(staging, "Aggregate");
        assertTrue(aggregate.mkdir());
        Files.write(new File(aggregate, "Aggregate_Profiles.csv").toPath(),
                version.getBytes(StandardCharsets.UTF_8));
        File figures = new File(staging, "Figures");
        assertTrue(figures.mkdir());
        Files.write(new File(figures, ".OIP_figures_manifest.txt").toPath(),
                new byte[0]);
        return staging;
    }
}
