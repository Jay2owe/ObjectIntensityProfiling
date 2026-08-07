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

import oip.ObjectIntensityProfiling;
import oip.OipParameters;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObjectTextureTest {

    @Test
    public void uniformPatchHasEnergyOneAndNoContrast() {
        ObjectTextureGLCM.Result result = ObjectTextureGLCM.compute(
                patch(32, 32, new Pixel() {
                    @Override
                    public float value(int x, int y) {
                        return 7;
                    }
                }));
        assertTrue(result.valid);
        assertFalse(result.reliable);
        assertEquals(0.0, result.contrast, 1.0e-12);
        assertEquals(1.0, result.asm, 1.0e-12);
        assertEquals(1.0, result.homogeneity, 1.0e-12);
    }

    /**
     * Analytic Haralick values for a 32x32 checkerboard quantised to 32 levels over [0,255], where
     * 0 and 255 land in levels 0 and 31.
     *
     * <p>Features are computed per direction and averaged. The two axial offsets see only
     * (0,31) pairs, so each contributes contrast 31^2 = 961 and homogeneity 1/(1+961); the two
     * diagonal offsets see only like-valued pairs, so each contributes contrast 0 and homogeneity 1.
     * Averaging the four gives contrast 480.5 and homogeneity 0.50052. Every direction puts its mass
     * on exactly two cells, so ASM is 0.5 and entropy is 1 bit; both land a few parts per million
     * off because a 31x31 diagonal window holds an odd 961 pairs and cannot split exactly in half.
     * The axial directions are perfectly anti-correlated and the diagonals perfectly correlated, so
     * the averaged correlation is 0.
     */
    @Test
    public void checkerboardMatchesAnalyticHaralickValues() {
        ObjectTextureGLCM.Result result = ObjectTextureGLCM.compute(
                patch(32, 32, new Pixel() {
                    @Override
                    public float value(int x, int y) {
                        return ((x + y) & 1) == 0 ? 0 : 255;
                    }
                }), 32, 1, 0, 255);
        assertTrue(result.valid);
        assertTrue(result.reliable);
        assertEquals(480.5, result.contrast, 1.0e-9);
        assertEquals(0.5, result.asm, 1.0e-5);
        assertEquals(1.0, result.entropy, 1.0e-5);
        assertEquals(0.5005197505197505, result.homogeneity, 1.0e-9);
        assertEquals(0.0, result.correlation, 1.0e-9);
        assertEquals(3906, result.coOccurrencePairs);
    }

    /**
     * Pooling the four offsets into one matrix leaves contrast and homogeneity nearly right but
     * halves ASM and doubles entropy, because those two are non-linear in the matrix. This pins the
     * per-direction definition against a texture that makes the difference maximal.
     */
    @Test
    public void nonLinearFeaturesAreAveragedPerDirectionNotPooled() {
        ObjectTextureGLCM.Result result = ObjectTextureGLCM.compute(
                patch(32, 32, new Pixel() {
                    @Override
                    public float value(int x, int y) {
                        return ((x + y) & 1) == 0 ? 0 : 255;
                    }
                }), 32, 1, 0, 255);
        assertTrue("pooled ASM would be ~0.25, got " + result.asm, result.asm > 0.45);
        assertTrue("pooled entropy would be ~2 bits, got " + result.entropy,
                result.entropy < 1.5);
    }

    @Test
    public void globalRangeChangesQuantisationComparedWithPatchAutoRange() {
        ObjectPatch patch = patch(32, 32, new Pixel() {
            @Override
            public float value(int x, int y) {
                return ((x + y) & 1) == 0 ? 0 : 10;
            }
        });
        ObjectTextureGLCM.Result local = ObjectTextureGLCM.compute(patch, 32, 1);
        ObjectTextureGLCM.Result global = ObjectTextureGLCM.compute(patch, 32, 1, 0, 100);
        assertTrue(local.contrast > global.contrast * 20);
    }

    @Test
    public void linearGradientHasHighGlcmCorrelation() {
        ObjectTextureGLCM.Result result = ObjectTextureGLCM.compute(
                patch(32, 32, new Pixel() {
                    @Override
                    public float value(int x, int y) {
                        return x;
                    }
                }));
        assertTrue(result.valid);
        assertTrue(result.reliable);
        assertTrue("correlation=" + result.correlation, result.correlation > 0.9);
    }

    @Test
    public void textureFeaturesAndKMeansAreDeterministic() {
        ObjectTextureFeatures.FeatureVector uniform =
                ObjectTextureFeatures.computeFeatures(patch(32, 32, new Pixel() {
                    @Override
                    public float value(int x, int y) {
                        return 5;
                    }
                }));
        for (float feature : uniform.features) assertEquals(0.0, feature, 1.0e-7);

        List<ObjectTextureFeatures.FeatureVector> vectors = Arrays.asList(
                vector(0.0f), vector(0.1f), vector(0.2f),
                vector(10.0f), vector(10.1f), vector(10.2f));
        double[][] first = ObjectTextureFeatures.fitCentroids(vectors, 2);
        double[][] second = ObjectTextureFeatures.fitCentroids(vectors, 2);
        assertEquals(2, first.length);
        for (int i = 0; i < first.length; i++) {
            assertArrayEquals(first[i], second[i], 0.0);
        }
    }

    @Test
    public void textureFeaturesIgnoreAllSignalOutsideMask() {
        int width = 32, height = 32;
        float[] first = new float[width * height];
        float[] second = new float[first.length];
        byte[] mask = new byte[first.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                boolean inside = x >= 8 && x < 24 && y >= 8 && y < 24;
                mask[i] = inside ? (byte) 1 : 0;
                first[i] = inside ? x + y : 0;
                second[i] = inside ? x + y : 10000;
            }
        }
        ObjectTextureFeatures.FeatureVector a = ObjectTextureFeatures.computeFeatures(
                new ObjectPatch(first, mask, width, height, 1));
        ObjectTextureFeatures.FeatureVector b = ObjectTextureFeatures.computeFeatures(
                new ObjectPatch(second, mask, width, height, 1));
        assertArrayEquals(a.features, b.features, 0.0f);
    }

    @Test
    public void nonFinitePixelsAreRemovedFromTheEffectiveFeatureMask() {
        int width = 20, height = 20;
        float[] intensity = new float[width * height];
        byte[] includesMissing = new byte[intensity.length];
        byte[] excludesMissing = new byte[intensity.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                intensity[i] = x < 4 ? Float.NaN : x * x + y;
                includesMissing[i] = 1;
                excludesMissing[i] = x < 4 ? (byte) 0 : (byte) 1;
            }
        }

        ObjectTextureFeatures.FeatureVector included =
                ObjectTextureFeatures.computeFeatures(
                        new ObjectPatch(intensity, includesMissing, width, height, 1));
        ObjectTextureFeatures.FeatureVector excluded =
                ObjectTextureFeatures.computeFeatures(
                        new ObjectPatch(intensity, excludesMissing, width, height, 1));

        assertArrayEquals(excluded.features, included.features, 0.0f);
    }

    @Test
    public void classAssignmentDefensivelySkipsNonFiniteFeatureVectors() {
        ObjectTextureResult result = new ObjectTextureResult(1, 256, "raw", false);
        float[] features = new float[ObjectTextureFeatures.DEFAULT_FEATURE_DIM];
        Arrays.fill(features, 1f);
        features[0] = Float.POSITIVE_INFINITY;
        result.featureVector = new ObjectTextureFeatures.FeatureVector(features, true, true);

        ObjectTextureAnalyzer.assignClasses(
                java.util.Collections.singletonList(result), 1);

        assertEquals(-1, result.classLabel);
    }

    @Test(expected = ObjectIntensityProfiling.AnalysisCancelledException.class)
    public void textureCancellationIsCheckedWithinConvolution() {
        final int[] calls = {0};
        ObjectTextureFeatures.computeFeatures(patch(128, 128, new Pixel() {
            @Override
            public float value(int x, int y) {
                return x + y;
            }
        }), new OipParameters.CancellationToken() {
            @Override
            public boolean isCancelled() {
                return ++calls[0] >= 3;
            }
        });
    }

    @Test(expected = ObjectIntensityProfiling.AnalysisCancelledException.class)
    public void glcmCancellationIsCheckedDuringRangeTraversal() {
        ObjectTextureGLCM.compute(patch(128, 128, new Pixel() {
            @Override
            public float value(int x, int y) {
                return x + y;
            }
        }), 32, 1, 0, 255, new OipParameters.CancellationToken() {
            @Override
            public boolean isCancelled() {
                return true;
            }
        });
    }

    /**
     * "Reliable" must mean a genuine four-direction average. A one-pixel-tall slice yields pairs
     * for the axial offset alone, and was previously exported indistinguishably from a properly
     * averaged object.
     */
    @Test
    public void singleDirectionSlicesAreNotReportedAsReliable() {
        ObjectTextureGLCM.Result thin = ObjectTextureGLCM.compute(
                patch(20, 1, new Pixel() {
                    @Override
                    public float value(int x, int y) {
                        return ((x / 2) & 1) == 0 ? 0 : 200;
                    }
                }), 32, 1, 0, 255);
        assertTrue("a 20x1 slice still produces pairs", thin.valid);
        assertFalse("one direction is not a four-direction average", thin.reliable);

        ObjectTextureGLCM.Result square = ObjectTextureGLCM.compute(
                patch(20, 20, new Pixel() {
                    @Override
                    public float value(int x, int y) {
                        return ((x / 2) & 1) == 0 ? 0 : 200;
                    }
                }), 32, 1, 0, 255);
        assertTrue("an ordinary object must stay reliable", square.reliable);

        // The interesting case sits between those two endpoints. A one-pixel-wide L populates
        // three of the four directions (13 + 13 + 1 pairs; the fourth is empty) and clears the
        // 16-pair floor, so if it were accepted the flag would mean "at least two directions"
        // rather than "all four". The vertical arm is phase-inverted so that the single corner
        // diagonal pair spans two grey levels — otherwise it has zero variance and reliability
        // would fail through the correlation term instead, proving nothing about direction count.
        int w = 16, h = 16;
        float[] intensity = new float[w * h];
        byte[] mask = new byte[intensity.length];
        for (int x = 0; x < 14; x++) {
            intensity[x] = ((x / 2) & 1) == 0 ? 0 : 200;
            mask[x] = 1;
        }
        for (int y = 0; y < 14; y++) {
            intensity[y * w] = ((y / 2) & 1) == 0 ? 200 : 0;
            mask[y * w] = 1;
        }
        ObjectTextureGLCM.Result bent =
                ObjectTextureGLCM.compute(new ObjectPatch(intensity, mask, w, h, 1.0),
                        32, 1, 0, 255);
        assertTrue(bent.valid);
        assertTrue("the L must clear the 16-pair floor, or this proves nothing",
                bent.coOccurrencePairs >= 16);
        assertTrue("and must define a correlation, or reliability fails for another reason",
                Double.isFinite(bent.correlation));
        assertFalse("three of four directions is not a four-direction average", bent.reliable);
    }

    /**
     * The per-direction and per-slice layers must agree: averaging correlation over the directions
     * that define it is pointless if the slice accumulator then discards the object's correlation
     * whenever any direction was undefined. A textured slice plus a constant slice must export a
     * real correlation flagged unreliable, not NaN.
     */
    @Test
    public void aPartialCorrelationIsExportedRatherThanDiscarded() {
        ij.ImageStack labelStack = new ij.ImageStack(12, 12);
        ij.ImageStack rawStack = new ij.ImageStack(12, 12);
        for (int z = 0; z < 2; z++) {
            ij.process.FloatProcessor label = new ij.process.FloatProcessor(12, 12);
            ij.process.FloatProcessor raw = new ij.process.FloatProcessor(12, 12);
            for (int y = 0; y < 12; y++) {
                for (int x = 0; x < 12; x++) {
                    label.setf(x, y, x >= 1 && x < 11 && y >= 1 && y < 11 ? 1 : 0);
                    raw.setf(x, y, z == 0 ? (((x / 2) & 1) == 0 ? 0 : 200) : 50);
                }
            }
            labelStack.addSlice(label);
            rawStack.addSlice(raw);
        }
        ij.ImagePlus labels = new ij.ImagePlus("labels", labelStack);
        java.util.Map<String, ij.ImagePlus> raws =
                new java.util.LinkedHashMap<String, ij.ImagePlus>();
        raws.put("Signal", new ij.ImagePlus("raw", rawStack));
        java.util.Map<String, QuantizationRange> ranges =
                new java.util.LinkedHashMap<String, QuantizationRange>();
        ranges.put("Signal", new QuantizationRange(0, 255));
        oip.profile.OipConfig config = new oip.profile.OipConfig();
        config.doGlcm = true;
        config.minimumTextureVoxels = 1;

        java.util.List<ObjectTextureResult> results = ObjectTextureAnalyzer.analyze(
                labels, raws, oip.profile.LabelObjects.extract(labels), ranges, config);

        assertEquals(1, results.size());
        ObjectTextureResult object = results.get(0);
        assertTrue("correlation must survive the constant slice, not become NaN",
                Double.isFinite(object.correlation));
        assertFalse("but the object must be flagged unreliable", object.glcmReliable);

        // Pin the DIVISOR, not just finiteness. Exactly one slice defines a correlation, so the
        // object's value must equal that slice's. Averaging over the slice count instead would
        // halve it, and would still be finite — the assertion above alone cannot see that.
        ObjectPatch textured = ObjectPatchBuilder.buildSlice(
                oip.profile.LabelObjects.extract(labels).get(0),
                labels, raws.get("Signal"), 0, 0.0);
        ObjectTextureGLCM.Result slice =
                ObjectTextureGLCM.compute(textured, 32, 1, 0, 255);
        assertTrue("the textured slice must define a correlation",
                Double.isFinite(slice.correlation));
        assertTrue("and it must be non-zero, or halving it would be invisible",
                Math.abs(slice.correlation) > 1.0e-6);
        assertEquals("object correlation must equal the one slice that defines it",
                slice.correlation, object.correlation, 1.0e-12);
    }

    /**
     * The Gabor bank must be identical for every object. When its wavelength was derived from each
     * patch's own short side, four objects carrying the same period-4 stripes but differing in size
     * produced a 30-fold spread in the leading orientation response, so k-means clustered by object
     * size instead of by texture.
     */
    @Test
    public void gaborFeaturesDoNotTrackObjectSize() {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int side : new int[] {20, 30, 40, 60}) {
            ObjectTextureFeatures.FeatureVector v =
                    ObjectTextureFeatures.computeFeatures(patch(side, side, new Pixel() {
                        @Override
                        public float value(int x, int y) {
                            return ((x / 2) & 1) == 0 ? 0 : 100;
                        }
                    }));
            assertTrue("side=" + side + " should be reliable", v.reliable);
            min = Math.min(min, v.features[0]);
            max = Math.max(max, v.features[0]);
        }
        assertTrue("leading Gabor response spread " + (max / min) + "x across object sizes",
                max / min < 1.5);
    }

    /**
     * Gabor responses are in intensity units and wavelet energies in squared intensity units, so on
     * 16-bit data one wavelet feature is ~1e4 times every Gabor feature and raw Euclidean distance
     * is decided by it alone. Standardising each dimension makes the assignment invariant to the
     * units of any one feature block, which is the property that test checks.
     */
    @Test
    public void textureClassesAreInvariantToRescalingOneFeatureBlock() {
        int[] plain = classify(1.0f);
        int[] rescaled = classify(1000.0f);
        assertEquals(plain.length, rescaled.length);
        for (int i = 0; i < plain.length; i++) {
            assertEquals("object " + i, plain[i], rescaled[i]);
        }
    }

    private static int[] classify(float waveletScale) {
        float[][] raw = {
                {1.0f, 1.1f, 1.2f, 1.0f, 900f, 400f, 120f, 60f},
                {1.1f, 1.0f, 1.1f, 1.1f, 950f, 380f, 110f, 55f},
                {0.9f, 1.2f, 1.0f, 0.9f, 880f, 420f, 130f, 65f},
                {8.0f, 8.1f, 8.2f, 8.0f, 910f, 390f, 115f, 62f},
                {8.1f, 8.0f, 8.1f, 8.1f, 940f, 410f, 125f, 58f},
                {7.9f, 8.2f, 8.0f, 7.9f, 890f, 405f, 118f, 61f},
        };
        java.util.List<ObjectTextureResult> group = new java.util.ArrayList<ObjectTextureResult>();
        for (int i = 0; i < raw.length; i++) {
            float[] features = new float[raw[i].length];
            for (int d = 0; d < features.length; d++) {
                features[d] = d < 4 ? raw[i][d] : raw[i][d] * waveletScale;
            }
            ObjectTextureResult result = new ObjectTextureResult(i + 1, 1000, "raw", false);
            result.featureVector =
                    new ObjectTextureFeatures.FeatureVector(features, true, true);
            group.add(result);
        }
        ObjectTextureAnalyzer.assignClasses(group, 2);
        int[] labels = new int[group.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = group.get(i).classLabel;
        return labels;
    }

    private static ObjectTextureFeatures.FeatureVector vector(float base) {
        float[] features = new float[ObjectTextureFeatures.DEFAULT_FEATURE_DIM];
        for (int i = 0; i < features.length; i++) features[i] = base + i * 0.01f;
        return new ObjectTextureFeatures.FeatureVector(features, true, true);
    }

    private static ObjectPatch patch(int width, int height, Pixel pixel) {
        float[] intensity = new float[width * height];
        byte[] mask = new byte[intensity.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                intensity[index] = pixel.value(x, y);
                mask[index] = 1;
            }
        }
        return new ObjectPatch(intensity, mask, width, height, 1.0);
    }

    private interface Pixel {
        float value(int x, int y);
    }
}
