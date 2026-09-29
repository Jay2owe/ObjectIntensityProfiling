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
package oip.golden;

import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import oip.ObjectIntensityProfiling;
import oip.OipBatchParameters;
import oip.OipBatchRunner;
import oip.OipParameters;
import oip.OipRoiInputs;
import ij.gui.OvalRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.io.RoiEncoder;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import oip.profile.OipConfig;
import oip.profile.ProfileShapeClassifier;
import oip.texture.QuantizationRange;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeded synthetic input matrix for the golden output digests. Every case is fully
 * deterministic: images are generated from fixed seeds each time a case runs.
 *
 * <p>Do not change an existing case: its digests are committed. Add a new case instead.</p>
 */
final class GoldenCases {

    interface Runner {
        /** Run the case, writing every output under {@code output}; {@code scratch} holds inputs. */
        void run(File output, File scratch) throws IOException;
    }

    static final class Case {
        final String name;
        final Runner runner;

        Case(String name, Runner runner) {
            this.name = name;
            this.runner = runner;
        }
    }

    private GoldenCases() {
    }

    static List<Case> all() {
        List<Case> cases = new ArrayList<Case>();

        cases.add(single("2d-one-channel-mask-defaults", new SingleSpec() {
            @Override
            OipParameters.Builder build() {
                Scene scene = scene2d(96, 96, 9, 11L);
                ImagePlus labels = scene.labels(8, "labels2d");
                return OipParameters.builder(labels)
                        .addRawImage("C1", scene.raw("c1", 101L, Pattern.CENTRE))
                        .config(new OipConfig());
            }
        }));

        cases.add(single("2d-two-channels-box-pad25-zscore", new SingleSpec() {
            @Override
            OipParameters.Builder build() {
                Scene scene = scene2d(96, 96, 8, 12L);
                OipConfig config = new OipConfig();
                config.region = OipConfig.Region.WHOLE_BOX;
                config.boxPadPct = 25.0;
                config.intensityNorm = OipConfig.IntensityNorm.ZSCORE;
                return OipParameters.builder(scene.labels(16, "labels2d"))
                        .addRawImage("Nucleus", scene.raw("n", 201L, Pattern.CENTRE))
                        .addRawImage("Ring", scene.raw("r", 202L, Pattern.RIM))
                        .referenceChannel("Nucleus")
                        .config(config);
            }
        }));

        cases.add(single("3d-anisotropic-three-channels-mean", new SingleSpec() {
            @Override
            OipParameters.Builder build() {
                Scene scene = scene3d(64, 64, 12, 8, 13L);
                OipConfig config = new OipConfig();
                config.intensityNorm = OipConfig.IntensityNorm.DIVIDE_BY_MEAN;
                ImagePlus labels = scene.labels(16, "labels3d");
                Calibration calibration = new Calibration();
                calibration.pixelWidth = 0.2;
                calibration.pixelHeight = 0.2;
                calibration.pixelDepth = 0.8;
                calibration.setUnit("um");
                labels.setCalibration(calibration);
                return OipParameters.builder(labels)
                        .addRawImage("A", scene.raw("a", 301L, Pattern.CENTRE))
                        .addRawImage("B", scene.raw("b", 302L, Pattern.RIM))
                        .addRawImage("C", scene.raw("c", 303L, Pattern.GRADIENT_X))
                        .referenceChannel("B")
                        .config(config);
            }
        }));

        cases.add(single("3d-nan-one-voxel-border", new SingleSpec() {
            @Override
            OipParameters.Builder build() {
                Scene scene = scene3d(48, 48, 10, 5, 14L);
                // An object touching the x=0 and y=0 border, and a one-voxel object.
                scene.add(new double[] {1, 1, 4}, 4.0, 90);
                scene.add(new double[] {40, 6, 7}, 0.4, 91);
                ImagePlus labels = scene.labels(16, "labels3d-nan");
                ImagePlus rawA = scene.raw("a", 401L, Pattern.CENTRE);
                sprinkleNaN(rawA, 402L, 0.03);
                ImagePlus rawB = scene.raw("b", 403L, Pattern.RIM);
                return OipParameters.builder(labels)
                        .addRawImage("A", rawA)
                        .addRawImage("B", rawB)
                        .referenceChannel("A")
                        .config(new OipConfig());
            }
        }));

        cases.add(single("2d-16bit-sparse-labels", new SingleSpec() {
            @Override
            OipParameters.Builder build() {
                Scene scene = new Scene(80, 80, 1);
                scene.add(new double[] {20, 20, 0}, 8.0, 7);
                scene.add(new double[] {55, 25, 0}, 10.0, 300);
                scene.add(new double[] {40, 58, 0}, 12.0, 65535);
                return OipParameters.builder(scene.labels(16, "labels16"))
                        .addRawImage("Signal", scene.raw("s", 501L, Pattern.RIM))
                        .config(new OipConfig());
            }
        }));

        cases.add(single("3d-four-channels", new SingleSpec() {
            @Override
            OipParameters.Builder build() {
                Scene scene = scene3d(48, 48, 8, 6, 15L);
                return OipParameters.builder(scene.labels(8, "labels4ch"))
                        .addRawImage("W", scene.raw("w", 601L, Pattern.CENTRE))
                        .addRawImage("X", scene.raw("x", 602L, Pattern.RIM))
                        .addRawImage("Y", scene.raw("y", 603L, Pattern.GRADIENT_X))
                        .addRawImage("Z", scene.raw("z", 604L, Pattern.NOISE))
                        .referenceChannel("Y")
                        .config(new OipConfig());
            }
        }));

        cases.add(single("2d-glcm-texture-classes", new SingleSpec() {
            @Override
            OipParameters.Builder build() {
                Scene scene = scene2d(96, 96, 10, 16L);
                return OipParameters.builder(scene.labels(16, "labels-tex2d"))
                        .addRawImage("T1", scene.raw("t1", 701L, Pattern.TEXTURE))
                        .addRawImage("T2", scene.raw("t2", 702L, Pattern.RIM))
                        .referenceChannel("T1")
                        .config(textureConfig())
                        .saveClassMaps(true);
            }
        }));

        cases.add(single("3d-glcm-texture-classes", new SingleSpec() {
            @Override
            OipParameters.Builder build() {
                Scene scene = scene3d(56, 56, 8, 8, 17L);
                return OipParameters.builder(scene.labels(16, "labels-tex3d"))
                        .addRawImage("T1", scene.raw("t1", 801L, Pattern.TEXTURE))
                        .config(textureConfig())
                        .saveClassMaps(true);
            }
        }));

        cases.add(single("2d-glcm-manual-quantisation", new SingleSpec() {
            @Override
            OipParameters.Builder build() {
                Scene scene = scene2d(80, 80, 7, 18L);
                OipConfig config = new OipConfig();
                config.doGlcm = true;
                config.glcmLevels = 16;
                config.glcmDistance = 1;
                config.minimumTextureVoxels = 16;
                return OipParameters.builder(scene.labels(16, "labels-quant"))
                        .addRawImage("Q", scene.raw("q", 901L, Pattern.TEXTURE))
                        .quantizationRange("Q", new QuantizationRange(10.0, 180.0))
                        .config(config)
                        .saveClassMaps(false);
            }
        }));

        cases.add(single("2d-radial-shell-only", new SingleSpec() {
            @Override
            OipParameters.Builder build() {
                Scene scene = scene2d(72, 72, 6, 19L);
                OipConfig config = new OipConfig();
                config.doMarginal = false;
                config.doPrincipalAxis = false;
                config.doAngular = false;
                config.doWithinBox = false;
                config.radialBins = 7;
                config.shells = 4;
                config.ringThresholdPct = 30.0;
                return OipParameters.builder(scene.labels(32, "labels-radial"))
                        .addRawImage("R", scene.raw("r", 1001L, Pattern.RIM))
                        .config(config);
            }
        }));

        cases.add(single("3d-zernike-only-degree4", new SingleSpec() {
            @Override
            OipParameters.Builder build() {
                Scene scene = scene3d(56, 56, 8, 7, 20L);
                OipConfig config = new OipConfig();
                config.doZernike = true;
                config.zernikeDegree = 4;
                return OipParameters.builder(scene.labels(16, "labels-zernike3d"))
                        .addRawImage("Z1", scene.raw("z1", 1401L, Pattern.GRADIENT_X))
                        .addRawImage("Z2", scene.raw("z2", 1402L, Pattern.RIM))
                        .referenceChannel("Z1")
                        .config(config);
            }
        }));

        cases.add(single("2d-zernike-with-texture-classes", new SingleSpec() {
            @Override
            OipParameters.Builder build() {
                Scene scene = scene2d(96, 96, 9, 21L);
                OipConfig config = textureConfig();
                config.doGlcm = false;
                config.doZernike = true;
                return OipParameters.builder(scene.labels(16, "labels-zernike2d"))
                        .addRawImage("T", scene.raw("t", 1501L, Pattern.TEXTURE))
                        .config(config)
                        .saveClassMaps(true);
            }
        }));

        cases.add(new Case("batch-three-samples-texture", new Runner() {
            @Override
            public void run(File output, File scratch) throws IOException {
                File labels = mkdirs(new File(scratch, "labels"));
                File c1 = mkdirs(new File(scratch, "c1"));
                File c2 = mkdirs(new File(scratch, "c2"));
                String[] keys = {"S1", "S2", "S3"};
                for (int i = 0; i < keys.length; i++) {
                    Scene scene = scene2d(72, 72, 6, 1100L + i);
                    save(new File(labels, keys[i] + "_labels.tif"), scene.labels(16, keys[i]));
                    save(new File(c1, keys[i] + "_c1.tif"),
                            scene.raw("c1", 1200L + i, Pattern.TEXTURE, 1.0 + 0.5 * i));
                    save(new File(c2, keys[i] + "_c2.tif"),
                            scene.raw("c2", 1300L + i, Pattern.RIM, 1.0));
                }
                OipBatchParameters parameters = OipBatchParameters.builder(
                                labels, "(.*)_labels\\.tif", output)
                        .addRawChannel("C1", c1, "(.*)_c1\\.tif")
                        .addRawChannel("C2", c2, "(.*)_c2\\.tif")
                        .referenceChannel("C1")
                        .config(textureConfig())
                        .saveFigures(false)
                        .saveClassMaps(true)
                        .build();
                OipBatchRunner.run(parameters);
            }
        }));

        cases.add(single("3d-profile-classes-shell-k3", new SingleSpec() {
            @Override
            OipParameters.Builder build() {
                Scene scene = scene3d(64, 64, 10, 9, 22L);
                OipConfig config = new OipConfig();
                config.doProfileClasses = true;
                config.profileClasses = 3;
                config.profileClassFamily = ProfileShapeClassifier.Family.SHELL;
                return OipParameters.builder(scene.labels(16, "labels-classes3d"))
                        .addRawImage("P1", scene.raw("p1", 1601L, Pattern.NOISE))
                        .addRawImage("P2", scene.raw("p2", 1602L, Pattern.GRADIENT_X))
                        .referenceChannel("P1")
                        .config(config);
            }
        }));

        cases.add(new Case("batch-two-samples-profile-classes", new Runner() {
            @Override
            public void run(File output, File scratch) throws IOException {
                File labels = mkdirs(new File(scratch, "labels"));
                File c1 = mkdirs(new File(scratch, "c1"));
                String[] keys = {"A", "B"};
                Pattern[] patterns = {Pattern.CENTRE, Pattern.RIM};
                for (int i = 0; i < keys.length; i++) {
                    Scene scene = scene2d(72, 72, 7, 1700L + i);
                    save(new File(labels, keys[i] + "_labels.tif"), scene.labels(16, keys[i]));
                    save(new File(c1, keys[i] + "_c1.tif"),
                            scene.raw("c1", 1800L + i, patterns[i], 1.0));
                }
                OipConfig config = new OipConfig();
                config.doProfileClasses = true;
                config.profileClasses = 2;
                config.radialBins = 6;
                OipBatchParameters parameters = OipBatchParameters.builder(
                                labels, "(.*)_labels\\.tif", output)
                        .addRawChannel("C1", c1, "(.*)_c1\\.tif")
                        .referenceChannel("C1")
                        .config(config)
                        .saveFigures(false)
                        .saveClassMaps(false)
                        .build();
                OipBatchRunner.run(parameters);
            }
        }));

        cases.add(new Case("2d-roi-set-objects", new Runner() {
            @Override
            public void run(File output, File scratch) throws IOException {
                File set = new File(scratch, "cells.zip");
                ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(set));
                try {
                    Roi[] rois = {
                        new OvalRoi(8, 8, 20, 16), new Roi(40, 10, 18, 12),
                        new OvalRoi(20, 40, 24, 24), new PolygonRoi(
                                new int[] {50, 70, 66, 48}, new int[] {45, 50, 70, 64}, 4,
                                Roi.POLYGON),
                        new Roi(24, 44, 10, 10)
                    };
                    for (int i = 0; i < rois.length; i++) {
                        zip.putNextEntry(new ZipEntry(String.format("%04d.roi", i + 1)));
                        zip.write(RoiEncoder.saveAsByteArray(rois[i]));
                        zip.closeEntry();
                    }
                } finally {
                    zip.close();
                }
                Scene scene = scene2d(80, 80, 6, 23L);
                ImagePlus raw = scene.raw("r", 1901L, Pattern.GRADIENT_X);
                ImagePlus labels = OipRoiInputs.labelsFromRoiSet(raw, set.getPath());
                ObjectIntensityProfiling.run(OipParameters.builder(labels)
                        .addRawImage("R", raw)
                        .addRawImage("N", scene.raw("n", 1902L, Pattern.NOISE))
                        .referenceChannel("R")
                        .saveFigures(false)
                        .autoSave(output)
                        .build());
            }
        }));

