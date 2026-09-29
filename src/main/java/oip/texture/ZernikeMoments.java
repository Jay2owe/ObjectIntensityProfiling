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
 * Intensity-weighted 2D Zernike moments of one object's mask-restricted patch (for 3D objects,
 * the maximum-intensity projection).
 *
 * <p>The unit disk is centred on the mask centroid (unweighted, pixel-index coordinates) with
 * radius equal to the largest centroid-to-pixel-centre distance plus half a pixel, so every mask
 * pixel lies inside it. For each order {@code n = 0..degree} and repetition
 * {@code m = n, n-2, ... >= 0}:</p>
 *
 * <pre>
 *   Z_nm      = sum over mask pixels of I * R_nm(rho) * exp(-i m theta)
 *   Magnitude = |Z_nm| / sum I            (dimensionless; unchanged by scaling intensity)
 *   Phase     = atan2(Im Z_nm, Re Z_nm)   (radians)
 * </pre>
 *
 * <p>Magnitude of (0,0) is therefore always 1. The moments are invalid (blank) when the object
 * has no finite pixels or its total intensity is not positive, and unreliable when the disk
 * radius is under {@link #RELIABLE_RADIUS} pixels.</p>
 */
public final class ZernikeMoments {

    public static final int DEFAULT_DEGREE = 9;
    public static final int MAX_DEGREE = 20;
    /** Disk radius in pixels below which the pixel grid is too coarse to trust the moments. */
    public static final double RELIABLE_RADIUS = 5.0;

    private ZernikeMoments() {
    }

    /** Moments for one object and channel, ordered by n then m descending. */
    public static final class Result {
        public final int[] n;
        public final int[] m;
        public final double[] magnitude;
        public final double[] phase;
        public final boolean valid;
        public final boolean reliable;
        /** Unit-disk radius in pixels (NaN when there are no pixels). */
        public final double radius;

        Result(int[] n, int[] m, double[] magnitude, double[] phase,
               boolean valid, boolean reliable, double radius) {
            this.n = n;
            this.m = m;
            this.magnitude = magnitude;
            this.phase = phase;
            this.valid = valid;
            this.reliable = reliable;
            this.radius = radius;
        }

        public int size() {
            return n.length;
        }
    }

    /** Number of (n, m) pairs for a degree: m = n, n-2, ... >= 0. */
    public static int momentCount(int degree) {
        int count = 0;
        for (int n = 0; n <= degree; n++) count += n / 2 + 1;
        return count;
    }

    public static Result compute(ObjectPatch patch, int degree,
                                 OipParameters.CancellationToken cancellation) {
        if (degree < 1 || degree > MAX_DEGREE) {
            throw new IllegalArgumentException(
                    "Zernike degree must be between 1 and " + MAX_DEGREE + ".");
        }
        int count = momentCount(degree);
        int[] ns = new int[count];
        int[] ms = new int[count];
        double[][] coefficients = new double[count][];
        int index = 0;
        for (int n = 0; n <= degree; n++) {
            for (int m = n; m >= 0; m -= 2) {
                ns[index] = n;
                ms[index] = m;
                coefficients[index] = radialCoefficients(n, m);
                index++;
            }
        }
        double[] magnitude = new double[count];
        double[] phase = new double[count];
        java.util.Arrays.fill(magnitude, Double.NaN);
        java.util.Arrays.fill(phase, Double.NaN);

        // Pass 1: mask centroid and total intensity over finite mask pixels.
        long pixels = 0;
        double sumX = 0.0;
        double sumY = 0.0;
        double total = 0.0;
        for (int y = 0; y < patch.height; y++) {
            if ((y & 31) == 0) checkCancelled(cancellation);
            for (int x = 0; x < patch.width; x++) {
                int i = y * patch.width + x;
                if (patch.mask[i] == 0 || !Float.isFinite(patch.intensity[i])) continue;
                pixels++;
                sumX += x;
                sumY += y;
                total += patch.intensity[i];
            }
        }
        if (pixels == 0) {
            return new Result(ns, ms, magnitude, phase, false, false, Double.NaN);
        }
        double cx = sumX / pixels;
        double cy = sumY / pixels;
        double maxDistance = 0.0;
        for (int y = 0; y < patch.height; y++) {
            for (int x = 0; x < patch.width; x++) {
                int i = y * patch.width + x;
                if (patch.mask[i] == 0 || !Float.isFinite(patch.intensity[i])) continue;
                double dx = x - cx;
                double dy = y - cy;
                maxDistance = Math.max(maxDistance, Math.sqrt(dx * dx + dy * dy));
            }
        }
        double radius = maxDistance + 0.5;
        boolean reliable = radius >= RELIABLE_RADIUS;
        if (!(total > 0.0) || !Double.isFinite(total)) {
            return new Result(ns, ms, magnitude, phase, false, reliable, radius);
        }

        // Pass 2: accumulate every moment in one sweep over the mask.
        double[] re = new double[count];
        double[] im = new double[count];
        double[] rhoPower = new double[degree + 1];
        double[] cosM = new double[degree + 1];
        double[] sinM = new double[degree + 1];
        for (int y = 0; y < patch.height; y++) {
            if ((y & 31) == 0) checkCancelled(cancellation);
            for (int x = 0; x < patch.width; x++) {
                int i = y * patch.width + x;
                if (patch.mask[i] == 0 || !Float.isFinite(patch.intensity[i])) continue;
                double value = patch.intensity[i];
                double dx = x - cx;
                double dy = y - cy;
                double rho = Math.sqrt(dx * dx + dy * dy) / radius;
                double theta = Math.atan2(dy, dx);
                rhoPower[0] = 1.0;
                for (int p = 1; p <= degree; p++) rhoPower[p] = rhoPower[p - 1] * rho;
                for (int m = 0; m <= degree; m++) {
                    cosM[m] = Math.cos(m * theta);
                    sinM[m] = Math.sin(m * theta);
                }
                for (int k = 0; k < count; k++) {
                    double r = radial(coefficients[k], ns[k], rhoPower);
                    double weighted = value * r;
                    re[k] += weighted * cosM[ms[k]];
                    im[k] -= weighted * sinM[ms[k]];
                }
            }
        }
        for (int k = 0; k < count; k++) {
            magnitude[k] = Math.hypot(re[k], im[k]) / total;
            phase[k] = Math.atan2(im[k], re[k]);
        }
        return new Result(ns, ms, magnitude, phase, true, reliable, radius);
    }

    /**
     * Coefficients c_s of R_nm(rho) = sum_s c_s rho^(n - 2s), s = 0..(n-m)/2, with
     * c_s = (-1)^s (n-s)! / (s! ((n+m)/2 - s)! ((n-m)/2 - s)!).
     */
    static double[] radialCoefficients(int n, int m) {
        int terms = (n - m) / 2 + 1;
        double[] c = new double[terms];
        for (int s = 0; s < terms; s++) {
            double value = factorial(n - s)
                    / (factorial(s) * factorial((n + m) / 2 - s) * factorial((n - m) / 2 - s));
            c[s] = (s % 2 == 0) ? value : -value;
        }
        return c;
    }

    /** R_nm(rho) for tests: evaluates the radial polynomial directly. */
    static double radial(int n, int m, double rho) {
        double[] powers = new double[n + 1];
        powers[0] = 1.0;
        for (int p = 1; p <= n; p++) powers[p] = powers[p - 1] * rho;
        return radial(radialCoefficients(n, m), n, powers);
    }

    private static double radial(double[] coefficients, int n, double[] rhoPower) {
        double sum = 0.0;
        for (int s = 0; s < coefficients.length; s++) {
            sum += coefficients[s] * rhoPower[n - 2 * s];
        }
        return sum;
    }

    private static double factorial(int value) {
        double result = 1.0;
        for (int i = 2; i <= value; i++) result *= i;
        return result;
    }

    private static void checkCancelled(OipParameters.CancellationToken cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ObjectIntensityProfiling.AnalysisCancelledException();
        }
    }
}
