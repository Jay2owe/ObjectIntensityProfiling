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
import ij.ImageStack;
import oip.profile.ObjectProfileResult;
import oip.profile.LabelObjects;
import oip.profile.OipConfig;
import oip.profile.ProfileAggregator;
import oip.texture.ObjectPatch;
import oip.texture.ObjectPatchBuilder;
import oip.texture.ObjectTextureAnalyzer;
import oip.texture.ObjectTextureResult;
import oip.texture.QuantizationRange;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ObjectIntensityProfilingTest {

    @Test(expected = IllegalArgumentException.class)
    public void interactiveIntegerSettingsRejectFractionalValues() {
        Object_Intensity_Profiling.exactInteger("Radial bins", 20.9);
    }

    @Test(expected = IllegalArgumentException.class)
    public void interactiveIntegerSettingsRejectNonFiniteValues() {
        Object_Intensity_Profiling.exactInteger("GLCM levels", Double.NaN);
    }

    @Test
    public void interactiveIntegerSettingsAcceptExactValues() {
        assertEquals(20, Object_Intensity_Profiling.exactInteger("Radial bins", 20.0));
    }

    @Test
    public void profileGroupsCannotCollideThroughDelimiterText() {
        ProfileAggregator aggregate = new ProfileAggregator();
        aggregate.add("a", "b<|>c", "d", "e", new double[] {1});
        aggregate.add("a<|>b", "c", "d", "e", new double[] {2});

        assertEquals(2, aggregate.results().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRawImageWithDifferentDimensions() {
        ImagePlus labels = SyntheticImages.image("labels", 8, 8, 1, constant(1));
        ImagePlus raw = SyntheticImages.image("raw", 7, 8, 1, constant(1));
        ObjectIntensityProfiling.run(labels, raw("raw", raw));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRawImageWithEmptyStack() {
        ImagePlus labels = SyntheticImages.image("labels", 8, 8, 1, constant(1));
        ImagePlus raw = new ImagePlus("empty", new ImageStack(8, 8));
        ObjectIntensityProfiling.run(labels, raw("raw", raw));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDifferentChannelSliceTimeLayoutEvenWhenStackSizesMatch() {
        ImagePlus labels = SyntheticImages.image("labels", 8, 8, 2, constant(1));
        labels.setDimensions(1, 2, 1);
        ImagePlus raw = SyntheticImages.image("raw", 8, 8, 2, constant(1));
        raw.setDimensions(2, 1, 1);
        ObjectIntensityProfiling.run(labels, raw("raw", raw));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsQuantisationRangeForUnknownRawChannel() {
        ImagePlus labels = SyntheticImages.image("labels", 8, 8, 1, constant(1));
        ImagePlus raw = SyntheticImages.image("raw", 8, 8, 1, constant(1));
        ObjectIntensityProfiling.run(OipParameters.builder(labels)
                .addRawImage("Signal", raw)
                .quantizationRange("Typo", new QuantizationRange(0, 1))
                .build());
    }

    @Test
    public void preservesSurroundingWhitespaceInRawAndReferenceChannelNames() {
        ImagePlus labels = SyntheticImages.image("labels", 8, 8, 1, constant(1));
        ImagePlus raw = SyntheticImages.image("raw", 8, 8, 1, constant(2));
        OipParameters parameters = OipParameters.builder(labels)
                .addRawImage("Signal ", raw)
                .referenceChannel("Signal ")
                .saveFigures(false)
                .saveClassMaps(false)
                .build();

        OipResult result = ObjectIntensityProfiling.run(parameters);

        assertEquals("Signal ", result.getParameters().getReferenceChannel());
        assertTrue(result.getParameters().getRawImages().containsKey("Signal "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsFractionalLabelPixels() {
        ImagePlus labels = SyntheticImages.image("labels", 8, 8, 1, constant(1.25f));
        ImagePlus raw = SyntheticImages.image("raw", 8, 8, 1, constant(1));
        ObjectIntensityProfiling.run(labels, raw("raw", raw));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonFiniteLabelPixels() {
        ImagePlus labels = SyntheticImages.image("labels", 8, 8, 1, constant(Float.NaN));
        ImagePlus raw = SyntheticImages.image("raw", 8, 8, 1, constant(1));
        ObjectIntensityProfiling.run(labels, raw("raw", raw));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRawChannelNamesThatCollideOnDisk() {
        ImagePlus labels = SyntheticImages.image("labels", 8, 8, 1, constant(1));
        Map<String, ImagePlus> channels = new LinkedHashMap<String, ImagePlus>();
        channels.put("Signal", SyntheticImages.image("first", 8, 8, 1, constant(1)));
        channels.put("signal", SyntheticImages.image("second", 8, 8, 1, constant(2)));
        ObjectIntensityProfiling.run(OipParameters.builder(labels)
                .rawImages(channels)
                .referenceChannel("Signal")
                .build());
    }

    @Test
    public void knownGradientHasPerfectCrossChannelCorrelationAndPositiveAxisPolarity() {
        ImagePlus labels = SyntheticImages.image("labels", 15, 9, 1, constant(1));
        ImagePlus reference = SyntheticImages.image("reference", 15, 9, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x + 1;
                    }
                });
        ImagePlus partner = SyntheticImages.image("partner", 15, 9, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return 2 * (x + 1);
                    }
                });

        OipConfig config = profiles(false);
        config.doMarginal = true;
        config.doPrincipalAxis = true;
        config.doWithinBox = true;
        Map<String, ImagePlus> raw = raw("reference", reference);
        raw.put("partner", partner);
        OipResult result = ObjectIntensityProfiling.run(
                OipParameters.builder(labels)
                        .rawImages(raw)
                        .referenceChannel("reference")
                        .config(config)
                        .build());

        assertEquals(1, result.getProfiles().size());
        ObjectProfileResult.PartnerProfiles measured =
                result.getProfiles().get(0).byPartner.get("partner");
        assertNotNull(measured.pcMajorRaw);
        assertEquals(1.0, measured.withinBoxPearson, 1.0e-12);
        assertEquals(1.0, measured.withinBoxOverlap, 1.0e-12);
        assertEquals(1.0, measured.mandersM1, 1.0e-12);
        assertEquals(1.0, measured.mandersM2, 1.0e-12);
        assertTrue("polarity=" + measured.pcMajorPolarity,
                measured.pcMajorPolarity > 0.25);
    }

    @Test
    public void highOffsetPearsonAndZScoreRemainStable() {
        ImagePlus labels = SyntheticImages.image("labels", 1000, 1, 1, constant(1));
        ImagePlus raw = SyntheticImages.image("raw", 1000, 1, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return (x & 1) == 0 ? 100000000f : 100000008f;
                    }
                });
        OipConfig config = profiles(false);
        config.doMarginal = true;
        config.doWithinBox = true;
        config.resampleN = 1000;
        config.intensityNorm = OipConfig.IntensityNorm.ZSCORE;

        ObjectProfileResult.PartnerProfiles profile = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("raw", raw)
                        .config(config).saveClassMaps(false).build())
                .getProfiles().get(0).byPartner.get("raw");

        assertEquals(1.0, profile.withinBoxPearson, 1.0e-12);
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (double value : profile.marginalXNorm) {
            if (!Double.isFinite(value)) continue;
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
        }
        assertEquals(-1.0, minimum, 1.0e-12);
        assertEquals(1.0, maximum, 1.0e-12);
    }

    @Test
    public void overflowingTextureFeaturesBecomeInvalidRatherThanCrashingClustering() {
        ImagePlus labels = SyntheticImages.image("labels", 16, 16, 1, constant(1));
        ImagePlus raw = SyntheticImages.image("raw", 16, 16, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return ((x + y) & 1) == 0
                                ? Float.MAX_VALUE : -Float.MAX_VALUE;
                    }
                });

        OipResult result = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("raw", raw)
                        .config(textureClassesOnly()).saveClassMaps(true).build());

        ObjectTextureResult texture = result.getTextures().get(0);
        assertTrue(!texture.featureVector.valid);
        assertTrue(!texture.featureVector.reliable);
        assertEquals(-1, texture.classLabel);
        assertTrue(result.getClassMaps().isEmpty());
    }

    @Test
    public void interactivePresentationBuildsAggregateProfilePlots() {
        ImagePlus labels = SyntheticImages.image("labels", 16, 16, 1, constant(1));
        ImagePlus raw = SyntheticImages.image("raw", 16, 16, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x + y;
                    }
                });
        OipResult result = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("raw", raw)
                        .saveClassMaps(false).build());

        java.util.List<ImagePlus> figures =
                Object_Intensity_Profiling.aggregateFigures(result);

        assertTrue(!figures.isEmpty());
        for (ImagePlus figure : figures) {
            assertTrue(figure.getTitle().startsWith("OIP "));
            figure.close();
            figure.flush();
        }
    }

    @Test
    public void extremelyLargeFinitePaddingSaturatesAtImageBounds() {
        ImagePlus labels = SyntheticImages.image("labels", 16, 16, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x >= 4 && x < 12 && y >= 4 && y < 12 ? 1 : 0;
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
        config.boxPadPct = Double.MAX_VALUE;
        config.doTextureClasses = true;
        config.minimumTextureVoxels = 1;

        OipResult result = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("raw", raw)
                        .config(config).saveClassMaps(false).build());

        assertEquals(1, result.getProfiles().size());
        assertEquals(1, result.getTextures().size());
        assertTrue(result.getTextures().get(0).featureVector.valid);
    }

    @Test
    public void brightOuterRingProducesOuterDominantShellProfile() {
        final int center = 10;
        ImagePlus labels = SyntheticImages.image("disc", 21, 21, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        double r = Math.hypot(x - center, y - center);
                        return r <= 10 ? 1 : 0;
                    }
                });
        ImagePlus raw = SyntheticImages.image("ring", 21, 21, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        double r = Math.hypot(x - center, y - center);
                        return r >= 7 ? 10 : 1;
                    }
                });

        OipConfig config = profiles(false);
        config.doRadial = true;
        config.doShell = true;
        config.shells = 3;
        OipResult result = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("ring", raw)
                        .config(config).build());
        ObjectProfileResult.PartnerProfiles profile =
                result.getProfiles().get(0).byPartner.get("ring");
        assertTrue(profile.shellOuter > profile.shellInner * 5);
        assertTrue(profile.radialCoreEdge < 0.5);
    }

    @Test
    public void halfFilledDiscHasHalfCompleteAngularProfile() {
        final int center = 12;
        ImagePlus labels = SyntheticImages.image("disc", 25, 25, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return Math.hypot(x - center, y - center) <= 11 ? 1 : 0;
                    }
                });
        ImagePlus raw = SyntheticImages.image("half", 25, 25, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x >= center ? 10 : 0;
                    }
                });
        OipConfig config = profiles(false);
        config.doAngular = true;
        config.angularBins = 12;
        OipResult result = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("half", raw)
                        .config(config).build());
        double completeness = result.getProfiles().get(0)
                .byPartner.get("half").ringCompleteness;
        assertEquals(0.5, completeness, 0.1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void requiresExplicitReferenceForMultipleRawChannels() {
        ImagePlus labels = SyntheticImages.image("labels", 8, 8, 1, constant(1));
        Map<String, ImagePlus> raw = raw("first", labels.duplicate());
        raw.put("second", labels.duplicate());
        ObjectIntensityProfiling.run(labels, raw);
    }

    @Test
    public void uniformSphereHasFlatRadialProfileAndNoPolarity() {
        final int center = 7;
        ImagePlus labels = SyntheticImages.image("sphere", 15, 15, 15,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        double dx = x - center;
                        double dy = y - center;
                        double dz = z - center;
                        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= 6 ? 1 : 0;
                    }
                });
        ImagePlus raw = SyntheticImages.image("uniform", 15, 15, 15, constant(5));
        OipConfig config = profiles(false);
        config.doRadial = true;
        config.doShell = true;
        config.shells = 3;
        OipResult result = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("uniform", raw)
                        .config(config).build());
        ObjectProfileResult.PartnerProfiles profile =
                result.getProfiles().get(0).byPartner.get("uniform");
        for (double value : profile.radialRaw) {
            if (Double.isFinite(value)) assertEquals(5.0, value, 0.0);
        }
        assertEquals(3, profile.shellRaw.length);
        for (int i = 0; i < profile.shellRaw.length; i++) {
            assertEquals(5.0, profile.shellRaw[i], 0.0);
            assertEquals(1.0, profile.shellOverlap[i], 1.0e-12);
        }
        assertEquals(0.0, profile.radialPolarity, 1.0e-12);
    }

    @Test
    public void maskProfilesDoNotChangeWhenOnlyPaddingChanges() {
        ImagePlus labels = SyntheticImages.image("labels", 21, 21, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x >= 6 && x <= 14 && y >= 7 && y <= 13 ? 1 : 0;
                    }
                });
        ImagePlus raw = SyntheticImages.image("raw", 21, 21, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x + 2 * y;
                    }
                });
        OipConfig noPadding = profiles(false);
        noPadding.doRadial = true;
        noPadding.doMarginal = true;
        OipConfig padded = noPadding.copy();
        padded.boxPadPct = 100;
        ObjectProfileResult.PartnerProfiles first = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("raw", raw)
                        .config(noPadding).build()).getProfiles().get(0).byPartner.get("raw");
        ObjectProfileResult.PartnerProfiles second = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("raw", raw)
                        .config(padded).build()).getProfiles().get(0).byPartner.get("raw");
        org.junit.Assert.assertArrayEquals(first.radialRaw, second.radialRaw, 0.0);
        org.junit.Assert.assertArrayEquals(first.marginalXRaw, second.marginalXRaw, 0.0);
    }

    @Test
    public void aggregationUsesSampleSem() {
        ProfileAggregator aggregate = new ProfileAggregator();
        aggregate.add("single", "p", ProfileAggregator.RADIAL, "g", new double[] {4});
        aggregate.add("s", "p", ProfileAggregator.RADIAL, "g", new double[] {0});
        aggregate.add("s", "p", ProfileAggregator.RADIAL, "g", new double[] {2});
        assertTrue(Double.isNaN(aggregate.results().get(0).sem[0]));
        assertEquals(1.0, aggregate.results().get(1).sem[0], 1.0e-12);
    }

    @Test(expected = ObjectIntensityProfiling.AnalysisCancelledException.class)
    public void profileAggregationCanBeCancelledWithinBins() {
        ProfileAggregator aggregate = new ProfileAggregator();
        aggregate.add("s", "p", ProfileAggregator.RADIAL, "g",
                new double[10000], new OipParameters.CancellationToken() {
                    @Override
                    public boolean isCancelled() {
                        return true;
                    }
                });
    }

    @Test(expected = ObjectIntensityProfiling.AnalysisCancelledException.class)
    public void classMapRenderingCanBeCancelledAfterFirstChannel() throws Exception {
        ImagePlus labels = SyntheticImages.image("labels", 8, 8, 1, constant(1));
        java.lang.reflect.Constructor<ObjectTextureResult> constructor =
                ObjectTextureResult.class.getDeclaredConstructor(
                        int.class, int.class, String.class, boolean.class);
        constructor.setAccessible(true);
        ObjectTextureResult first = constructor.newInstance(1, 64, "first", false);
        ObjectTextureResult second = constructor.newInstance(1, 64, "second", false);
        first.classLabel = 0;
        second.classLabel = 0;
        final int[] checks = {0};

        TextureClassMapRenderer.render(labels,
                java.util.Arrays.asList(first, second),
                new OipParameters.CancellationToken() {
                    @Override
                    public boolean isCancelled() {
                        return ++checks[0] >= 5;
                    }
                });
    }

    @Test
    public void textureMipCannotTakeIntensityFromNonObjectZPlane() {
        ImagePlus labels = SyntheticImages.image("labels", 4, 4, 2,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return (z == 0 && x == 1 && y == 1)
                                || (z == 1 && x == 2 && y == 1) ? 1 : 0;
                    }
                });
        ImagePlus raw = SyntheticImages.image("raw", 4, 4, 2,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        if (x == 1 && y == 1) return z == 0 ? 5 : 100;
                        return 2;
                    }
                });
        LabelObjects.ObjectInfo object = LabelObjects.extract(labels).get(0);
        ObjectPatch patch = ObjectPatchBuilder.buildMIP(object, labels, raw, 0);
        assertEquals(5.0, patch.intensity[0], 0.0);
    }

    @Test
    public void textureFeaturesDoNotChangeWithProfilePadding() {
        ImagePlus labels = SyntheticImages.image("labels", 40, 40, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x >= 10 && x < 30 && y >= 10 && y < 30 ? 1 : 0;
                    }
                });
        ImagePlus raw = SyntheticImages.image("raw", 40, 40, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x * x + y;
                    }
                });
        OipConfig firstConfig = profiles(false);
        firstConfig.doTextureClasses = true;
        firstConfig.minimumTextureVoxels = 1;
        OipConfig paddedConfig = firstConfig.copy();
        paddedConfig.boxPadPct = 50;
        float[] first = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("raw", raw)
                        .config(firstConfig).saveClassMaps(false).build())
                .getTextures().get(0).featureVector.features;
        float[] padded = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("raw", raw)
                        .config(paddedConfig).saveClassMaps(false).build())
                .getTextures().get(0).featureVector.features;
        org.junit.Assert.assertArrayEquals(first, padded, 0.0f);
    }

    @Test
    public void allNanObjectMipRemainsMissingRatherThanZero() {
        ImagePlus labels = SyntheticImages.image("labels", 4, 4, 1, constant(1));
        ImagePlus raw = SyntheticImages.image("raw", 4, 4, 1, constant(Float.NaN));
        ObjectPatch patch = ObjectPatchBuilder.buildMIP(
                LabelObjects.extract(labels).get(0), labels, raw, 0);
        assertTrue(Float.isNaN(patch.intensity[0]));
        assertEquals(0, patch.mask[0]);
        assertTrue(!ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("raw", raw)
                        .config(textureClassesOnly()).saveClassMaps(false).build())
                .getTextures().get(0).featureVector.valid);
    }

    @Test
    public void missingMipPixelsDoNotChangeTextureFeatures() {
        ImagePlus fullLabels = SyntheticImages.image("full-labels", 20, 20, 1, constant(1));
        ImagePlus finiteLabels = SyntheticImages.image("finite-labels", 20, 20, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x >= 4 ? 1 : 0;
                    }
                });
        ImagePlus missingRaw = SyntheticImages.image("missing", 20, 20, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x < 4 ? Float.NaN : x * x + y;
                    }
                });
        ImagePlus maskedRaw = SyntheticImages.image("masked", 20, 20, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x < 4 ? 10000 : x * x + y;
                    }
                });

        float[] missing = ObjectIntensityProfiling.run(
                OipParameters.builder(fullLabels).addRawImage("raw", missingRaw)
                        .config(textureClassesOnly()).saveClassMaps(false).build())
                .getTextures().get(0).featureVector.features;
        float[] masked = ObjectIntensityProfiling.run(
                OipParameters.builder(finiteLabels).addRawImage("raw", maskedRaw)
                        .config(textureClassesOnly()).saveClassMaps(false).build())
                .getTextures().get(0).featureVector.features;

        org.junit.Assert.assertArrayEquals(masked, missing, 0.0f);
    }

    @Test
    public void unreliableThinTextureIsReportedButNotClassified() {
        ImagePlus labels = SyntheticImages.image("labels", 32, 32, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x == 15 ? 1 : 0;
                    }
                });
        ImagePlus raw = SyntheticImages.image("raw", 32, 32, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x + y;
                    }
                });

        OipResult result = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("raw", raw)
                        .config(textureClassesOnly()).saveClassMaps(true).build());
        ObjectTextureResult texture = result.getTextures().get(0);

        assertTrue(texture.featureVector.valid);
        assertTrue(!texture.featureVector.reliable);
        assertEquals(-1, texture.classLabel);
        assertTrue(result.getClassMaps().isEmpty());
        assertEquals(0.0,
                OipTables.textures(result).getValue("Feature reliable", 0), 0.0);
    }

    @Test
    public void batchAnalysisCanDeferTextureClassFitting() {
        ImagePlus labels = SyntheticImages.image("labels", 16, 16, 1, constant(1));
        ImagePlus raw = SyntheticImages.image("raw", 16, 16, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x + y;
                    }
                });
        OipConfig config = textureClassesOnly();
        config.textureClasses = 1;
        OipResult result = ObjectIntensityProfiling.runWithDeferredTextureClasses(
                OipParameters.builder(labels).addRawImage("raw", raw)
                        .config(config).saveClassMaps(false).build());

        assertEquals(-1, result.getTextures().get(0).classLabel);
        ObjectTextureAnalyzer.assignClasses(result.getTextures(), 1);
        assertEquals(0, result.getTextures().get(0).classLabel);
    }

    @Test(expected = ObjectIntensityProfiling.AnalysisCancelledException.class)
    public void cancellationIsCheckedInsideOneLargeObject() {
        ImagePlus labels = SyntheticImages.image("labels", 128, 128, 1, constant(1));
        ImagePlus raw = SyntheticImages.image("raw", 128, 128, 1, constant(2));
        OipConfig config = profiles(false);
        config.doRadial = true;
        ObjectIntensityProfiling.run(OipParameters.builder(labels).addRawImage("raw", raw)
                .config(config)
                .cancellationToken(new OipParameters.CancellationToken() {
                    private int calls;
                    @Override
                    public boolean isCancelled() {
                        return ++calls >= 7;
                    }
                }).build());
    }

    /**
     * A measurement that does not exist must not be reported as 0, because 0 is a valid value for
     * every one of these columns. Skipping {@code addValue} for a non-finite value does not leave
     * the cell empty — a column created on a later row is allocated zero-filled, so every earlier
     * row silently reads 0.0.
     */
    @Test
    public void interactiveTablesReportUndefinedMeasurementsAsNaNNotZero() {
        ImagePlus labels = SyntheticImages.image("two", 32, 32, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        if (x >= 1 && x < 3 && y >= 1 && y < 3) return 1;   // 4 voxels: suppressed
                        return x >= 8 && x < 28 && y >= 8 && y < 28 ? 2 : 0;
                    }
                });
        ImagePlus raw = SyntheticImages.image("Signal", 32, 32, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return ((x + y) & 1) == 0 ? 0 : 200;
                    }
                });
        OipConfig config = profiles(true);
        config.doGlcm = true;
        config.doTextureClasses = true;
        OipResult result = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("Signal", raw).config(config).build());

        ij.measure.ResultsTable textures = OipTables.textures(result);
        int contrast = textures.getColumnIndex("Contrast");
        assertTrue("Contrast column must exist on every row",
                contrast != ij.measure.ResultsTable.COLUMN_NOT_FOUND);
        assertTrue("suppressed object must not report contrast 0",
                Double.isNaN(textures.getValueAsDouble(contrast, 0)));
        assertTrue("suppressed object must not report class distance 0",
                Double.isNaN(textures.getValueAsDouble(
                        textures.getColumnIndex("Class distance"), 0)));
        assertTrue("measured object should have a real contrast",
                Double.isFinite(textures.getValueAsDouble(contrast, 1)));

        // The interactive table and the CSV must name the same eight features identically.
        assertTrue(textures.getColumnIndex("Gabor 45")
                != ij.measure.ResultsTable.COLUMN_NOT_FOUND);
        assertTrue(textures.getColumnIndex("Gabor 135")
                != ij.measure.ResultsTable.COLUMN_NOT_FOUND);
    }

    /** A hollow object has no core intensity at all, which is not a core intensity of zero. */
    @Test
    public void hollowObjectReportsAnUndefinedCoreEdgeRatio() {
        ImagePlus labels = SyntheticImages.image("annulus", 40, 40, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        double r = Math.hypot(x - 19.5, y - 19.5);
                        return r >= 12 && r <= 19 ? 1 : 0;
                    }
                });
        ImagePlus raw = SyntheticImages.image("uniform", 40, 40, 1, constant(7));
        OipConfig config = profiles(false);
        config.doRadial = true;
        config.radialBins = 20;
        ObjectProfileResult.PartnerProfiles profile = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("uniform", raw).config(config).build())
                .getProfiles().get(0).byPartner.get("uniform");

        assertTrue(Double.isNaN(profile.radialRaw[0]));
        assertTrue("coreEdge=" + profile.radialCoreEdge,
                Double.isNaN(profile.radialCoreEdge));
    }

    /** {@code n / 3} is 0 for one- and two-bin curves, which used to empty the inner range. */
    @Test
    public void shortRadialCurvesStillProduceACoreEdgeRatio() {
        ImagePlus labels = SyntheticImages.image("square", 20, 20, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return x >= 2 && x < 18 && y >= 2 && y < 18 ? 1 : 0;
                    }
                });
        ImagePlus raw = SyntheticImages.image("uniform", 20, 20, 1, constant(7));
        for (int bins : new int[] {1, 2, 3, 20}) {
            OipConfig config = profiles(false);
            config.doRadial = true;
            config.radialBins = bins;
            ObjectProfileResult.PartnerProfiles profile = ObjectIntensityProfiling.run(
                    OipParameters.builder(labels).addRawImage("uniform", raw).config(config)
                            .build()).getProfiles().get(0).byPartner.get("uniform");
            assertEquals("radialBins=" + bins, 1.0, profile.radialCoreEdge, 1.0e-12);
        }
    }

    /**
     * A mean-centred channel sums to zero only up to rounding, so the running mean lands near
     * 1e-17. Dividing by it produced ~1e17 and that curve then entered the population aggregate.
     */
    @Test
    public void divideByMeanDropsBinsWhenTheObjectMeanIsZero() {
        ImagePlus labels = SyntheticImages.image("bar", 20, 4, 1, constant(1));
        ImagePlus raw = SyntheticImages.image("centred", 20, 4, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return (float) (x - 9.5);
                    }
                });
        OipConfig config = profiles(false);
        config.doMarginal = true;
        config.resampleN = 4;
        config.intensityNorm = OipConfig.IntensityNorm.DIVIDE_BY_MEAN;
        ObjectProfileResult.PartnerProfiles profile = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("centred", raw).config(config).build())
                .getProfiles().get(0).byPartner.get("centred");

        assertEquals(-7.5, profile.marginalXRaw[0], 1.0e-9);
        for (double value : profile.marginalXNorm) {
            assertTrue("normalised=" + value, Double.isNaN(value));
        }
    }

    /**
     * An empty angular bin means no signal at that angle, which is maximal non-uniformity. Skipping
     * those bins made a straight line report a perfectly uniform ring.
     */
    @Test
    public void emptyAngularBinsCountAsNonUniform() {
        ImagePlus raw = SyntheticImages.image("uniform", 40, 40, 1, constant(50));
        OipConfig config = profiles(false);
        config.doAngular = true;
        config.angularBins = 12;

        ImagePlus line = SyntheticImages.image("line", 40, 40, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return y == 20 && x >= 8 && x < 33 ? 1 : 0;
                    }
                });
        ObjectProfileResult.PartnerProfiles straight = ObjectIntensityProfiling.run(
                OipParameters.builder(line).addRawImage("uniform", raw).config(config).build())
                .getProfiles().get(0).byPartner.get("uniform");
        assertEquals(2.0 / 12.0, straight.ringCompleteness, 1.0e-12);
        assertEquals(0.0, straight.angularUniformity, 1.0e-12);

        ImagePlus disc = SyntheticImages.image("disc", 40, 40, 1,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        return Math.hypot(x - 19.5, y - 19.5) <= 15 ? 1 : 0;
                    }
                });
        ObjectProfileResult.PartnerProfiles full = ObjectIntensityProfiling.run(
                OipParameters.builder(disc).addRawImage("uniform", raw).config(config).build())
                .getProfiles().get(0).byPartner.get("uniform");
        assertEquals(1.0, full.ringCompleteness, 1.0e-12);
        assertEquals(1.0, full.angularUniformity, 1.0e-12);
    }

    /**
     * The uniform-sphere test uses one constant value everywhere, so it cannot see mask leakage.
     * This gives the background a different, much larger value.
     */
    @Test
    public void maskModeProfilesIgnoreSignalOutsideTheObject() {
        ImagePlus labels = SyntheticImages.image("sphere", 15, 15, 15,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        double dx = x - 7, dy = y - 7, dz = z - 7;
                        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= 6 ? 1 : 0;
                    }
                });
        ImagePlus raw = SyntheticImages.image("hot-background", 15, 15, 15,
                new SyntheticImages.Pixel() {
                    @Override
                    public float value(int x, int y, int z) {
                        double dx = x - 7, dy = y - 7, dz = z - 7;
                        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= 6 ? 5 : 9000;
                    }
                });
        OipConfig config = profiles(true);
        config.doPrincipalAxis = false;
        config.shells = 3;
        ObjectProfileResult.PartnerProfiles profile = ObjectIntensityProfiling.run(
                OipParameters.builder(labels).addRawImage("hot-background", raw).config(config)
                        .build()).getProfiles().get(0).byPartner.get("hot-background");

        for (double value : profile.radialRaw) {
            if (Double.isFinite(value)) assertEquals(5.0, value, 0.0);
        }
        for (double value : profile.marginalXRaw) {
            if (Double.isFinite(value)) assertEquals(5.0, value, 0.0);
        }
        for (double value : profile.angularRaw) {
            if (Double.isFinite(value)) assertEquals(5.0, value, 0.0);
        }
        for (double value : profile.shellRaw) {
            assertEquals(5.0, value, 0.0);
        }
    }

    /** Merged groups must not alias the source aggregator's running state. */
    @Test
    public void mergedGroupsAreIndependentOfTheSourceAggregator() {
        ProfileAggregator target = new ProfileAggregator();
        ProfileAggregator worker = new ProfileAggregator();
        worker.add("s", "p", ProfileAggregator.RADIAL, "g", new double[] {2.0});
        target.merge(worker);

        worker.add("s", "p", ProfileAggregator.RADIAL, "g", new double[] {8.0});

        assertEquals(2.0, target.results().get(0).mean[0], 1.0e-12);
        assertEquals(1, target.results().get(0).n[0]);
    }

    /** Silently truncating a longer curve would drop bins from a published aggregate. */
    @Test(expected = IllegalArgumentException.class)
    public void aggregatingCurvesOfDifferentLengthsIsRejected() {
        ProfileAggregator aggregate = new ProfileAggregator();
        aggregate.add("s", "p", ProfileAggregator.RADIAL, "g", new double[] {1, 2, 3});
        aggregate.add("s", "p", ProfileAggregator.RADIAL, "g", new double[] {1, 2, 3, 4, 5});
    }

    private static OipConfig profiles(boolean enabled) {
        OipConfig config = new OipConfig();
        config.doRadial = enabled;
        config.doMarginal = enabled;
        config.doPrincipalAxis = enabled;
        config.doAngular = enabled;
        config.doShell = enabled;
        config.doWithinBox = enabled;
        return config;
    }

    private static OipConfig textureClassesOnly() {
        OipConfig config = profiles(false);
        config.doTextureClasses = true;
        config.minimumTextureVoxels = 1;
        return config;
    }

    private static Map<String, ImagePlus> raw(String name, ImagePlus image) {
        Map<String, ImagePlus> raw = new LinkedHashMap<String, ImagePlus>();
        raw.put(name, image);
        return raw;
    }

    private static SyntheticImages.Pixel constant(final float value) {
        return new SyntheticImages.Pixel() {
            @Override
            public float value(int x, int y, int z) {
                return value;
            }
        };
    }
}
