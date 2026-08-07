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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Gabor and wavelet object texture features plus deterministic k-means helpers.
 */
public final class ObjectTextureFeatures {
    public static final int DEFAULT_GABOR_ORIENTATIONS = 4;
    public static final int DEFAULT_WAVELET_SCALES = 4;
    public static final int DEFAULT_FEATURE_DIM = 8;
    public static final int DEFAULT_K = 4;

    /**
     * Gabor carrier wavelength in pixels. Fixed, deliberately: the filter bank has to be identical
     * for every object or the feature vectors are not comparable and k-means clusters by object size
     * instead of by texture. This is the same requirement that forces one GLCM quantisation range
     * across a batch. Four pixels is the finest resolvable period and keeps the kernel small enough
     * ({@value #GABOR_KERNEL_SIZE} px) that ordinary nuclei stay above the reliability threshold;
     * coarser scales are covered by the four wavelet energies, whose à trous steps are also fixed.
     */
    public static final double GABOR_WAVELENGTH_PX = 4.0;
    /** Gaussian envelope width of the Gabor kernel, in pixels. */
    public static final double GABOR_SIGMA_PX = 2.0;
    /** Side length of the square Gabor kernel, in pixels. Objects smaller than this are unreliable. */
    public static final int GABOR_KERNEL_SIZE = 2 * (int) Math.ceil(3.0 * GABOR_SIGMA_PX) + 1;

    private static final long KMEANS_SEED = 3405691582L;
    private static final int KMEANS_MAX_ITERATIONS = 100;
    private static final double[] ATROUS_KERNEL = {1.0 / 16.0, 4.0 / 16.0, 6.0 / 16.0, 4.0 / 16.0, 1.0 / 16.0};

    private ObjectTextureFeatures() {
    }

    public static final class FeatureVector {
        public final float[] features;
        public final boolean valid;
        public final boolean reliable;

        public FeatureVector(float[] features, boolean valid, boolean reliable) {
            this.features = features;
            this.valid = valid;
            this.reliable = reliable;
        }
    }

    public static final class ClassAssignment {
        public final int classLabel;
        public final double classDistance;

        public ClassAssignment(int classLabel, double classDistance) {
            this.classLabel = classLabel;
            this.classDistance = classDistance;
        }
    }

    public static FeatureVector computeFeatures(ObjectPatch patch) {
        return computeFeatures(patch, null);
    }

    public static FeatureVector computeFeatures(ObjectPatch patch,
                                                OipParameters.CancellationToken cancellation) {
        return computeFeatures(patch, DEFAULT_GABOR_ORIENTATIONS, DEFAULT_WAVELET_SCALES,
                cancellation);
    }

    static FeatureVector computeFeatures(ObjectPatch patch, int gaborOrientations, int waveletScales) {
        return computeFeatures(patch, gaborOrientations, waveletScales, null);
    }

    static FeatureVector computeFeatures(ObjectPatch patch, int gaborOrientations, int waveletScales,
                                         OipParameters.CancellationToken cancellation) {
        if (patch == null) {
            throw new IllegalArgumentException("patch must not be null");
        }
        if (gaborOrientations <= 0) {
            throw new IllegalArgumentException("gaborOrientations must be positive");
        }
        if (waveletScales <= 0) {
            throw new IllegalArgumentException("waveletScales must be positive");
        }

        patch = finiteMask(patch, cancellation);
        patch = tightCrop(patch, cancellation);
        MaskStats stats = maskStats(patch, cancellation);
        int featureDim = gaborOrientations + waveletScales;
        float[] features = new float[featureDim];
        if (stats.count == 0) {
            return new FeatureVector(features, false, false);
        }

        double[] centered = centeredImage(patch, stats.mean, cancellation);
        GaborSpec gabor = gaborSpec();
        double[][] kernels = gaborKernels(gaborOrientations, gabor, cancellation);
        for (int i = 0; i < gaborOrientations; i++) {
            checkCancelled(cancellation);
            features[i] = (float) maskedMeanAbsConvolution(centered, patch.mask,
                    patch.width, patch.height, kernels[i], gabor.kernelSize, stats.count,
                    cancellation);
        }

        double[] current = Arrays.copyOf(centered, centered.length);
        for (int scale = 0; scale < waveletScales; scale++) {
            checkCancelled(cancellation);
            int step = 1 << scale;
            double[] smooth = atrousSmooth(current, patch.width, patch.height, step, cancellation);
            double energy = 0.0;
            for (int i = 0; i < current.length; i++) {
                if ((i & 4095) == 0) checkCancelled(cancellation);
                if (patch.mask[i] == 0) continue;
                double coeff = current[i] - smooth[i];
                energy += coeff * coeff;
            }
            features[gaborOrientations + scale] = (float) (energy / stats.count);
            current = smooth;
        }

        boolean reliable = stats.count >= 16
                && patch.width >= gabor.kernelSize
                && patch.height >= gabor.kernelSize;
        boolean valid = allFinite(features);
        return new FeatureVector(features, valid, valid && reliable);
    }

