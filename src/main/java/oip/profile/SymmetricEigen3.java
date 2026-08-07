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
package oip.profile;

/**
 * Eigen-decomposition of a real symmetric 3×3 matrix via the cyclic Jacobi method. Small, dependency
 * free, and accurate enough for principal-axis analysis of an object's voxel covariance. Eigenvalues
 * are returned in descending order with their matching unit eigenvectors (as columns).
 */
public final class SymmetricEigen3 {

    /** Eigenvalues {@code values[0] >= values[1] >= values[2]} and column eigenvectors
     *  {@code vectors[*][k]} for value {@code k}. */
    public final double[] values;
    public final double[][] vectors;

    private SymmetricEigen3(double[] values, double[][] vectors) {
        this.values = values;
        this.vectors = vectors;
    }

    /**
     * Decompose the symmetric matrix given by its 6 unique entries.
     * Layout: {@code [[a,d,e],[d,b,f],[e,f,c]]}.
     */
    public static SymmetricEigen3 of(double a, double b, double c, double d, double e, double f) {
        double[][] A = {{a, d, e}, {d, b, f}, {e, f, c}};
        double[][] V = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};

        for (int sweep = 0; sweep < 50; sweep++) {
            double off = Math.abs(A[0][1]) + Math.abs(A[0][2]) + Math.abs(A[1][2]);
            if (off < 1e-12) break;
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < 3; q++) {
                    double apq = A[p][q];
                    if (Math.abs(apq) < 1e-15) continue;
                    double app = A[p][p];
                    double aqq = A[q][q];
                    double phi = 0.5 * Math.atan2(2.0 * apq, aqq - app);
                    double cphi = Math.cos(phi);
                    double sphi = Math.sin(phi);
                    rotate(A, p, q, cphi, sphi);
                    rotateVectors(V, p, q, cphi, sphi);
                }
            }
        }

        double[] eval = {A[0][0], A[1][1], A[2][2]};
        double[][] evec = new double[3][3];
        for (int k = 0; k < 3; k++) {
            evec[0][k] = V[0][k];
            evec[1][k] = V[1][k];
            evec[2][k] = V[2][k];
        }
        sortDescending(eval, evec);
        return new SymmetricEigen3(eval, evec);
    }

    private static void rotate(double[][] A, int p, int q, double c, double s) {
        double app = A[p][p], aqq = A[q][q], apq = A[p][q];
        A[p][p] = c * c * app - 2 * s * c * apq + s * s * aqq;
        A[q][q] = s * s * app + 2 * s * c * apq + c * c * aqq;
        A[p][q] = 0.0;
        A[q][p] = 0.0;
        int r = 3 - p - q; // the remaining index
        double arp = A[r][p], arq = A[r][q];
        double nrp = c * arp - s * arq;
        double nrq = s * arp + c * arq;
        A[r][p] = nrp; A[p][r] = nrp;
        A[r][q] = nrq; A[q][r] = nrq;
    }

    private static void rotateVectors(double[][] V, int p, int q, double c, double s) {
        for (int i = 0; i < 3; i++) {
            double vip = V[i][p], viq = V[i][q];
            V[i][p] = c * vip - s * viq;
            V[i][q] = s * vip + c * viq;
        }
    }

    private static void sortDescending(double[] eval, double[][] evec) {
        for (int i = 0; i < 2; i++) {
            int max = i;
            for (int j = i + 1; j < 3; j++) {
                if (eval[j] > eval[max]) max = j;
            }
            if (max != i) {
                double t = eval[i]; eval[i] = eval[max]; eval[max] = t;
                for (int r = 0; r < 3; r++) {
                    double tv = evec[r][i]; evec[r][i] = evec[r][max]; evec[r][max] = tv;
                }
            }
        }
    }
}
