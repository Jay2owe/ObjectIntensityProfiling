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
package oip.texture;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import oip.profile.LabelObjects;
import oip.profile.LabelObjects.ObjectInfo;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZernikeMomentsTest {

    @Test
    public void momentCountMatchesTheDegree() {
        assertEquals(30, ZernikeMoments.momentCount(9));
        assertEquals(ZernikeMoments.momentCount(9),
                ZernikeMoments.compute(disk(12, 1f), 9, null).size());
    }

    @Test
    public void radialPolynomialsMatchKnownForms() {
        double rho = 0.37;
        assertEquals(1.0, ZernikeMoments.radial(0, 0, rho), 1e-15);
        assertEquals(2 * rho * rho - 1, ZernikeMoments.radial(2, 0, rho), 1e-15);
        assertEquals(3 * Math.pow(rho, 3) - 2 * rho, ZernikeMoments.radial(3, 1, rho), 1e-15);
        assertEquals(6 * Math.pow(rho, 4) - 6 * rho * rho + 1,
                ZernikeMoments.radial(4, 0, rho), 1e-14);
        assertEquals(1.0, ZernikeMoments.radial(7, 3, 1.0), 1e-12);
    }

    @Test
    public void uniformDiskHasUnitZeroMomentAndNoAngularStructure() {
        ZernikeMoments.Result result = ZernikeMoments.compute(disk(30, 5f), 9, null);
        ZernikeMoments.Result finer = ZernikeMoments.compute(disk(60, 5f), 9, null);
        assertTrue(result.valid);
        assertTrue(result.reliable);
        for (int i = 0; i < result.size(); i++) {
            String label = "n=" + result.n[i] + " m=" + result.m[i] + " " + result.magnitude[i];
            if (result.n[i] == 0) {
                assertEquals(1.0, result.magnitude[i], 1e-12);
            } else if (result.m[i] % 4 != 0) {
                // A digital disk keeps the square grid's four-fold symmetry, so every repetition
                // that is not a multiple of four cancels exactly.
                assertTrue(label, result.magnitude[i] < 1e-12);
            } else if (result.m[i] > 0) {
                // m = 4, 8 pick up the grid's own four-fold staircase edge: small, and smaller
                // again once the disk is large relative to the pixels.
                assertTrue(label, result.magnitude[i] < 1e-2);
                assertTrue(label, finer.magnitude[i] < 3e-3);
            }
        }
    }

    @Test
    public void exactQuarterTurnLeavesMagnitudesUnchanged() {
        ObjectPatch patch = asymmetric();
        ObjectPatch rotated = rotate90(patch);
        ZernikeMoments.Result a = ZernikeMoments.compute(patch, 9, null);
        ZernikeMoments.Result b = ZernikeMoments.compute(rotated, 9, null);
        boolean anyStructure = false;
        for (int i = 0; i < a.size(); i++) {
            assertEquals("n=" + a.n[i] + " m=" + a.m[i], a.magnitude[i], b.magnitude[i], 1e-9);
            if (a.m[i] > 0 && a.magnitude[i] > 1e-3) anyStructure = true;
        }
        assertTrue("the test object must have angular structure", anyStructure);
    }

    @Test
    public void scalingIntensityLeavesMagnitudesUnchanged() {
        ObjectPatch patch = asymmetric();
        float[] scaled = patch.intensity.clone();
        for (int i = 0; i < scaled.length; i++) scaled[i] *= 10f;
        ZernikeMoments.Result a = ZernikeMoments.compute(patch, 9, null);
        ZernikeMoments.Result b = ZernikeMoments.compute(
                new ObjectPatch(scaled, patch.mask, patch.width, patch.height, 1.0), 9, null);
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.magnitude[i], b.magnitude[i], 1e-12);
            // Phase is only defined for a non-vanishing moment, and +pi and -pi are the same angle.
            if (a.magnitude[i] > 1e-9) {
                double difference = Math.IEEEremainder(a.phase[i] - b.phase[i], 2 * Math.PI);
                assertEquals(0.0, difference, 1e-9);
            }
        }
    }

    @Test
    public void threeDimensionalObjectsUseTheMaximumIntensityProjection() {
        int size = 24;
        ImageStack labels = new ImageStack(size, size);
        ImageStack raw = new ImageStack(size, size);
        for (int z = 0; z < 3; z++) {
            FloatProcessor l = new FloatProcessor(size, size);
            FloatProcessor r = new FloatProcessor(size, size);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double dx = x - 11.5;
                    double dy = y - 11.5;
                    if (dx * dx + dy * dy <= 81) {
                        l.setf(x, y, 1);
                        // Each slice is bright on a different side; the MIP combines them.
                        r.setf(x, y, (z == 0 && x < 12) || (z == 1 && y < 12) ? 50f : 10f);
                    }
                }
            }
            labels.addSlice(l);
            raw.addSlice(r);
        }
        ImagePlus labelImage = new ImagePlus("labels", labels);
        ImagePlus rawImage = new ImagePlus("raw", raw);
        List<ObjectInfo> objects = LabelObjects.extract(labelImage);
        ObjectPatch mip = ObjectPatchBuilder.buildMIP(objects.get(0), labelImage, rawImage, 0.0);
        ZernikeMoments.Result fromStack = ZernikeMoments.compute(mip, 6, null);

        ObjectPatch expected = ObjectPatchBuilder.buildMIP(objects.get(0),
                new ImagePlus("l", labels.getProcessor(1)), maxProjection(raw), 0.0);
        ZernikeMoments.Result fromProjection = ZernikeMoments.compute(expected, 6, null);
        for (int i = 0; i < fromStack.size(); i++) {
            assertEquals(fromProjection.magnitude[i], fromStack.magnitude[i], 1e-12);
        }
    }

    @Test
    public void nonPositiveTotalIntensityIsInvalidAndBlank() {
        ZernikeMoments.Result zero = ZernikeMoments.compute(disk(10, 0f), 4, null);
        assertFalse(zero.valid);
        for (double value : zero.magnitude) assertTrue(Double.isNaN(value));
        ZernikeMoments.Result negative = ZernikeMoments.compute(disk(10, -2f), 4, null);
        assertFalse(negative.valid);
    }

    @Test
    public void smallObjectsAreFlaggedUnreliable() {
        ZernikeMoments.Result small = ZernikeMoments.compute(disk(3, 1f), 4, null);
        assertTrue(small.valid);
        assertFalse(small.reliable);
    }

    @Test(expected = IllegalArgumentException.class)
    public void degreeAboveTheLimitIsRejected() {
        ZernikeMoments.compute(disk(10, 1f), 21, null);
    }

    private static ObjectPatch disk(int radius, float value) {
        int size = 2 * radius + 3;
        float[] intensity = new float[size * size];
        byte[] mask = new byte[intensity.length];
        int c = size / 2;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int dx = x - c;
                int dy = y - c;
                if (dx * dx + dy * dy <= radius * radius) {
                    intensity[y * size + x] = value;
                    mask[y * size + x] = 1;
                } else {
                    intensity[y * size + x] = Float.NaN;
                }
            }
        }
        return new ObjectPatch(intensity, mask, size, size, 1.0);
    }

    /** Off-centre ellipse with an intensity gradient: no rotational symmetry. */
    private static ObjectPatch asymmetric() {
        int width = 31;
        int height = 23;
        float[] intensity = new float[width * height];
        byte[] mask = new byte[intensity.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double dx = (x - 14.0) / 13.0;
                double dy = (y - 10.0) / 9.0;
                int i = y * width + x;
                if (dx * dx + dy * dy <= 1.0) {
                    intensity[i] = (float) (5 + 3 * x + 0.5 * y * y);
                    mask[i] = 1;
                } else {
                    intensity[i] = Float.NaN;
                }
            }
        }
        return new ObjectPatch(intensity, mask, width, height, 1.0);
    }

    private static ObjectPatch rotate90(ObjectPatch patch) {
        int width = patch.height;
        int height = patch.width;
        float[] intensity = new float[width * height];
        byte[] mask = new byte[intensity.length];
        for (int y = 0; y < patch.height; y++) {
            for (int x = 0; x < patch.width; x++) {
                int nx = patch.height - 1 - y;
                int ny = x;
                intensity[ny * width + nx] = patch.intensity[y * patch.width + x];
                mask[ny * width + nx] = patch.mask[y * patch.width + x];
            }
        }
        return new ObjectPatch(intensity, mask, width, height, 1.0);
    }

    private static ImagePlus maxProjection(ImageStack stack) {
        FloatProcessor out = new FloatProcessor(stack.getWidth(), stack.getHeight());
        float[] target = (float[]) out.getPixels();
        java.util.Arrays.fill(target, Float.NEGATIVE_INFINITY);
        for (int z = 1; z <= stack.getSize(); z++) {
            float[] pixels = (float[]) stack.getPixels(z);
            for (int i = 0; i < pixels.length; i++) target[i] = Math.max(target[i], pixels[i]);
        }
        return new ImagePlus("mip", out);
    }
}