    private static ObjectPatch finiteMask(
            ObjectPatch patch, OipParameters.CancellationToken cancellation) {
        byte[] effective = null;
        for (int i = 0; i < patch.mask.length; i++) {
            if ((i & 4095) == 0) checkCancelled(cancellation);
            if (patch.mask[i] == 0 || isFinite(patch.intensity[i])) continue;
            if (effective == null) effective = Arrays.copyOf(patch.mask, patch.mask.length);
            effective[i] = 0;
        }
        return effective == null ? patch : new ObjectPatch(
                patch.intensity, effective, patch.width, patch.height, patch.pixelSize_um);
    }

    private static ObjectPatch tightCrop(
            ObjectPatch patch, OipParameters.CancellationToken cancellation) {
        int x0 = patch.width, y0 = patch.height, x1 = -1, y1 = -1;
        for (int y = 0; y < patch.height; y++) {
            if ((y & 31) == 0) checkCancelled(cancellation);
            for (int x = 0; x < patch.width; x++) {
                if (patch.mask[y * patch.width + x] == 0) continue;
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
            }
        }
        if (x1 < x0 || y1 < y0
                || (x0 == 0 && y0 == 0 && x1 == patch.width - 1
                && y1 == patch.height - 1)) return patch;
        int width = x1 - x0 + 1, height = y1 - y0 + 1;
        float[] intensity = new float[width * height];
        byte[] mask = new byte[intensity.length];
        for (int y = 0; y < height; y++) {
            if ((y & 31) == 0) checkCancelled(cancellation);
            int source = (y + y0) * patch.width + x0;
            int target = y * width;
            System.arraycopy(patch.intensity, source, intensity, target, width);
            System.arraycopy(patch.mask, source, mask, target, width);
        }
        return new ObjectPatch(intensity, mask, width, height, patch.pixelSize_um);
    }

