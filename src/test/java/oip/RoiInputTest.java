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
import ij.gui.Line;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.io.RoiEncoder;
import oip.profile.ObjectProfileResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RoiInputTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void roiSetOfTwoRectanglesGivesTwoObjects() throws Exception {
        File set = roiSet("cells.zip", new Roi(2, 2, 5, 4), new Roi(20, 10, 6, 6));
        ImagePlus labels = OipRoiInputs.labelsFromRoiSet(raw(40, 30, 1), set.getPath());
        assertEquals("cells", labels.getTitle());
        OipResult result = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("Signal", raw(40, 30, 1)).build());
        assertEquals(Arrays.asList("1:20", "2:36"), counts(result));
        assertEquals("cells", result.getProfiles().get(0).sourceChannel);
    }

    @Test
    public void laterRoiWinsWhereRoisOverlapAndAFullyCoveredRoiDisappears() {
        OipResult overlap = run(OipRoiInputs.labelsFromRois(raw(40, 30, 1),
                new Roi[] {new Roi(0, 0, 10, 10), new Roi(5, 5, 10, 10)}));
        assertEquals(Arrays.asList("1:75", "2:100"), counts(overlap));

        OipResult covered = run(OipRoiInputs.labelsFromRois(raw(40, 30, 1),
                new Roi[] {new Roi(4, 4, 2, 2), new Roi(0, 0, 10, 10), new Roi(20, 5, 4, 4)}));
        assertEquals(Arrays.asList("2:100", "3:16"), counts(covered));
    }

    @Test
    public void positionedRoiIsDrawnOnItsSliceOnlyAndUnpositionedOnEverySlice() {
        Roi positioned = new Roi(2, 2, 4, 4);
        positioned.setPosition(0, 3, 0);
        OipResult result = run(OipRoiInputs.labelsFromRois(raw(30, 30, 5),
                new Roi[] {positioned, new Roi(15, 15, 3, 3)}));
        assertEquals(Arrays.asList("1:16", "2:45"), counts(result));
    }

    @Test
    public void regionKeepsOnlyObjectsWhoseCentroidIsInside() {
        ImagePlus labels = OipRoiInputs.labelsFromRois(raw(60, 30, 1), new Roi[] {
            new Roi(2, 2, 6, 6), new Roi(22, 2, 6, 6), new Roi(42, 2, 6, 6)});
        // Covers object 1 entirely and object 2 up to (but not including) its centroid pixel.
        Roi[] region = {new Roi(0, 0, 25, 20), new OvalRoi(40, 0, 10, 10)};
        OipResult result = ObjectIntensityProfiling.run(OipParameters.builder(labels)
                .addRawImage("Signal", raw(60, 30, 1)).regionRois(region).build());
        assertEquals(Arrays.asList("1:36", "3:36"), counts(result));
    }

    @Test
    public void positionedRegionAppliesToItsSliceOnly() {
        ImagePlus labels = OipRoiInputs.labelsFromRois(raw(30, 30, 5), new Roi[] {
            slice(new Roi(2, 2, 4, 4), 1), slice(new Roi(2, 2, 4, 4), 4)});
        Roi region = slice(new Roi(0, 0, 10, 10), 4);
        OipResult result = ObjectIntensityProfiling.run(OipParameters.builder(labels)
                .addRawImage("Signal", raw(30, 30, 5)).regionRois(new Roi[] {region}).build());
        assertEquals(Arrays.asList("2:16"), counts(result));
    }

    @Test
    public void regionWithNoObjectInsideNamesTheRoiFile() throws Exception {
        File region = roiFile("scn.roi", new Roi(30, 20, 5, 5));
        ImagePlus labels = OipRoiInputs.labelsFromRois(raw(40, 30, 1),
                new Roi[] {new Roi(2, 2, 5, 5)});
        try {
            ObjectIntensityProfiling.run(OipParameters.builder(labels)
                    .addRawImage("Signal", raw(40, 30, 1))
                    .regionRois(OipRoiInputs.regionRois(region.getPath()), region.getName())
                    .build());
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("None of the 1 objects has its centroid inside the region ROI set "
                    + "scn.roi.", expected.getMessage());
        }
    }

    @Test
    public void lineRoisAreRejectedAsObjectsAndAsRegions() throws Exception {
        try {
            OipRoiInputs.labelsFromRois(raw(40, 30, 1), new Roi[] {new Line(1, 1, 20, 20)});
            fail("expected rejection of a line object ROI");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line"));
        }
        File region = roiSet("lines.zip", new Roi(0, 0, 5, 5), new Line(1, 1, 20, 20));
        try {
            OipRoiInputs.regionRois(region.getPath());
            fail("expected rejection of a line region ROI");
        } catch (IllegalArgumentException expected) {
            assertEquals("Region ROI 2 (\"0002\") in lines.zip is a line, angle or point selection; "
                    + "region ROIs must enclose an area.", expected.getMessage());
        }
    }

    @Test
    public void regionPositionedBeyondTheStackIsRejected() {
        ImagePlus labels = OipRoiInputs.labelsFromRois(raw(30, 30, 2),
                new Roi[] {new Roi(2, 2, 4, 4)});
        try {
            ObjectIntensityProfiling.run(OipParameters.builder(labels)
                    .addRawImage("Signal", raw(30, 30, 2))
                    .regionRois(new Roi[] {slice(new Roi(0, 0, 9, 9), 5)}).build());
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(
                    "positioned on slice 5 but the label image has only 2 slice(s)"));
        }
    }

    @Test
    public void macroOptionsAcceptRoiInputsAndRejectConflicts() {
        OipMacroOptions options = OipMacroOptionsParser.parse(
                "objects_roi=[C:/data/cells.zip] raw1_path=[C:/data/dapi.tif] "
                        + "region_roi=[C:/data/scn.roi]");
        assertEquals("C:/data/cells.zip", options.objectsRoi);
        assertEquals("C:/data/scn.roi", options.regionRoi);
        String recorded = options.toMacroOptions();
        assertTrue(recorded, recorded.startsWith(
                "objects_roi=[C:/data/cells.zip] region_roi=[C:/data/scn.roi] raw1_path="));
        assertEquals("C:/data/cells.zip",
                OipMacroOptionsParser.parse(recorded).objectsRoi);

        OipMacroOptionsParserTest.assertRejected(
                "labels=[L] objects_roi=[C:/cells.zip] raw1=[R]",
                "Use only one of labels, labels_path and objects_roi to define the objects.");
        OipMacroOptionsParserTest.assertRejected("raw1=[R]",
                "labels, labels_path or objects_roi is required.");
        OipMacroOptionsParserTest.assertRejected("batch labels_folder=[C:/l] "
                        + "labels_regex=[(.*)\\.tif] raw1_name=[R] raw1_folder=[C:/r] "
                        + "raw1_regex=[(.*)\\.tif] output=[C:/o] objects_roi=[C:/cells.zip]",
                "objects_roi is available for single images only in this version");
    }

    @Test
    public void headlessMacroRunsFromRoiFiles() throws Exception {
        File objects = roiSet("cells.zip", new Roi(2, 2, 5, 4), new Roi(20, 10, 6, 6),
                new Roi(30, 20, 4, 4));
        File region = roiFile("left.roi", new Roi(0, 0, 28, 30));
        File raw = temporary.newFile("dapi.tif");
        assertTrue(new FileSaver(raw(40, 30, 1)).saveAsTiff(raw.getAbsolutePath()));
        File output = temporary.newFolder("out");
        new Object_Intensity_Profiling().execute("objects_roi=[" + objects.getAbsolutePath()
                + "] raw1_path=[" + raw.getAbsolutePath() + "] region_roi=["
                + region.getAbsolutePath() + "] no_figures auto_save output=["
                + output.getAbsolutePath() + "]", true);
        List<String> rows = Files.readAllLines(
                new File(output, "Profiles/Object_Summaries.csv").toPath(),
                StandardCharsets.UTF_8);
        assertEquals(3, rows.size());
        assertTrue(rows.get(1), rows.get(1).startsWith("cells,1,20,"));
        assertTrue(rows.get(2), rows.get(2).startsWith("cells,2,36,"));
    }

    // ------------------------------------------------------------------ fixtures

    private static OipResult run(ImagePlus labels) {
        return ObjectIntensityProfiling.run(OipParameters.builder(labels)
                .addRawImage("Signal", raw(labels.getWidth(), labels.getHeight(),
                        labels.getNSlices())).build());
    }

    private static List<String> counts(OipResult result) {
        List<String> out = new ArrayList<String>();
        for (ObjectProfileResult profile : result.getProfiles()) {
            out.add(profile.label + ":" + profile.voxelCount);
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static Roi slice(Roi roi, int slice) {
        roi.setPosition(0, slice, 0);
        return roi;
    }

    private static ImagePlus raw(int width, int height, int depth) {
        return SyntheticImages.image("raw", width, height, depth, new SyntheticImages.Pixel() {
            @Override
            public float value(int x, int y, int z) {
                return 5 + x + 2 * y + 3 * z;
            }
        });
    }

    private File roiSet(String name, Roi... rois) throws IOException {
        File file = new File(temporary.getRoot(), name);
        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file));
        try {
            for (int i = 0; i < rois.length; i++) {
                zip.putNextEntry(new ZipEntry(String.format("%04d.roi", i + 1)));
                zip.write(RoiEncoder.saveAsByteArray(rois[i]));
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
        return file;
    }

    private File roiFile(String name, Roi roi) throws IOException {
        File file = new File(temporary.getRoot(), name);
        Files.write(file.toPath(), RoiEncoder.saveAsByteArray(roi));
        return file;
    }
}
