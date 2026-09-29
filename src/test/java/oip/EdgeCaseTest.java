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

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import oip.profile.ObjectProfileResult;
import oip.profile.OipConfig;
import oip.profile.ProfileAggregator;
import oip.profile.ProfileShapeClassifier;
import oip.texture.ObjectTextureResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Regression tests for the stage-06 edge-case sweep; one section per input hazard. */
public class EdgeCaseTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    // ------------------------------------------------------------------ RGB inputs

    @Test
    public void rgbLabelAndRawImagesAreRejected() throws Exception {
        ImagePlus rgb = new ImagePlus("rgb", new ColorProcessor(16, 16));
        assertRejected(OipParameters.builder(rgb).addRawImage("Signal", raw(16, 16, 1)),
                "RGB colour images are not supported (label image)");
        assertRejected(OipParameters.builder(discs(16, 16, 1))
                        .addRawImage("Signal", new ImagePlus("rgb", new ColorProcessor(16, 16))),
                "RGB colour images are not supported (raw channel Signal)");

        File labels = temporary.newFolder("rgb-labels");
        File raws = temporary.newFolder("rgb-raw");
        save(new File(labels, "A_labels.tif"), discs(16, 16, 1));
        save(new File(raws, "A_raw.tif"), new ImagePlus("rgb", new ColorProcessor(16, 16)));
        assertBatchRejected(labels, raws, "RGB colour images are not supported "
                + "(raw channel Signal of sample A)");
    }

    // ------------------------------------------------------------------ calibration

    @Test
    public void rawCalibrationThatDiffersFromTheLabelsIsRejected() throws Exception {
        ImagePlus labels = discs(24, 24, 3);
        labels.setCalibration(calibration(0.2, 0.2, 0.5, "µm"));
        ImagePlus raw = raw(24, 24, 3);
        raw.setCalibration(calibration(0.4, 0.4, 0.5, "µm"));
        assertRejected(OipParameters.builder(labels).addRawImage("Signal", raw),
                "Raw calibration differs from the label image (raw channel Signal)");

        ImagePlus sameInMicrons = raw(24, 24, 3);
        sameInMicrons.setCalibration(calibration(0.2, 0.2, 0.5, "micron"));
        ObjectIntensityProfiling.run(OipParameters.builder(labels)
                .addRawImage("Signal", sameInMicrons).saveFigures(false).build());
        // An uncalibrated raw channel is accepted: TIFFs often lose their calibration.
        ObjectIntensityProfiling.run(OipParameters.builder(labels)
                .addRawImage("Signal", raw(24, 24, 3)).saveFigures(false).build());

        File labelFolder = temporary.newFolder("cal-labels");
        File rawFolder = temporary.newFolder("cal-raw");
        save(new File(labelFolder, "A_labels.tif"), labels);
        save(new File(rawFolder, "A_raw.tif"), raw);
        assertBatchRejected(labelFolder, rawFolder,
                "Raw calibration differs from the label image (raw channel Signal of sample A)");
    }

    // ------------------------------------------------------------------ float labels

    @Test
    public void floatLabelsAboveTwoToTheTwentyFourAreRejected() {
        FloatProcessor exact = new FloatProcessor(8, 8);
        exact.setf(2, 2, 16777216f);
        OipResult result = ObjectIntensityProfiling.run(OipParameters.builder(
                new ImagePlus("exact", exact)).addRawImage("Signal", raw(8, 8, 1))
                .saveFigures(false).build());
        assertEquals(16777216, result.getProfiles().get(0).label);

        FloatProcessor inexact = new FloatProcessor(8, 8);
        inexact.setf(2, 2, 16777218f);
        assertRejected(OipParameters.builder(new ImagePlus("inexact", inexact))
                        .addRawImage("Signal", raw(8, 8, 1)),
                "Label value 16777218 is above 16,777,216");
    }

    // ------------------------------------------------------------------ 2D inputs

    @Test
    public void twoDimensionalZAndThirdAxisCurvesAreSingleBinAsDocumented() {
        OipResult result = ObjectIntensityProfiling.run(OipParameters.builder(discs(40, 40, 1))
                .addRawImage("Signal", raw(40, 40, 1)).saveFigures(false).build());
        for (ObjectProfileResult object : result.getProfiles()) {
            for (ObjectProfileResult.PartnerProfiles partner : object.byPartner.values()) {
                assertEquals(1, finiteCount(partner.marginalZRaw));
                assertEquals(1, finiteCount(partner.pcThirdRaw));
                assertEquals(partner.marginalZRaw.length / 2, firstFinite(partner.marginalZRaw));
            }
        }
    }

    // ------------------------------------------------------------------ tiny objects

    @Test
    public void tinyObjectsGiveFiniteOrBlankValuesNeverHugeOnes() throws Exception {
        for (int depth : new int[] {1, 4}) {
            for (OipConfig.Region region : OipConfig.Region.values()) {
                FloatProcessor slice = new FloatProcessor(20, 20);
                slice.setf(2, 2, 1);                                    // one voxel
                for (int x = 5; x < 11; x++) slice.setf(x, 6, 2);       // one row
                for (int y = 10; y < 16; y++) slice.setf(14, y, 3);     // one column
                slice.setf(17, 17, 4);
                slice.setf(18, 18, 4);                                  // diagonal pair
                ImageStack stack = new ImageStack(20, 20);
                stack.addSlice(slice);
                for (int z = 1; z < depth; z++) stack.addSlice(new FloatProcessor(20, 20));
                OipConfig config = new OipConfig();
                config.region = region;
                config.doGlcm = true;
                config.doTextureClasses = true;
                config.minimumTextureVoxels = 1;
                config.doZernike = true;
                config.doProfileClasses = true;
                config.radialBins = 2;
                File output = temporary.newFolder("tiny-" + depth + "-" + region);
                ObjectIntensityProfiling.run(OipParameters.builder(
                                new ImagePlus("tiny", stack))
                        .addRawImage("A", raw(20, 20, depth))
                        .addRawImage("B", constant(20, 20, depth, 7f))
                        .referenceChannel("A").config(config)
                        .saveFigures(true).autoSave(output).build());
                assertEveryNumberIsModest(output);
            }
        }
    }

    // ------------------------------------------------------------------ one and zero objects

    @Test
    public void oneObjectGivesBlankSemAndClampsBothClassFits() {
        FloatProcessor slice = new FloatProcessor(64, 64);
        for (int y = 8; y < 56; y++) for (int x = 8; x < 56; x++) slice.setf(x, y, 1);
        OipConfig config = new OipConfig();
        config.doTextureClasses = true;
        config.textureClasses = 4;
        config.minimumTextureVoxels = 16;
        config.doProfileClasses = true;
        config.profileClasses = 4;
        OipResult result = ObjectIntensityProfiling.run(OipParameters.builder(
                        new ImagePlus("one", slice)).addRawImage("Signal", raw(64, 64, 1))
                .config(config).saveFigures(false).build());
        for (ProfileAggregator.AggregatedProfile curve : result.getAggregatedProfiles()) {
            for (int i = 0; i < curve.n.length; i++) {
                if (curve.n[i] == 1) assertTrue(Double.isNaN(curve.sem[i]));
            }
        }
        assertEquals(1, result.getTextures().size());
        assertEquals("k=4 is clamped to the one usable object", 0,
                result.getTextures().get(0).classLabel);
        for (ProfileShapeClassifier.Assignment assignment : result.getProfileClasses()) {
            assertEquals(0, assignment.classLabel);
        }
    }

    @Test
    public void labelImageWithoutObjectsIsRejected() {
        assertRejected(OipParameters.builder(new ImagePlus("empty", new FloatProcessor(8, 8)))
                        .addRawImage("Signal", raw(8, 8, 1)),
                "The label image contains no positive object labels.");
    }

    // ------------------------------------------------------------------ batch empty samples

    @Test
    public void batchListsEveryEmptySampleInOneMessage() throws Exception {
        File labels = temporary.newFolder("empty-labels");
        File raws = temporary.newFolder("empty-raw");
        for (String key : new String[] {"A", "B", "C", "D"}) {
            boolean empty = key.equals("B") || key.equals("D");
            save(new File(labels, key + "_labels.tif"), empty
                    ? new ImagePlus("e", new FloatProcessor(16, 16)) : discs(16, 16, 1));
            save(new File(raws, key + "_raw.tif"), raw(16, 16, 1));
        }
        assertBatchRejected(labels, raws,
                "Label image contains no positive object labels for 2 samples: B, D.");
    }

    // ------------------------------------------------------------------ all-NaN and constant

    @Test
    public void allNaNChannelNamesTheChannelAndSampleAndConstantChannelIsUnreliable()
            throws Exception {
        OipConfig glcm = new OipConfig();
        glcm.doGlcm = true;
        glcm.minimumTextureVoxels = 4;
        ImagePlus nan = constant(24, 24, 1, Float.NaN);
        nan.setTitle("nan.tif");
        assertRejected(OipParameters.builder(discs(24, 24, 1)).addRawImage("Dim", nan)
                        .config(glcm),
                "Raw image has no finite pixels, so no fixed GLCM range can be set: "
                        + "channel Dim (nan.tif)");

        File labels = temporary.newFolder("nan-labels");
        File raws = temporary.newFolder("nan-raw");
        save(new File(labels, "S1_labels.tif"), discs(24, 24, 1));
        save(new File(raws, "S1_raw.tif"), nan);
        try {
            OipBatchRunner.run(OipBatchParameters.builder(labels, "(.*)_labels\\.tif",
                            temporary.newFolder("nan-out"))
                    .addRawChannel("Dim", raws, "(.*)_raw\\.tif").config(glcm)
                    .saveFigures(false).saveClassMaps(false).build());
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(
                    "channel Dim of sample S1 (S1_raw.tif)"));
        }

        OipResult constant = ObjectIntensityProfiling.run(OipParameters.builder(discs(24, 24, 1))
                .addRawImage("Flat", constant(24, 24, 1, 12f)).config(glcm)
                .saveFigures(false).build());
        assertFalse(constant.getTextures().isEmpty());
        for (ObjectTextureResult texture : constant.getTextures()) {
            assertFalse("a constant channel has no texture", texture.glcmReliable);
        }
    }

    // ------------------------------------------------------------------ progress

    @Test
    public void progressNeverGoesBackwards() throws Exception {
        OipConfig config = new OipConfig();
        config.doGlcm = true;
        config.doTextureClasses = true;
        config.minimumTextureVoxels = 4;
        config.doProfileClasses = true;
        config.radialBins = 4;
        final List<Double> single = new ArrayList<Double>();
        ObjectIntensityProfiling.run(OipParameters.builder(discs(40, 40, 3))
                .addRawImage("A", raw(40, 40, 3))
                .addRawImage("B", constant(40, 40, 3, 9f))
                .addRawImage("C", raw(40, 40, 3))
                .referenceChannel("A").config(config).saveFigures(false)
                .autoSave(temporary.newFolder("progress-single"))
                .progressListener(recorder(single)).build());
        assertMonotonic(single);

        File labels = temporary.newFolder("progress-labels");
        File a = temporary.newFolder("progress-a");
        File b = temporary.newFolder("progress-b");
        for (String key : new String[] {"S1", "S2", "S3"}) {
            save(new File(labels, key + "_labels.tif"), discs(32, 32, 2));
            save(new File(a, key + "_a.tif"), raw(32, 32, 2));
            save(new File(b, key + "_b.tif"), raw(32, 32, 2));
        }
        final List<Double> batch = new ArrayList<Double>();
        OipBatchRunner.run(OipBatchParameters.builder(labels, "(.*)_labels\\.tif",
                        temporary.newFolder("progress-out"))
                .addRawChannel("A", a, "(.*)_a\\.tif")
                .addRawChannel("B", b, "(.*)_b\\.tif")
                .quantizationRange("B", new oip.texture.QuantizationRange(0, 500))
                .referenceChannel("A").config(config).saveFigures(false)
                .progressListener(recorder(batch)).build());
        assertMonotonic(batch);
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    public void cancelledSingleImageRunLeavesThePreviousResultUntouched() throws Exception {
        final File output = temporary.newFolder("cancel-single");
        OipConfig config = new OipConfig();
        config.doGlcm = true;
        config.minimumTextureVoxels = 4;
        ObjectIntensityProfiling.run(OipParameters.builder(discs(40, 40, 2))
                .addRawImage("Signal", raw(40, 40, 2)).config(config)
                .autoSave(output).build());
        final ImagePlus changed = constant(40, 40, 2, 3f);
        final AtomicInteger counted = new AtomicInteger();
        ObjectIntensityProfiling.run(OipParameters.builder(discs(40, 40, 2))
                .addRawImage("Signal", changed).config(config)
                .autoSave(temporary.newFolder()).cancellationToken(counting(counted)).build());
        int total = counted.get();
        Map<String, String> before = snapshot(output);
        int cancelled = 0;
        for (int n = 0; n <= total; n += Math.max(1, total / 30)) {
            try {
                ObjectIntensityProfiling.run(OipParameters.builder(discs(40, 40, 2))
                        .addRawImage("Signal", changed).config(config)
                        .autoSave(output).cancellationToken(flipAfter(n)).build());
                before = snapshot(output);
            } catch (ObjectIntensityProfiling.AnalysisCancelledException expected) {
                cancelled++;
                assertEquals("cancelled after " + n + " checks", before, snapshot(output));
            }
            assertNoTransientDirectories(output);
        }
        assertTrue(cancelled > 5);
    }

    @Test
    public void cancelledBatchLeavesThePreviousBatchUntouched() throws Exception {
        File labels = temporary.newFolder("cancel-labels");
        File raws = temporary.newFolder("cancel-raw");
        final File output = temporary.newFolder("cancel-batch");
        for (String key : new String[] {"S1", "S2"}) {
            save(new File(labels, key + "_labels.tif"), discs(24, 24, 2));
            save(new File(raws, key + "_raw.tif"), raw(24, 24, 2));
        }
        final OipConfig config = new OipConfig();
        config.doTextureClasses = true;
        config.minimumTextureVoxels = 4;
        OipBatchRunner.run(batch(labels, raws, output, config, null));
        OipConfig other = config.copy();
        other.radialBins = 7;
        AtomicInteger counted = new AtomicInteger();
        OipBatchRunner.run(batch(labels, raws, temporary.newFolder(), other, counting(counted)));
        int total = counted.get();
        Map<String, String> before = snapshot(output);
        int cancelled = 0;
        for (int n = 0; n <= total; n += Math.max(1, total / 20)) {
            try {
                OipBatchRunner.run(batch(labels, raws, output, other, flipAfter(n)));
                before = snapshot(output);
            } catch (ObjectIntensityProfiling.AnalysisCancelledException expected) {
                cancelled++;
                assertEquals("cancelled after " + n + " checks", before, snapshot(output));
            }
            assertNoTransientDirectories(output);
        }
        assertTrue(cancelled > 5);
    }

    // ------------------------------------------------------------------ virtual stacks

    @Test
    public void virtualStacksGiveTheSameResultsAsInMemoryStacks() throws Exception {
        File labelFile = new File(temporary.getRoot(), "virtual-labels.tif");
        File rawFile = new File(temporary.getRoot(), "virtual-raw.tif");
        ImagePlus labels = discs(48, 48, 6);
        ImagePlus raw = raw(48, 48, 6);
        assertTrue(new FileSaver(labels).saveAsTiffStack(labelFile.getPath()));
        assertTrue(new FileSaver(raw).saveAsTiffStack(rawFile.getPath()));
        OipConfig config = new OipConfig();
        config.doGlcm = true;
        config.doTextureClasses = true;
        config.minimumTextureVoxels = 4;
        List<String> expected = describe(ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("Signal", raw).config(config)
                        .saveFigures(false).build()));
        String previous = System.getProperty("oip.parallelism");
        try {
            System.setProperty("oip.parallelism", "4");
            for (int repeat = 0; repeat < 3; repeat++) {
                ImagePlus virtualLabels = IJ.openVirtual(labelFile.getPath());
                ImagePlus virtualRaw = IJ.openVirtual(rawFile.getPath());
                assertTrue(virtualLabels.getStack().isVirtual());
                List<String> actual = describe(ObjectIntensityProfiling.run(
                        OipParameters.builder(virtualLabels).sourceName("labels")
                                .addRawImage("Signal", virtualRaw).config(config)
                                .saveFigures(false).build()));
                assertEquals(expected, actual);
                virtualLabels.close();
                virtualRaw.close();
            }
        } finally {
            if (previous == null) System.clearProperty("oip.parallelism");
            else System.setProperty("oip.parallelism", previous);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void assertRejected(OipParameters.Builder builder, String fragment) {
        try {
            ObjectIntensityProfiling.run(builder.saveFigures(false).build());
            fail("expected rejection containing: " + fragment);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(fragment));
        }
    }

    private void assertBatchRejected(File labels, File raws, String fragment) throws IOException {
        try {
            OipBatchRunner.preview(OipBatchParameters.builder(labels, "(.*)_labels\\.tif",
                            temporary.newFolder())
                    .addRawChannel("Signal", raws, "(.*)_raw\\.tif").build());
            fail("expected rejection containing: " + fragment);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(fragment));
        }
    }

    private static OipBatchParameters batch(File labels, File raws, File output,
                                            OipConfig config,
                                            OipParameters.CancellationToken token) {
        return OipBatchParameters.builder(labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raws, "(.*)_raw\\.tif")
                .config(config).saveFigures(true).saveClassMaps(true)
                .cancellationToken(token).build();
    }

    private static OipParameters.CancellationToken flipAfter(final int n) {
        final AtomicInteger calls = new AtomicInteger();
        return new OipParameters.CancellationToken() {
            @Override
            public boolean isCancelled() {
                return calls.incrementAndGet() > n;
            }
        };
    }

    /** Never cancels; counts how many cancellation checks a complete run makes. */
    private static OipParameters.CancellationToken counting(final AtomicInteger calls) {
        return new OipParameters.CancellationToken() {
            @Override
            public boolean isCancelled() {
                calls.incrementAndGet();
                return false;
            }
        };
    }

    private static OipParameters.ProgressListener recorder(final List<Double> into) {
        return new OipParameters.ProgressListener() {
            @Override
            public synchronized void onProgress(double fraction, String message) {
                into.add(fraction);
            }
        };
    }

    private static void assertMonotonic(List<Double> fractions) {
        assertTrue(fractions.size() > 5);
        double last = -1;
        for (int i = 0; i < fractions.size(); i++) {
            double value = fractions.get(i);
            assertTrue("fraction " + value + " at " + i + " after " + last, value >= last);
            assertTrue(value >= 0.0 && value <= 1.0);
            last = value;
        }
        assertEquals(1.0, last, 0.0);
    }

    private static void assertNoTransientDirectories(File output) throws IOException {
        try (Stream<Path> paths = Files.walk(output.toPath())) {
            paths.forEach(path -> {
                String name = path.getFileName().toString();
                assertFalse("left behind: " + path,
                        name.startsWith(".OIP_staging_") || name.startsWith(".OIP_backup_"));
            });
        }
    }

    private static Map<String, String> snapshot(File root) throws Exception {
        final Map<String, String> out = new TreeMap<String, String>();
        final Path base = root.toPath();
        try (Stream<Path> paths = Files.walk(base)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!Files.isRegularFile(path)) continue;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(Files.readAllBytes(path));
                StringBuilder hex = new StringBuilder();
                for (byte b : hash) hex.append(String.format("%02x", b));
                out.put(base.relativize(path).toString().replace('\\', '/'), hex.toString());
            }
        }
        return out;
    }

    private static void assertEveryNumberIsModest(File output) throws IOException {
        try (Stream<Path> paths = Files.walk(output.toPath())) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!path.toString().endsWith(".csv")) continue;
                List<String> rows = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (String row : rows.subList(1, rows.size())) {
                    for (String cell : row.split(",", -1)) {
                        double value;
                        try {
                            value = Double.parseDouble(cell);
                        } catch (NumberFormatException notNumeric) {
                            continue;
                        }
                        assertTrue(path + ": " + row, Double.isNaN(value)
                                || (Double.isFinite(value) && Math.abs(value) < 1.0e6));
                    }
                }
            }
        }
    }

    private static List<String> describe(OipResult result) {
        List<String> out = new ArrayList<String>();
        for (ObjectProfileResult object : result.getProfiles()) {
            for (ObjectProfileResult.PartnerProfiles partner : object.byPartner.values()) {
                out.add(object.label + " " + Arrays.toString(partner.radialRaw)
                        + Arrays.toString(partner.shellRaw) + partner.withinBoxPearson);
            }
        }
        for (ObjectTextureResult texture : result.getTextures()) {
            out.add(texture.label + " " + texture.contrast + " " + texture.classLabel + " "
                    + texture.classDistance);
        }
        return out;
    }

    private static int finiteCount(double[] values) {
        int count = 0;
        for (double value : values) if (Double.isFinite(value)) count++;
        return count;
    }

    private static int firstFinite(double[] values) {
        for (int i = 0; i < values.length; i++) if (Double.isFinite(values[i])) return i;
        return -1;
    }

    private static Calibration calibration(double x, double y, double z, String unit) {
        Calibration calibration = new Calibration();
        calibration.pixelWidth = x;
        calibration.pixelHeight = y;
        calibration.pixelDepth = z;
        calibration.setUnit(unit);
        return calibration;
    }

    /** Four discs (balls in 3D) labelled 1..4 on a 2 x 2 grid. */
    private static ImagePlus discs(final int width, final int height, final int depth) {
        final double radius = Math.min(width, height) / 5.0;
        return SyntheticImages.image("labels", width, height, depth, new SyntheticImages.Pixel() {
            @Override
            public float value(int x, int y, int z) {
                for (int i = 0; i < 4; i++) {
                    double cx = width * (i % 2 == 0 ? 0.28 : 0.72);
                    double cy = height * (i / 2 == 0 ? 0.28 : 0.72);
                    double dx = x - cx;
                    double dy = y - cy;
                    if (dx * dx + dy * dy <= radius * radius) return i + 1;
                }
                return 0;
            }
        });
    }

    private static ImagePlus raw(int width, int height, int depth) {
        return SyntheticImages.image("raw", width, height, depth, new SyntheticImages.Pixel() {
            @Override
            public float value(int x, int y, int z) {
                return 10 + ((x * 7 + y * 13 + z * 5) % 17) + 0.5f * x;
            }
        });
    }

    private static ImagePlus constant(int width, int height, int depth, final float value) {
        return SyntheticImages.image("constant", width, height, depth,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return value;
                    }
                });
    }

    private static void save(File file, ImagePlus image) {
        assertTrue(image.getStackSize() > 1
                ? new FileSaver(image).saveAsTiffStack(file.getAbsolutePath())
                : new FileSaver(image).saveAsTiff(file.getAbsolutePath()));
    }
}
