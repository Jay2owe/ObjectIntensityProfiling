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
package oip.cluster;

import oip.ObjectIntensityProfiling;
import oip.OipParameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Deterministic k-means shared by texture classes and profile-shape classes: k-means++
 * initialisation from a fixed seed, at most {@link #MAX_ITERATIONS} Lloyd iterations, and
 * centroids returned in lexicographic order so class numbers do not depend on the seed's first
 * pick. Class 1 is therefore "first in sort order", not lowest in any biological sense.
 */
public final class KMeans {

    public static final long SEED = 3405691582L;
    public static final int MAX_ITERATIONS = 100;

    private KMeans() {
    }

    /**
     * Fit {@code k} centroids to finite, equal-length vectors. With fewer vectors than
     * {@code k}, the last vector is repeated so exactly {@code k} centroids are returned.
     */
    public static double[][] fit(List<double[]> input, int k,
                                 OipParameters.CancellationToken cancellation) {
        if (input == null) throw new IllegalArgumentException("vectors must not be null");
        if (k <= 0) throw new IllegalArgumentException("k must be positive");
        List<double[]> vectors = new ArrayList<double[]>(input);
        if (vectors.isEmpty()) {
            return new double[0][0];
        }
        int dim = vectors.get(0).length;
        for (double[] vector : vectors) {
            if (vector.length != dim) {
                throw new IllegalArgumentException("feature dimensionality mismatch");
            }
        }
        while (vectors.size() < k) {
            vectors.add(Arrays.copyOf(vectors.get(vectors.size() - 1), dim));
        }

        double[][] centroids = initializeKMeansPlusPlus(vectors, k, dim, cancellation);
        int[] assignment = new int[vectors.size()];
        Arrays.fill(assignment, -1);
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
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
        Random random = new Random(SEED);
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

    /** Index of the centroid with the smallest squared Euclidean distance (first on ties). */
    public static int nearest(double[] point, double[][] centroids) {
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

    public static double squaredDistance(double[] left, double[] right) {
        double sum = 0.0;
        for (int i = 0; i < left.length; i++) {
            double delta = left[i] - right[i];
            sum += delta * delta;
        }
        return sum;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static void checkCancelled(OipParameters.CancellationToken cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ObjectIntensityProfiling.AnalysisCancelledException();
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