    public static ClassAssignment assignToCentroids(FeatureVector vec, double[][] centroids) {
        if (vec == null || vec.features == null || vec.features.length == 0) {
            throw new IllegalArgumentException("feature vector must not be empty");
        }
        if (centroids == null || centroids.length == 0) {
            throw new IllegalArgumentException("centroids must not be empty");
        }
        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int c = 0; c < centroids.length; c++) {
            if (centroids[c] == null || centroids[c].length != vec.features.length) {
                throw new IllegalArgumentException("centroid dimensionality mismatch");
            }
            double distance = euclidean(vec.features, centroids[c]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = c;
            }
        }
        return new ClassAssignment(best, bestDistance);
    }

    public static double[][] fitCentroids(List<FeatureVector> all, int k) {
        return fitCentroids(all, k, null);
    }

    public static double[][] fitCentroids(List<FeatureVector> all, int k,
                                          OipParameters.CancellationToken cancellation) {
        if (all == null) {
            throw new IllegalArgumentException("feature vectors must not be null");
        }
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }

        List<double[]> vectors = usableVectors(all, cancellation);
        if (vectors.isEmpty()) {
            return new double[0][0];
        }
        int dim = vectors.get(0).length;
        while (vectors.size() < k) {
            vectors.add(Arrays.copyOf(vectors.get(vectors.size() - 1), dim));
        }

        double[][] centroids = initializeKMeansPlusPlus(vectors, k, dim, cancellation);
        int[] assignment = new int[vectors.size()];
        Arrays.fill(assignment, -1);
        for (int iteration = 0; iteration < KMEANS_MAX_ITERATIONS; iteration++) {
            checkCancelled(cancellation);
            boolean changed = false;
            for (int i = 0; i < vectors.size(); i++) {
                if ((i & 255) == 0) checkCancelled(cancellation);
                int nearest = nearest(vectors.get(i), centroids);
                if (assignment[i] != nearest) {
                    assignment[i] = nearest;
                    changed = true;
                }
            }

            double[][] sums = new double[k][dim];
            int[] counts = new int[k];
            for (int i = 0; i < vectors.size(); i++) {
                if ((i & 255) == 0) checkCancelled(cancellation);
                int cluster = assignment[i];
                counts[cluster]++;
                double[] point = vectors.get(i);
                for (int d = 0; d < dim; d++) sums[cluster][d] += point[d];
            }
            for (int c = 0; c < k; c++) {
                if (counts[c] == 0) continue;
                for (int d = 0; d < dim; d++) centroids[c][d] = sums[c][d] / counts[c];
            }
            if (!changed) break;
        }

        List<double[]> sorted = new ArrayList<double[]>(Arrays.asList(centroids));
        Collections.sort(sorted, new LexicographicDoubleArrayComparator());
        return sorted.toArray(new double[sorted.size()][]);
    }

    private static double[][] initializeKMeansPlusPlus(
            List<double[]> vectors, int k, int dim,
            OipParameters.CancellationToken cancellation) {
        Random random = new Random(KMEANS_SEED);
        double[][] centers = new double[k][dim];
        centers[0] = Arrays.copyOf(vectors.get(random.nextInt(vectors.size())), dim);
        double[] distance = new double[vectors.size()];
        for (int c = 1; c < k; c++) {
            checkCancelled(cancellation);
            double total = 0.0;
            for (int i = 0; i < vectors.size(); i++) {
                if ((i & 255) == 0) checkCancelled(cancellation);
                double best = Double.POSITIVE_INFINITY;
                for (int previous = 0; previous < c; previous++) {
                    best = Math.min(best, squaredDistance(vectors.get(i), centers[previous]));
                }
                distance[i] = best;
                total += best;
            }
            int selected;
            if (total <= 0.0 || !isFinite(total)) {
                selected = c % vectors.size();
            } else {
                double target = random.nextDouble() * total;
                double cumulative = 0.0;
                selected = vectors.size() - 1;
                for (int i = 0; i < vectors.size(); i++) {
                    cumulative += distance[i];
                    if (cumulative >= target) {
                        selected = i;
                        break;
                    }
                }
            }
            centers[c] = Arrays.copyOf(vectors.get(selected), dim);
        }
        return centers;
    }

    private static int nearest(double[] point, double[][] centroids) {
        int best = 0;
        double bestDistance = squaredDistance(point, centroids[0]);
        for (int c = 1; c < centroids.length; c++) {
            double candidate = squaredDistance(point, centroids[c]);
            if (candidate < bestDistance) {
                bestDistance = candidate;
                best = c;
            }
        }
        return best;
    }

    private static double squaredDistance(double[] left, double[] right) {
        double sum = 0.0;
        for (int i = 0; i < left.length; i++) {
            double delta = left[i] - right[i];
            sum += delta * delta;
        }
        return sum;
    }

    private static List<double[]> usableVectors(
            List<FeatureVector> all, OipParameters.CancellationToken cancellation) {
        List<double[]> vectors = new ArrayList<double[]>();
        int dim = -1;
        for (int i = 0; i < all.size(); i++) {
            if ((i & 255) == 0) checkCancelled(cancellation);
            FeatureVector vector = all.get(i);
            if (vector == null || !vector.valid || vector.features == null) continue;
            if (!allFinite(vector.features)) continue;
            if (dim < 0) {
                dim = vector.features.length;
            } else if (vector.features.length != dim) {
                throw new IllegalArgumentException("feature dimensionality mismatch");
            }
            double[] copy = new double[vector.features.length];
            for (int f = 0; f < vector.features.length; f++) {
                copy[f] = vector.features[f];
            }
            vectors.add(copy);
        }
        return vectors;
    }

    private static boolean allFinite(float[] values) {
        for (int i = 0; i < values.length; i++) {
            if (!isFinite(values[i])) return false;
        }
        return true;
    }

    private static MaskStats maskStats(
            ObjectPatch patch, OipParameters.CancellationToken cancellation) {
        int count = 0;
        double sum = 0.0;
        for (int i = 0; i < patch.intensity.length; i++) {
            if ((i & 4095) == 0) checkCancelled(cancellation);
            if (patch.mask[i] == 0) continue;
            float value = patch.intensity[i];
            if (!isFinite(value)) continue;
            sum += value;
            count++;
        }
        return new MaskStats(count, count == 0 ? Double.NaN : sum / count);
    }

    private static double[] centeredImage(
            ObjectPatch patch, double maskMean,
            OipParameters.CancellationToken cancellation) {
        double[] centered = new double[patch.intensity.length];
        double mean = isFinite(maskMean) ? maskMean : 0.0;
        for (int i = 0; i < patch.intensity.length; i++) {
            if ((i & 4095) == 0) checkCancelled(cancellation);
            if (patch.mask[i] == 0) {
                centered[i] = 0.0;
                continue;
            }
            float value = patch.intensity[i];
            centered[i] = isFinite(value) ? value - mean : 0.0;
        }
        return centered;
    }

    private static GaborSpec gaborSpec() {
        return new GaborSpec(GABOR_WAVELENGTH_PX, GABOR_SIGMA_PX, GABOR_KERNEL_SIZE);
    }

    private static double[][] gaborKernels(
            int orientations, GaborSpec spec,
            OipParameters.CancellationToken cancellation) {
        double[][] kernels = new double[orientations][];
        for (int i = 0; i < orientations; i++) {
            checkCancelled(cancellation);
            double theta = Math.PI * i / orientations;
            kernels[i] = gaborKernel(theta, spec);
        }
        return kernels;
    }

    private static double[] gaborKernel(double theta, GaborSpec spec) {
        int size = spec.kernelSize;
        int radius = size / 2;
        double[] kernel = new double[size * size];
        double gamma = 0.5;
        double sum = 0.0;
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                double xr = x * Math.cos(theta) + y * Math.sin(theta);
                double yr = -x * Math.sin(theta) + y * Math.cos(theta);
                double envelope = Math.exp(-(xr * xr + gamma * gamma * yr * yr)
                        / (2.0 * spec.sigma * spec.sigma));
                double carrier = Math.cos(2.0 * Math.PI * xr / spec.wavelength);
                double value = envelope * carrier;
                kernel[(y + radius) * size + (x + radius)] = value;
                sum += value;
            }
        }
        double mean = sum / kernel.length;
        double norm = 0.0;
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] -= mean;
            norm += Math.abs(kernel[i]);
        }
        if (norm <= 0.0) norm = 1.0;
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= norm;
        }
        return kernel;
    }

    private static double maskedMeanAbsConvolution(double[] image,
                                                   byte[] mask,
                                                   int width,
                                                   int height,
                                                   double[] kernel,
                                                   int kernelSize,
                                                   int maskCount,
                                                   OipParameters.CancellationToken cancellation) {
        int radius = kernelSize / 2;
        double sum = 0.0;
        for (int y = 0; y < height; y++) {
            if ((y & 7) == 0) checkCancelled(cancellation);
            for (int x = 0; x < width; x++) {
                int center = y * width + x;
                if (mask[center] == 0) continue;
                double response = 0.0;
                for (int ky = -radius; ky <= radius; ky++) {
                    int yy = y + ky;
                    if (yy < 0 || yy >= height) continue;
                    for (int kx = -radius; kx <= radius; kx++) {
                        int xx = x + kx;
                        if (xx < 0 || xx >= width) continue;
                        double kval = kernel[(ky + radius) * kernelSize + (kx + radius)];
                        response += image[yy * width + xx] * kval;
                    }
                }
                sum += Math.abs(response);
            }
        }
        return maskCount == 0 ? Double.NaN : sum / maskCount;
    }

    private static double[] atrousSmooth(double[] image, int width, int height, int step,
                                         OipParameters.CancellationToken cancellation) {
        double[] temp = new double[image.length];
        double[] output = new double[image.length];
        for (int y = 0; y < height; y++) {
            if ((y & 15) == 0) checkCancelled(cancellation);
            for (int x = 0; x < width; x++) {
                double sum = 0.0;
                for (int k = -2; k <= 2; k++) {
                    int xx = clamp(x + k * step, 0, width - 1);
                    sum += image[y * width + xx] * ATROUS_KERNEL[k + 2];
                }
                temp[y * width + x] = sum;
            }
        }
        for (int y = 0; y < height; y++) {
            if ((y & 15) == 0) checkCancelled(cancellation);
            for (int x = 0; x < width; x++) {
                double sum = 0.0;
                for (int k = -2; k <= 2; k++) {
                    int yy = clamp(y + k * step, 0, height - 1);
                    sum += temp[yy * width + x] * ATROUS_KERNEL[k + 2];
                }
                output[y * width + x] = sum;
            }
        }
        return output;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static void checkCancelled(OipParameters.CancellationToken cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ObjectIntensityProfiling.AnalysisCancelledException();
        }
    }

    private static double euclidean(float[] features, double[] centroid) {
        double sum = 0.0;
        for (int i = 0; i < features.length; i++) {
            double delta = features[i] - centroid[i];
            sum += delta * delta;
        }
        return Math.sqrt(sum);
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /**
     * Reads and writes centroid files for callers that want to reuse one texture-class model across
     * runs. Nothing inside the plugin calls this — {@link oip.texture.ObjectTextureAnalyzer} fits a
     * model per run — so it exists purely as Java API surface.
     *
     * <p>Note that centroids fitted by the analyzer live in standardised feature space, not raw
     * feature space, so they are only meaningful alongside the standardisation that produced them.
     */
    public static final class CentroidsIO {
        private CentroidsIO() {
        }

        public static double[][] load(File centroidFile, int expectedFeatureDim) throws IOException {
            if (centroidFile == null || !centroidFile.isFile()) {
                return null;
            }
            if (expectedFeatureDim <= 0) {
                throw new IllegalArgumentException("expectedFeatureDim must be positive");
            }
            List<double[]> rows = new ArrayList<double[]>();
            BufferedReader reader = java.nio.file.Files.newBufferedReader(
                    centroidFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\s+");
                    if (parts.length != expectedFeatureDim) {
                        return null;
                    }
                    double[] row = new double[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        row[i] = Double.parseDouble(parts[i]);
                        if (!isFinite(row[i])) return null;
                    }
                    rows.add(row);
                }
            } finally {
                reader.close();
            }
            if (rows.isEmpty()) return null;
            return rows.toArray(new double[rows.size()][]);
        }

        public static void save(File centroidFile, double[][] centroids) throws IOException {
            if (centroidFile == null) {
                throw new IllegalArgumentException("centroidFile must not be null");
            }
            if (centroids == null) {
                throw new IllegalArgumentException("centroids must not be null");
            }
            File parent = centroidFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not create centroid directory: " + parent);
            }
            BufferedWriter writer = java.nio.file.Files.newBufferedWriter(
                    centroidFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            try {
                for (int r = 0; r < centroids.length; r++) {
                    if (centroids[r] == null) {
                        throw new IllegalArgumentException("centroid row must not be null");
                    }
                    for (int c = 0; c < centroids[r].length; c++) {
                        if (c > 0) writer.write(' ');
                        writer.write(String.format(Locale.ROOT, "%.12g", centroids[r][c]));
                    }
                    writer.newLine();
                }
            } finally {
                writer.close();
            }
        }
    }

    private static final class MaskStats {
        final int count;
        final double mean;

        private MaskStats(int count, double mean) {
            this.count = count;
            this.mean = mean;
        }
    }

    private static final class GaborSpec {
        final double wavelength;
        final double sigma;
        final int kernelSize;

        private GaborSpec(double wavelength, double sigma, int kernelSize) {
            this.wavelength = wavelength;
            this.sigma = sigma;
            this.kernelSize = kernelSize;
        }
    }

    private static final class LexicographicDoubleArrayComparator implements Comparator<double[]> {
        @Override
        public int compare(double[] left, double[] right) {
            int n = Math.min(left.length, right.length);
            for (int i = 0; i < n; i++) {
                int cmp = Double.compare(left[i], right[i]);
                if (cmp != 0) return cmp;
            }
            return Integer.compare(left.length, right.length);
        }
    }
}
