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

/**
 * Mask-restricted 2D Grey-Level Co-occurrence Matrix features for one object.
 */
public final class ObjectTextureGLCM {
    public static final int DEFAULT_LEVELS = 32;
    public static final int DEFAULT_DISTANCE = 1;

    private static final double LOG_2 = Math.log(2.0);
    private static final int[][] OFFSETS = {
            {1, 0},
            {1, -1},
            {0, -1},
            {-1, -1}
    };

    private ObjectTextureGLCM() {
    }

    public static final class Result {
        public final double contrast;
        public final double asm;
        public final double correlation;
        public final double entropy;
        public final double homogeneity;
        public final boolean valid;
        public final boolean reliable;
        public final int coOccurrencePairs;

        private Result(double contrast,
                       double asm,
                       double correlation,
                       double entropy,
                       double homogeneity,
                       boolean valid,
                       boolean reliable,
                       int coOccurrencePairs) {
            this.contrast = contrast;
            this.asm = asm;
            this.correlation = correlation;
            this.entropy = entropy;
            this.homogeneity = homogeneity;
            this.valid = valid;
            this.reliable = reliable;
            this.coOccurrencePairs = coOccurrencePairs;
        }
    }

    public static Result compute(ObjectPatch patch) {
        return compute(patch, DEFAULT_LEVELS, DEFAULT_DISTANCE);
    }

    /**
     * Quantises to the range observed in this patch alone.
     *
     * <p><strong>The result is not comparable with any other object or image.</strong> Auto-ranging
     * rescales each patch to its own min and max, so two objects with identical texture at different
     * brightness produce different features. Every path inside this plugin instead passes a range
     * fixed across the batch — see {@link #compute(ObjectPatch, int, int, double, double)} and
     * {@link QuantizationRange}. Use this overload only for a single isolated patch.
     */
    public static Result compute(ObjectPatch patch, int levels, int distance) {
        validate(patch, levels, distance);
        Range range = intensityRange(patch, null);
        if (range.count == 0) return invalid(0);
        return compute(patch, levels, distance, range.min, range.max);
    }

    /**
     * Compute with a caller-supplied quantisation range. Reusing the same range for every image in
     * a batch makes the resulting co-occurrence features comparable across objects and images.
     */
    public static Result compute(ObjectPatch patch, int levels, int distance,
                                 double quantizationMin, double quantizationMax) {
        return compute(patch, levels, distance, quantizationMin, quantizationMax, null);
    }

    public static Result compute(ObjectPatch patch, int levels, int distance,
                                 double quantizationMin, double quantizationMax,
                                 OipParameters.CancellationToken cancellation) {
        validate(patch, levels, distance);
        if (!isFinite(quantizationMin) || !isFinite(quantizationMax)
                || quantizationMax < quantizationMin) {
            throw new IllegalArgumentException("quantisation range must be finite and ordered");
        }

        Range observed = intensityRange(patch, cancellation);
        if (observed.count == 0) return invalid(0);

        int[] quantized = new int[patch.intensity.length];
        for (int i = 0; i < quantized.length; i++) quantized[i] = -1;

        boolean[] occupied = new boolean[levels];
        int occupiedLevels = 0;
        double span = quantizationMax - quantizationMin;
        for (int i = 0; i < patch.intensity.length; i++) {
            if ((i & 4095) == 0) checkCancelled(cancellation);
            if (patch.mask[i] == 0 || !isFinite(patch.intensity[i])) continue;
            int q;
            if (span <= 0.0) {
                q = 0;
            } else {
                double unit = (patch.intensity[i] - quantizationMin) / span;
                q = (int) Math.floor(unit * levels);
                if (q < 0) q = 0;
                if (q >= levels) q = levels - 1;
            }
            quantized[i] = q;
            if (!occupied[q]) {
                occupied[q] = true;
                occupiedLevels++;
            }
        }

        return finish(patch, quantized, levels, distance, occupiedLevels, cancellation);
    }

    private static void validate(ObjectPatch patch, int levels, int distance) {
        if (patch == null) {
            throw new IllegalArgumentException("patch must not be null");
        }
        if (levels < 2) {
            throw new IllegalArgumentException("levels must be at least 2");
        }
        if (distance < 1) {
            throw new IllegalArgumentException("distance must be positive");
        }
    }

