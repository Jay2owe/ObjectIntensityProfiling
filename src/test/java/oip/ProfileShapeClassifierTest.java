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
import oip.profile.ProfileShapeClassifier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProfileShapeClassifierTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static final int SIZE = 80;
    private static final int DEPTH = 17;
    private static final double RADIUS = 8.0;
    private static final int[] CENTRES = {15, 40, 65};

    @Test
    public void separatesCentreBrightFromRimBrightSpheres() {
        OipResult result = ObjectIntensityProfiling.run(parameters(
                spheres(), raw(0, 1.0), config(2, ProfileShapeClassifier.Family.RADIAL)));
        List<ProfileShapeClassifier.Assignment> assignments = result.getProfileClasses();
        assertEquals(9, assignments.size());
        int centreClass = -1;
        int rimClass = -1;
        for (ProfileShapeClassifier.Assignment assignment : assignments) {
            assertTrue(assignment.classLabel >= 0);
            if (centreBright(assignment.curve.label, 0)) {
                if (centreClass < 0) centreClass = assignment.classLabel;
                assertEquals("centre-bright label " + assignment.curve.label,
                        centreClass, assignment.classLabel);
            } else {
                if (rimClass < 0) rimClass = assignment.classLabel;
                assertEquals("rim-bright label " + assignment.curve.label,
                        rimClass, assignment.classLabel);
            }
        }
        assertNotEquals(centreClass, rimClass);
    }

    @Test
    public void classesAreDeterministicAndIndependentOfParallelism() {
        String previous = System.getProperty("oip.parallelism");
        try {
            System.setProperty("oip.parallelism", "1");
            List<String> serial = describe(ObjectIntensityProfiling.run(parameters(
                    spheres(), raw(0, 1.0), config(3, ProfileShapeClassifier.Family.SHELL))));
            System.setProperty("oip.parallelism", "4");
            List<String> parallel = describe(ObjectIntensityProfiling.run(parameters(
                    spheres(), raw(0, 1.0), config(3, ProfileShapeClassifier.Family.SHELL))));
            List<String> again = describe(ObjectIntensityProfiling.run(parameters(
                    spheres(), raw(0, 1.0), config(3, ProfileShapeClassifier.Family.SHELL))));
            assertEquals(serial, parallel);
            assertEquals(parallel, again);
        } finally {
            if (previous == null) System.clearProperty("oip.parallelism");
            else System.setProperty("oip.parallelism", previous);
        }
    }

    @Test
    public void requestedClassesAreClampedToUsableCurvesAndBadCurvesAreExcluded() {
        List<ProfileShapeClassifier.Curve> curves = new ArrayList<ProfileShapeClassifier.Curve>();
        curves.add(new ProfileShapeClassifier.Curve("A", 1, 10, "P", new double[] {1, 0}));
        curves.add(new ProfileShapeClassifier.Curve("A", 2, 10, "P", new double[] {0, 1}));
        curves.add(new ProfileShapeClassifier.Curve("A", 3, 10, "P", new double[] {1, Double.NaN}));
        curves.add(new ProfileShapeClassifier.Curve("A", 4, 10, "P", null));
        curves.add(new ProfileShapeClassifier.Curve("A", 1, 10, "Q", new double[] {2, 2, 2}));
        List<ProfileShapeClassifier.Assignment> assignments =
                ProfileShapeClassifier.classify(curves, 10, null);
        assertEquals(5, assignments.size());
        assertEquals(1, assignments.get(0).classLabel);
        assertEquals(0, assignments.get(1).classLabel);
        assertEquals(0.0, assignments.get(0).distance, 0.0);
        assertEquals(-1, assignments.get(2).classLabel);
        assertTrue(Double.isNaN(assignments.get(2).distance));
        assertEquals(-1, assignments.get(3).classLabel);
        assertEquals("each partner is fitted on its own", 0, assignments.get(4).classLabel);
        for (int i = 0; i < curves.size(); i++) {
            assertTrue("result keeps input order", curves.get(i) == assignments.get(i).curve);
        }
    }

    @Test
    public void curveOfADisabledProfileFamilyIsRejected() {
        OipConfig config = config(2, ProfileShapeClassifier.Family.ANGULAR);
        config.doAngular = false;
        try {
            ObjectIntensityProfiling.run(parameters(spheres(), raw(0, 1.0), config));
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("profile_class_type=angular needs the angular profile; remove "
                    + "no_angular or choose another profile_class_type.", expected.getMessage());
        }
    }

    /**
     * GUI check (0.3.0): the interactive plots left out the class-mean figures that auto-save
     * writes, and an Escape pressed after the run had saved its files stopped the display half
     * way (tables shown, plots missing, status "cancelled").
     */
    @Test
    public void interactiveFiguresMatchTheSavedFiguresAndIgnoreALateEscape() throws Exception {
        File output = temporary.newFolder("shown");
        final boolean[] escape = {false};
        OipResult result = ObjectIntensityProfiling.run(OipParameters.builder(spheres())
                .addRawImage("Signal", raw(0, 1.0))
                .config(config(2, ProfileShapeClassifier.Family.RADIAL))
                .saveFigures(true).autoSave(output)
                .cancellationToken(new OipParameters.CancellationToken() {
                    @Override
                    public boolean isCancelled() {
                        return escape[0];
                    }
                }).build());
        escape[0] = true; // Escape pressed after the results were saved

        List<ImagePlus> shown = Object_Intensity_Profiling.aggregateFigures(result);
        List<String> saved = new ArrayList<String>();
        for (String name : new File(output, "Figures").list()) {
            if (!name.startsWith(".")) saved.add(name);
        }
        try {
            assertEquals("every saved figure is shown: " + saved, saved.size(), shown.size());
        } finally {
            for (ImagePlus figure : shown) {
                figure.close();
                figure.flush();
            }
        }
    }

    @Test
    public void singleImageTablesAreWrittenOnlyWhenEnabledAndRemovedOnADisabledRerun()
            throws Exception {
        File output = temporary.newFolder("single");
        File classes = new File(output, "Profiles/Profile_Classes.csv");
        File curves = new File(output, "Aggregate/Profile_Class_Curves.csv");
        ObjectIntensityProfiling.run(OipParameters.builder(spheres())
                .addRawImage("Signal", raw(0, 1.0)).config(config(2,
                        ProfileShapeClassifier.Family.RADIAL))
                .saveFigures(true).autoSave(output).build());
        List<String> rows = Files.readAllLines(classes.toPath(), StandardCharsets.UTF_8);
        assertEquals("Source,Label,VoxelCount,Partner,ProfileType,ProfileClass,ClassDistance",
                rows.get(0));
        assertEquals(10, rows.size());
        List<String> curveRows = Files.readAllLines(curves.toPath(), StandardCharsets.UTF_8);
        assertEquals("Partner,ProfileType,ProfileClass,Bin,AxisNorm,Mean,SEM,N", curveRows.get(0));
        assertTrue(curveRows.get(1).startsWith("Signal,Radial,Class 1,0,"));
        assertTrue(new File(output, "Figures").list().length > 0);
        boolean classFigure = false;
        for (String name : new File(output, "Figures").list()) {
            classFigure |= name.contains("Profile") && name.contains("classes");
        }
        assertTrue("one class-mean figure per partner", classFigure);

        ObjectIntensityProfiling.run(OipParameters.builder(spheres())
                .addRawImage("Signal", raw(0, 1.0)).saveFigures(false).autoSave(output).build());
        assertFalse("stale class table removed", classes.exists());
        assertFalse("stale class curves removed", curves.exists());
    }

    @Test
    public void batchFitsOnceAcrossSamplesAndMatchesAConcatenatedFit() throws Exception {
        File labels = temporary.newFolder("labels");
        File raw = temporary.newFolder("raw");
        File output = temporary.newFolder("batch-out");
        save(new File(labels, "S1_labels.tif"), spheres());
        save(new File(labels, "S2_labels.tif"), spheres());
        save(new File(raw, "S1_raw.tif"), raw(0, 1.0));
        save(new File(raw, "S2_raw.tif"), raw(1, 2.5));
        OipConfig config = config(2, ProfileShapeClassifier.Family.RADIAL);
        OipBatchRunner.run(OipBatchParameters.builder(labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .referenceChannel("Signal")
                .config(config)
                .saveFigures(false)
                .saveClassMaps(false)
                .build());

        List<ProfileShapeClassifier.Curve> all = new ArrayList<ProfileShapeClassifier.Curve>();
        int[][] variants = {{0}, {1}};
        double[] gains = {1.0, 2.5};
        for (int i = 0; i < 2; i++) {
            OipResult sample = ObjectIntensityProfiling.runWithDeferredTextureClasses(
                    OipParameters.builder(spheres()).sourceName("Labels")
                            .addRawImage("Signal", raw(variants[i][0], gains[i]))
                            .config(config).build());
            all.addAll(ProfileShapeClassifier.curves(sample.getProfiles(), config.profileClassFamily));
        }
        List<ProfileShapeClassifier.Assignment> expected =
                ProfileShapeClassifier.classify(all, 2, null);
        List<String> expectedClasses = new ArrayList<String>();
        for (ProfileShapeClassifier.Assignment assignment : expected) {
            expectedClasses.add(assignment.curve.label + ":" + (assignment.classLabel + 1));
        }
        List<String> actual = new ArrayList<String>();
        for (String sample : Arrays.asList("S1", "S2")) {
            List<String> rows = Files.readAllLines(new File(output,
                    "Samples/" + sample + "/Profiles/Profile_Classes.csv").toPath(),
                    StandardCharsets.UTF_8);
            for (String row : rows.subList(1, rows.size())) {
                String[] cells = row.split(",");
                actual.add(cells[1] + ":" + cells[5]);
            }
        }
        assertEquals(expectedClasses, actual);
        // Sample 2 swaps which objects are centre-bright; a global fit keeps shapes, not
        // positions, together, so the same label lands in different classes across samples.
        assertNotEquals(actual.subList(0, 9), actual.subList(9, 18));
        List<String> root = Files.readAllLines(
                new File(output, "Aggregate/Profile_Class_Curves.csv").toPath(),
                StandardCharsets.UTF_8);
        assertEquals("Partner,ProfileType,ProfileClass,Bin,AxisNorm,Mean,SEM,N", root.get(0));
        int total = 0;
        for (String row : root.subList(1, root.size())) {
            String[] cells = row.split(",");
            assertEquals("Signal", cells[0]);
            assertEquals("Radial", cells[1]);
            if (cells[3].equals("0")) total += Integer.parseInt(cells[7]);
        }
        assertEquals("every object of both samples is in exactly one class", 18, total);

        OipBatchRunner.run(OipBatchParameters.builder(labels, "(.*)_labels\\.tif", output)
                .addRawChannel("Signal", raw, "(.*)_raw\\.tif")
                .referenceChannel("Signal")
                .saveFigures(false)
                .saveClassMaps(false)
                .build());
        assertFalse(new File(output, "Aggregate/Profile_Class_Curves.csv").exists());
        assertFalse(new File(output, "Samples/S1/Profiles/Profile_Classes.csv").exists());
        assertTrue(new File(output, "Aggregate/Aggregate_Profiles.csv").isFile());
    }

    @Test
    public void macroRunsProfileClassesInSingleImageAndBatchMode() throws Exception {
        File labels = temporary.newFolder("macro-labels");
        File raw = temporary.newFolder("macro-raw");
        save(new File(labels, "S1_labels.tif"), spheres());
        save(new File(raw, "S1_raw.tif"), raw(0, 1.0));
        String classes = " profile_classes profile_k=2 profile_class_type=shell";

        File single = temporary.newFolder("macro-single");
        new Object_Intensity_Profiling().execute("labels_path=["
                + new File(labels, "S1_labels.tif").getAbsolutePath() + "] raw1_path=["
                + new File(raw, "S1_raw.tif").getAbsolutePath() + "]" + classes
                + " no_figures auto_save output=[" + single.getAbsolutePath() + "]", true);
        assertShellClasses(new File(single, "Profiles/Profile_Classes.csv"));

        File batch = temporary.newFolder("macro-batch");
        new Object_Intensity_Profiling().execute("batch labels_folder=["
                + labels.getAbsolutePath() + "] labels_regex=[(.*)_labels\\.tif] "
                + "raw1_name=[Signal] raw1_folder=[" + raw.getAbsolutePath() + "] "
                + "raw1_regex=[(.*)_raw\\.tif] output=[" + batch.getAbsolutePath() + "]"
                + classes + " no_figures no_maps", true);
        assertShellClasses(new File(batch, "Samples/S1/Profiles/Profile_Classes.csv"));
        assertTrue(new File(batch, "Aggregate/Profile_Class_Curves.csv").isFile());
    }

    private static void assertShellClasses(File table) throws Exception {
        List<String> rows = Files.readAllLines(table.toPath(), StandardCharsets.UTF_8);
        assertEquals(10, rows.size());
        java.util.Set<String> seen = new java.util.TreeSet<String>();
        for (String row : rows.subList(1, rows.size())) {
            String[] cells = row.split(",");
            assertEquals("ShellIntensity", cells[4]);
            seen.add(cells[5]);
        }
        assertEquals(Arrays.asList("1", "2"), new ArrayList<String>(seen));
    }

    // ------------------------------------------------------------------ fixtures

    private static OipConfig config(int k, ProfileShapeClassifier.Family family) {
        OipConfig config = new OipConfig();
        config.doProfileClasses = true;
        config.profileClasses = k;
        config.profileClassFamily = family;
        // Few enough bins that every sphere fills each one; a curve with an empty bin is excluded.
        config.radialBins = 8;
        return config;
    }

    private static OipParameters parameters(ImagePlus labels, ImagePlus raw, OipConfig config) {
        return OipParameters.builder(labels).addRawImage("Signal", raw).config(config)
                .saveFigures(false).build();
    }

    private static List<String> describe(OipResult result) {
        List<String> out = new ArrayList<String>();
        for (ProfileShapeClassifier.Assignment assignment : result.getProfileClasses()) {
            out.add(assignment.curve.label + "/" + assignment.curve.partner + "="
                    + assignment.classLabel + "@" + Double.doubleToLongBits(assignment.distance));
        }
        return out;
    }

    /** Nine labelled spheres on a 3 x 3 grid, labels 1..9 in row order. */
    private static ImagePlus spheres() {
        return SyntheticImages.image("labels", SIZE, SIZE, DEPTH, new SyntheticImages.Pixel() {
            @Override
            public float value(int x, int y, int z) {
                int label = owner(x, y, z);
                return label < 0 ? 0 : label;
            }
        });
    }

    /** Label whose sphere covers the voxel, or -1. */
    private static int owner(int x, int y, int z) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                if (relative(x, y, z, col, row) <= 1.0) return 1 + row * 3 + col;
            }
        }
        return -1;
    }

    private static double relative(int x, int y, int z, int col, int row) {
        double dx = x - CENTRES[col];
        double dy = y - CENTRES[row];
        double dz = z - DEPTH / 2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) / RADIUS;
    }

    /** Odd labels are centre-bright in variant 0; variant 1 swaps them. */
    private static boolean centreBright(int label, int variant) {
        return (label % 2 == 1) == (variant == 0);
    }

    private static ImagePlus raw(final int variant, final double gain) {
        return SyntheticImages.image("raw", SIZE, SIZE, DEPTH, new SyntheticImages.Pixel() {
            @Override
            public float value(int x, int y, int z) {
                int label = owner(x, y, z);
                if (label < 0) return 2f;
                int col = (label - 1) % 3;
                int row = (label - 1) / 3;
                double r = Math.min(1.0, relative(x, y, z, col, row));
                double shape = centreBright(label, variant) ? 1.0 - 0.8 * r : 0.2 + 0.8 * r;
                return (float) (10.0 + 100.0 * gain * shape + (label % 3));
            }
        });
    }

    private static void save(File file, ImagePlus image) {
        assertTrue(new FileSaver(image).saveAsTiff(file.getAbsolutePath()));
        image.close();
    }
}
