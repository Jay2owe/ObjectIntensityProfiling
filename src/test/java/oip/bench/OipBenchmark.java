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
package oip.bench;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import oip.ObjectIntensityProfiling;
import oip.OipParameters;
import oip.profile.LabelObjects;
import oip.profile.OipConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Repeatable synthetic benchmark (not a JUnit test). Run from the project root after
 * {@code mvnw test-compile}:
 *
 * <pre>
 * java -cp "target/classes;target/test-classes;&lt;ij-1.54p.jar&gt;" oip.bench.OipBenchmark [small|large] [modes]
 * </pre>
 *
 * <p>{@code small} is a 512x512x24 grid of 1,024 spheres (16 px pitch); {@code large} is a
 * 2048x2048x10 grid with the same pitch, of which a region ROI keeps the 1,024 objects in the
 * top-left 512x512 block, so it profiles the same number of objects on slices 16 times bigger.
 * In both variants the first slice holds no objects, as in most real 3D label stacks; ImageJ
 * then gives the label stack a 0-0 display range, and every {@code ImageStack.getProcessor}
 * call rescans the whole slice to find one.
 * Modes is a comma list of {@code profiles}, {@code glcm}, {@code classes} (default all three).
 * Each mode runs with {@code oip.parallelism=1} and then with the default worker count.
 * System properties {@code oip.bench.warmup} (default 1) and {@code oip.bench.reps} (default 3)
 * set the repetitions; the median of the timed repetitions is printed.
 */
public final class OipBenchmark {

    private OipBenchmark() {
    }

    public static void main(String[] args) {
        String variant = args.length > 0 ? args[0] : "small";
        String modes = args.length > 1 ? args[1] : "profiles,glcm,classes";
        int warmup = Integer.getInteger("oip.bench.warmup", 1).intValue();
        int reps = Integer.getInteger("oip.bench.reps", 3).intValue();
        boolean large = "large".equals(variant);
        int w = large ? 2048 : 512, h = w, d = large ? 10 : 24, pitch = 16;
        // The large variant profiles only the objects in the top-left 512x512 block (a region
        // ROI), so both variants measure the same 1,024 objects and differ only in slice size.
        Roi[] region = large ? new Roi[] {new Roi(0, 0, 512, 512)} : null;

        long t0 = System.nanoTime();
        ImagePlus[] images = generate(w, h, d, pitch);
        long generated = System.nanoTime() - t0;
        t0 = System.nanoTime();
        int objects = LabelObjects.extract(images[0]).size();
        long extraction = System.nanoTime() - t0;
        System.out.printf(Locale.ROOT, "variant %s: %dx%dx%d, %d objects%s, pitch %d, %d processors%n",
                variant, w, h, d, objects, region == null ? "" : " (1,024 profiled: region ROI)",
                pitch, Runtime.getRuntime().availableProcessors());
        System.out.printf(Locale.ROOT, "generate %.0f ms, label extraction %.0f ms%n",
                generated / 1e6, extraction / 1e6);
        System.out.printf(Locale.ROOT, "warm-up %d, timed repetitions %d (median ms)%n", warmup, reps);

        for (String mode : modes.split(",")) {
            OipConfig config = config(mode.trim());
            double serial = time(images, region, config, "1", warmup, reps);
            double parallel = time(images, region, config, null, warmup, reps);
            System.out.printf(Locale.ROOT, "%-9s serial %9.0f ms   default %9.0f ms%n",
                    mode.trim(), serial, parallel);
        }
    }

    static OipConfig config(String mode) {
        OipConfig config = new OipConfig();
        if ("profiles".equals(mode)) return config;
        config.doRadial = false;
        config.doMarginal = false;
        config.doPrincipalAxis = false;
        config.doAngular = false;
        config.doShell = false;
        config.doWithinBox = false;
        if ("glcm".equals(mode)) {
            config.doGlcm = true;
        } else if ("classes".equals(mode)) {
            config.doTextureClasses = true;
        } else {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
        return config;
    }

    private static double time(ImagePlus[] images, Roi[] region, OipConfig config,
                               String parallelism,
                               int warmup, int reps) {
        if (parallelism == null) System.clearProperty("oip.parallelism");
        else System.setProperty("oip.parallelism", parallelism);
        try {
            for (int i = 0; i < warmup; i++) runOnce(images, region, config);
            double[] times = new double[reps];
            for (int i = 0; i < reps; i++) {
                long start = System.nanoTime();
                runOnce(images, region, config);
                times[i] = (System.nanoTime() - start) / 1e6;
            }
            Arrays.sort(times);
            return times[reps / 2];
        } finally {
            System.clearProperty("oip.parallelism");
        }
    }

    private static void runOnce(ImagePlus[] images, Roi[] region, OipConfig config) {
        OipParameters.Builder builder = OipParameters.builder(images[0])
                .addRawImage("A", images[1])
                .addRawImage("B", images[2])
                .referenceChannel("A")
                .config(config.copy());
        if (region != null) builder.regionRois(region);
        ObjectIntensityProfiling.run(builder.build());
    }

    /** Labels (16-bit) and two float channels with seeded noise. */
    static ImagePlus[] generate(int w, int h, int d, int pitch) {
        Random rnd = new Random(1);
        ImageStack labels = new ImageStack(w, h);
        ImageStack a = new ImageStack(w, h);
        ImageStack b = new ImageStack(w, h);
        double radius = pitch * 0.45;
        double half = pitch / 2.0;
        int perRow = w / pitch;
        for (int z = 0; z < d; z++) {
            short[] lp = new short[w * h];
            float[] ap = new float[w * h];
            float[] bp = new float[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int cx = x / pitch, cy = y / pitch;
                    double dx = x % pitch - half, dy = y % pitch - half, dz = (z - d / 2.0) * 1.5;
                    double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    int i = x + y * w;
                    if (r < radius) lp[i] = (short) (1 + cx + cy * perRow);
                    ap[i] = (float) (100 + 50 * Math.cos(r) + rnd.nextGaussian() * 5);
                    bp[i] = (float) (200 - 10 * r + rnd.nextGaussian() * 5);
                }
            }
            labels.addSlice(new ShortProcessor(w, h, lp, null));
            a.addSlice(new FloatProcessor(w, h, ap));
            b.addSlice(new FloatProcessor(w, h, bp));
        }
        List<ImagePlus> out = new ArrayList<ImagePlus>();
        out.add(new ImagePlus("bench-labels", labels));
        out.add(new ImagePlus("bench-A", a));
        out.add(new ImagePlus("bench-B", b));
        for (ImagePlus image : out) {
            // Calibrated (1 um cubes) so the uncalibrated-3D warning does not flood the output.
            Calibration calibration = new Calibration();
            calibration.setUnit("um");
            image.setCalibration(calibration);
        }
        return out.toArray(new ImagePlus[0]);
    }
}