        cases.add(single("3d-region-roi-restricted", new SingleSpec() {
            @Override
            OipParameters.Builder build() {
                Scene scene = scene3d(64, 64, 8, 9, 24L);
                Roi positioned = new Roi(30, 30, 34, 34);
                positioned.setPosition(0, 4, 0);
                return OipParameters.builder(scene.labels(16, "labels-region"))
                        .addRawImage("C", scene.raw("c", 2001L, Pattern.CENTRE))
                        .regionRois(new Roi[] {new OvalRoi(0, 0, 36, 36), positioned},
                                "region.zip")
                        .config(new OipConfig());
            }
        }));
        return cases;
    }

    private static OipConfig textureConfig() {
        OipConfig config = new OipConfig();
        config.doGlcm = true;
        config.doTextureClasses = true;
        config.glcmLevels = 16;
        config.glcmDistance = 1;
        config.textureClasses = 3;
        config.minimumTextureVoxels = 16;
        return config;
    }

    // ---------------------------------------------------------------- single-image plumbing

    abstract static class SingleSpec {
        abstract OipParameters.Builder build();
    }

    private static Case single(String name, final SingleSpec spec) {
        return new Case(name, new Runner() {
            @Override
            public void run(File output, File scratch) {
                OipParameters parameters = spec.build()
                        .saveFigures(false)
                        .autoSave(output)
                        .build();
                ObjectIntensityProfiling.run(parameters);
            }
        });
    }

    // ---------------------------------------------------------------- synthetic scenes

    enum Pattern { CENTRE, RIM, GRADIENT_X, NOISE, TEXTURE }

    /** Objects are balls (discs in 2D); later objects overwrite earlier ones where they overlap. */
    static final class Scene {
        final int width;
        final int height;
        final int depth;
        final List<double[]> centres = new ArrayList<double[]>();
        final List<Double> radii = new ArrayList<Double>();
        final List<Integer> ids = new ArrayList<Integer>();

        Scene(int width, int height, int depth) {
            this.width = width;
            this.height = height;
            this.depth = depth;
        }

        void add(double[] centre, double radius, int id) {
            centres.add(centre);
            radii.add(radius);
            ids.add(id);
        }

        /** Index of the object covering the voxel, or -1. */
        int owner(int x, int y, int z) {
            int owner = -1;
            for (int i = 0; i < centres.size(); i++) {
                double[] c = centres.get(i);
                double dx = x - c[0];
                double dy = y - c[1];
                double dz = depth == 1 ? 0.0 : (z - c[2]) * 2.0;
                if (dx * dx + dy * dy + dz * dz <= radii.get(i) * radii.get(i)) owner = i;
            }
            return owner;
        }

        /** Distance from the owning centre, normalised by its radius (0 centre, 1 edge). */
        double relative(int index, int x, int y, int z) {
            double[] c = centres.get(index);
            double dx = x - c[0];
            double dy = y - c[1];
            double dz = depth == 1 ? 0.0 : (z - c[2]) * 2.0;
            return Math.sqrt(dx * dx + dy * dy + dz * dz) / radii.get(index);
        }

        ImagePlus labels(int bitDepth, String title) {
            ImageStack stack = new ImageStack(width, height);
            for (int z = 0; z < depth; z++) {
                ImageProcessor processor = bitDepth == 8 ? new ByteProcessor(width, height)
                        : bitDepth == 16 ? new ShortProcessor(width, height)
                        : new FloatProcessor(width, height);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int owner = owner(x, y, z);
                        processor.setf(x, y, owner < 0 ? 0f : ids.get(owner).floatValue());
                    }
                }
                stack.addSlice(processor);
            }
            return new ImagePlus(title, stack);
        }

        ImagePlus raw(String title, long seed, Pattern pattern) {
            return raw(title, seed, pattern, 1.0);
        }

        ImagePlus raw(String title, long seed, Pattern pattern, double gain) {
            Random random = new Random(seed);
            ImageStack stack = new ImageStack(width, height);
            for (int z = 0; z < depth; z++) {
                FloatProcessor processor = new FloatProcessor(width, height);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        double noise = random.nextGaussian() * 4.0;
                        double texture = random.nextDouble();
                        int owner = owner(x, y, z);
                        double value = 12.0 + noise;
                        if (owner >= 0) {
                            double r = relative(owner, x, y, z);
                            double objectGain = 1.0 + 0.15 * (owner % 5);
                            switch (pattern) {
                                case CENTRE:
                                    value += 120.0 * objectGain * Math.max(0.0, 1.0 - r);
                                    break;
                                case RIM:
                                    value += 140.0 * objectGain * Math.exp(-12.0 * (r - 0.85) * (r - 0.85));
                                    break;
                                case GRADIENT_X:
                                    value += 60.0 + 1.5 * x * objectGain;
                                    break;
                                case NOISE:
                                    value += 40.0 + 25.0 * random.nextGaussian();
                                    break;
                                case TEXTURE:
                                default:
                                    int period = 2 + (owner % 3);
                                    boolean on = ((x / period) + (y / period) + z) % 2 == 0;
                                    value += (on ? 150.0 : 40.0) * objectGain + 30.0 * texture;
                                    break;
                            }
                        }
                        processor.setf(x, y, (float) (value * gain));
                    }
                }
                stack.addSlice(processor);
            }
            return new ImagePlus(title, stack);
        }
    }

    static Scene scene2d(int width, int height, int objects, long seed) {
        return scene(width, height, 1, objects, seed);
    }

    static Scene scene3d(int width, int height, int depth, int objects, long seed) {
        return scene(width, height, depth, objects, seed);
    }

    /** Non-touching balls placed on a jittered grid with seeded radii. */
    private static Scene scene(int width, int height, int depth, int objects, long seed) {
        Random random = new Random(seed);
        Scene scene = new Scene(width, height, depth);
        int columns = (int) Math.ceil(Math.sqrt(objects));
        int rows = (int) Math.ceil(objects / (double) columns);
        double cellW = width / (double) columns;
        double cellH = height / (double) rows;
        for (int i = 0; i < objects; i++) {
            int column = i % columns;
            int row = i / columns;
            double maxRadius = Math.min(cellW, cellH) / 2.0 - 1.5;
            double radius = Math.max(2.5, maxRadius * (0.55 + 0.4 * random.nextDouble()));
            double slack = Math.max(0.0, Math.min(cellW, cellH) / 2.0 - radius - 1.0);
            double cx = (column + 0.5) * cellW + (random.nextDouble() - 0.5) * slack;
            double cy = (row + 0.5) * cellH + (random.nextDouble() - 0.5) * slack;
            double cz = depth == 1 ? 0.0 : (depth - 1) / 2.0 + (random.nextDouble() - 0.5);
            if (depth > 1) radius = Math.min(radius, depth);
            scene.add(new double[] {cx, cy, cz}, radius, i + 1);
        }
        return scene;
    }

    private static void sprinkleNaN(ImagePlus image, long seed, double fraction) {
        Random random = new Random(seed);
        ImageStack stack = image.getStack();
        for (int z = 1; z <= stack.getSize(); z++) {
            float[] pixels = (float[]) stack.getPixels(z);
            for (int i = 0; i < pixels.length; i++) {
                if (random.nextDouble() < fraction) pixels[i] = Float.NaN;
            }
        }
    }

    private static File mkdirs(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        return directory;
    }

    private static void save(File file, ImagePlus image) throws IOException {
        FileSaver saver = new FileSaver(image);
        boolean saved = image.getStackSize() > 1
                ? saver.saveAsTiffStack(file.getAbsolutePath())
                : saver.saveAsTiff(file.getAbsolutePath());
        if (!saved) throw new IOException("Could not save " + file);
    }
}