    /**
     * Compute Haralick features once per direction and average them, rather than pooling all four
     * offsets into one matrix and computing once. Contrast and homogeneity are linear in the matrix
     * so the two routes differ only by the directions' unequal pair counts, but ASM, entropy and
     * correlation are non-linear: pooling a checkerboard's four directions halves ASM and doubles
     * entropy. Per-direction averaging is what CellProfiler's MeasureTexture and the standard
     * Haralick definition report, so it is what this plugin exports.
     */
    private static Result finish(ObjectPatch patch, int[] quantized, int levels, int distance,
                                 int occupiedLevels,
                                 OipParameters.CancellationToken cancellation) {
        double contrast = 0.0;
        double asm = 0.0;
        double entropy = 0.0;
        double homogeneity = 0.0;
        double correlation = 0.0;
        int totalPairs = 0;
        int directions = 0;
        int correlatedDirections = 0;
        boolean correlationDefined = true;

        for (int o = 0; o < OFFSETS.length; o++) {
            checkCancelled(cancellation);
            double[][] matrix = new double[levels][levels];
            int pairs = buildMatrix(patch, quantized, matrix,
                    OFFSETS[o][0] * distance, OFFSETS[o][1] * distance, cancellation);
            totalPairs += pairs;
            if (pairs == 0) continue;
            Directional d = directional(matrix, levels, pairs, cancellation);
            contrast += d.contrast;
            asm += d.asm;
            entropy += d.entropy;
            homogeneity += d.homogeneity;
            if (isFinite(d.correlation)) {
                correlation += d.correlation;
                correlatedDirections++;
            } else {
                correlationDefined = false;
            }
            directions++;
        }

        if (totalPairs < 16 || directions == 0) {
            return invalid(totalPairs);
        }

        // A direction whose pairs all sit at one grey level has zero variance and no defined
        // correlation. Average the directions that do define it rather than discarding the
        // measurement entirely, but leave the object flagged unreliable so the gap is visible.
        // "Reliable" has to mean a genuine four-direction average. A one-pixel-tall slice yields
        // pairs for the axial offset alone, and without this it was exported indistinguishably
        // from a properly averaged object.
        double n = directions;
        boolean reliable = occupiedLevels >= 2 && correlationDefined
                && directions == OFFSETS.length;
        return new Result(contrast / n, asm / n,
                correlatedDirections > 0 ? correlation / correlatedDirections : Double.NaN,
                entropy / n, homogeneity / n, true, reliable, totalPairs);
    }

    /** All five features of one direction's symmetric, normalised co-occurrence matrix. */
    private static Directional directional(double[][] matrix, int levels, int pairs,
                                           OipParameters.CancellationToken cancellation) {
        double total = 2.0 * pairs;
        double[] row = new double[levels];
        double[] col = new double[levels];
        for (int i = 0; i < levels; i++) {
            checkCancelled(cancellation);
            for (int j = 0; j < levels; j++) {
                double p = matrix[i][j] / total;
                row[i] += p;
                col[j] += p;
            }
        }

        double meanI = 0.0;
        double meanJ = 0.0;
        for (int i = 0; i < levels; i++) {
            checkCancelled(cancellation);
            meanI += i * row[i];
            meanJ += i * col[i];
        }

        double varI = 0.0;
        double varJ = 0.0;
        for (int i = 0; i < levels; i++) {
            checkCancelled(cancellation);
            double di = i - meanI;
            double dj = i - meanJ;
            varI += di * di * row[i];
            varJ += dj * dj * col[i];
        }

        double contrast = 0.0;
        double asm = 0.0;
        double entropy = 0.0;
        double homogeneity = 0.0;
        double corrNumerator = 0.0;
        for (int i = 0; i < levels; i++) {
            checkCancelled(cancellation);
            for (int j = 0; j < levels; j++) {
                double p = matrix[i][j] / total;
                if (p <= 0.0) continue;
                int delta = i - j;
                contrast += delta * delta * p;
                asm += p * p;
                entropy -= p * (Math.log(p) / LOG_2);
                homogeneity += p / (1.0 + delta * delta);
                corrNumerator += (i - meanI) * (j - meanJ) * p;
            }
        }

        double sigma = Math.sqrt(varI * varJ);
        boolean hasCorrelation = sigma > 0.0 && isFinite(sigma);
        return new Directional(contrast, asm, entropy, homogeneity,
                hasCorrelation ? corrNumerator / sigma : Double.NaN);
    }

    private static int buildMatrix(ObjectPatch patch, int[] quantized, double[][] matrix,
                                   int dx, int dy,
                                   OipParameters.CancellationToken cancellation) {
        int pairs = 0;
        for (int y = 0; y < patch.height; y++) {
            if ((y & 15) == 0) checkCancelled(cancellation);
            int yy = y + dy;
            if (yy < 0 || yy >= patch.height) continue;
            for (int x = 0; x < patch.width; x++) {
                int xx = x + dx;
                if (xx < 0 || xx >= patch.width) continue;
                int aIndex = y * patch.width + x;
                int bIndex = yy * patch.width + xx;
                int a = quantized[aIndex];
                int b = quantized[bIndex];
                if (a < 0 || b < 0) continue;
                matrix[a][b] += 1.0;
                matrix[b][a] += 1.0;
                pairs++;
            }
        }
        return pairs;
    }

    private static final class Directional {
        final double contrast;
        final double asm;
        final double entropy;
        final double homogeneity;
        final double correlation;

        private Directional(double contrast, double asm, double entropy, double homogeneity,
                            double correlation) {
            this.contrast = contrast;
            this.asm = asm;
            this.entropy = entropy;
            this.homogeneity = homogeneity;
            this.correlation = correlation;
        }
    }

    private static Result invalid(int pairs) {
        return new Result(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, false, false, pairs);
    }

    private static Range intensityRange(
            ObjectPatch patch, OipParameters.CancellationToken cancellation) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int count = 0;
        for (int i = 0; i < patch.intensity.length; i++) {
            if ((i & 4095) == 0) checkCancelled(cancellation);
            if (patch.mask[i] == 0) continue;
            float value = patch.intensity[i];
            if (!isFinite(value)) continue;
            if (value < min) min = value;
            if (value > max) max = value;
            count++;
        }
        return new Range(min, max, count);
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static void checkCancelled(OipParameters.CancellationToken cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ObjectIntensityProfiling.AnalysisCancelledException();
        }
    }

    private static final class Range {
        final double min;
        final double max;
        final int count;

        private Range(double min, double max, int count) {
            this.min = min;
            this.max = max;
            this.count = count;
        }
    }
}
