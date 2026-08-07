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

import oip.ObjectIntensityProfiling;
import oip.OipParameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates per-object normalized profile curves into per-bin mean ± SEM across objects, grouped
 * by (source channel, partner channel, profile type, grouping key e.g. image/condition). NaN bins
 * (objects whose box left a bin empty) are skipped per bin, so each bin's {@code n} is the number of
 * contributing objects. Feeds the aggregated CSVs (Stage 4) and figures (Stage 5).
 *
 * <p>Not thread-safe by itself; call {@link #add} from a single consumer thread, or one aggregator
 * per worker then {@link #merge}.
 */
public final class ProfileAggregator {

    public static final String RADIAL = "Radial";
    public static final String MARGINAL_X = "MarginalX";
    public static final String MARGINAL_Y = "MarginalY";
    public static final String MARGINAL_Z = "MarginalZ";
    public static final String PC_MAJOR = "PrincipalMajor";
    public static final String PC_MINOR = "PrincipalMinor";
    public static final String PC_THIRD = "PrincipalThird";
    public static final String ANGULAR = "Angular";
    public static final String SHELL_INTENSITY = "ShellIntensity";
    public static final String SHELL_OVERLAP = "ShellOverlap";

    private final Map<GroupKey, BinStats> groups =
            new LinkedHashMap<GroupKey, BinStats>();

    /** Add one object's normalized curve to its group; null/empty curves are ignored. */
    public void add(String source, String partner, String profileType, String groupKey, double[] curve) {
        add(source, partner, profileType, groupKey, curve, null);
    }

    public void add(String source, String partner, String profileType, String groupKey,
                    double[] curve, OipParameters.CancellationToken cancellation) {
        if (curve == null || curve.length == 0) return;
        GroupKey key = new GroupKey(source, partner, profileType, groupKey);
        BinStats bs = groups.get(key);
        if (bs == null) {
            bs = new BinStats(source, partner, profileType, groupKey, curve.length);
            groups.put(key, bs);
        }
        bs.add(curve, cancellation);
    }

    /** Convenience: add every profile of one partner result for a group key. */
    public void addAll(ObjectProfileResult res, String groupKey) {
        addAll(res, groupKey, null);
    }

    public void addAll(ObjectProfileResult res, String groupKey,
                       OipParameters.CancellationToken cancellation) {
        if (res == null) return;
        for (ObjectProfileResult.PartnerProfiles pf : res.byPartner.values()) {
            checkCancelled(cancellation);
            String s = res.sourceChannel, p = pf.partnerChannel;
            add(s, p, RADIAL, groupKey, pf.radialNorm, cancellation);
            add(s, p, MARGINAL_X, groupKey, pf.marginalXNorm, cancellation);
            add(s, p, MARGINAL_Y, groupKey, pf.marginalYNorm, cancellation);
            add(s, p, MARGINAL_Z, groupKey, pf.marginalZNorm, cancellation);
            add(s, p, PC_MAJOR, groupKey, pf.pcMajorNorm, cancellation);
            add(s, p, PC_MINOR, groupKey, pf.pcMinorNorm, cancellation);
            add(s, p, PC_THIRD, groupKey, pf.pcThirdNorm, cancellation);
            add(s, p, ANGULAR, groupKey, pf.angularNorm, cancellation);
            add(s, p, SHELL_INTENSITY, groupKey, pf.shellNorm, cancellation);
            add(s, p, SHELL_OVERLAP, groupKey, pf.shellOverlap, cancellation);
        }
    }

    /** Merge another aggregator's groups into this one (for per-worker accumulation). */
    public void merge(ProfileAggregator other) {
        if (other == null) return;
        for (Map.Entry<GroupKey, BinStats> e : other.groups.entrySet()) {
            BinStats mine = groups.get(e.getKey());
            // Copy rather than alias: the source aggregator stays live in the per-worker pattern
            // this class documents, and later additions to it must not rewrite merged results.
            if (mine == null) groups.put(e.getKey(), e.getValue().copy());
            else mine.merge(e.getValue());
        }
    }

    public List<AggregatedProfile> results() {
        return results(null);
    }

    public List<AggregatedProfile> results(
            OipParameters.CancellationToken cancellation) {
        List<AggregatedProfile> out = new ArrayList<AggregatedProfile>(groups.size());
        for (BinStats bs : groups.values()) {
            checkCancelled(cancellation);
            out.add(bs.finish(cancellation));
        }
        return out;
    }

    private static void checkCancelled(OipParameters.CancellationToken cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ObjectIntensityProfiling.AnalysisCancelledException();
        }
    }

    private static final class GroupKey {
        final String source;
        final String partner;
        final String profileType;
        final String groupKey;

        GroupKey(String source, String partner, String profileType, String groupKey) {
            this.source = source;
            this.partner = partner;
            this.profileType = profileType;
            this.groupKey = groupKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof GroupKey)) return false;
            GroupKey key = (GroupKey) other;
            return java.util.Objects.equals(source, key.source)
                    && java.util.Objects.equals(partner, key.partner)
                    && java.util.Objects.equals(profileType, key.profileType)
                    && java.util.Objects.equals(groupKey, key.groupKey);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(source, partner, profileType, groupKey);
        }
    }

    /** Normalized x-axis coordinate of bin {@code i} for a given profile type and bin count. */
    public static double axisAt(String profileType, int i, int length) {
        double t = (i + 0.5) / length;
        if (RADIAL.equals(profileType) || SHELL_INTENSITY.equals(profileType)
                || SHELL_OVERLAP.equals(profileType)) return t;    // 0..1
        if (ANGULAR.equals(profileType)) return t * 360.0;         // degrees
        return -1.0 + 2.0 * t;                                     // marginal/principal: -1..1
    }

    /** Aggregated mean ± SEM curve for one (source, partner, profileType, group). */
    public static final class AggregatedProfile {
        public final String source, partner, profileType, groupKey;
        public final double[] x, mean, sem;
        public final int[] n;
        public AggregatedProfile(String source, String partner, String profileType, String groupKey,
                                 double[] x, double[] mean, double[] sem, int[] n) {
            this.source = source; this.partner = partner; this.profileType = profileType;
            this.groupKey = groupKey; this.x = x; this.mean = mean; this.sem = sem; this.n = n;
        }
    }

    private static final class BinStats {
        final String source, partner, profileType, groupKey;
        final double[] mean, m2;
        final int[] n;
        BinStats(String source, String partner, String profileType, String groupKey, int len) {
            this.source = source; this.partner = partner; this.profileType = profileType;
            this.groupKey = groupKey;
            mean = new double[len]; m2 = new double[len]; n = new int[len];
        }
        BinStats copy() {
            BinStats c = new BinStats(source, partner, profileType, groupKey, mean.length);
            System.arraycopy(mean, 0, c.mean, 0, mean.length);
            System.arraycopy(m2, 0, c.m2, 0, m2.length);
            System.arraycopy(n, 0, c.n, 0, n.length);
            return c;
        }

        void add(double[] curve, OipParameters.CancellationToken cancellation) {
            // Every curve in a group is one profile family at one fixed bin count, so a length
            // mismatch is a caller bug. Fail loudly instead of silently dropping the extra bins.
            if (curve.length != mean.length) {
                throw new IllegalArgumentException("Curve length " + curve.length
                        + " does not match the " + mean.length + "-bin " + profileType
                        + " group; all curves in a group must share one length.");
            }
            int len = curve.length;
            for (int i = 0; i < len; i++) {
                if ((i & 255) == 0) checkCancelled(cancellation);
                double v = curve[i];
                if (Double.isNaN(v)) continue;
                n[i]++;
                double delta = v - mean[i];
                mean[i] += delta / n[i];
                m2[i] += delta * (v - mean[i]);
            }
        }
        void merge(BinStats o) {
            for (int i = 0; i < mean.length && i < o.mean.length; i++) {
                if (o.n[i] == 0) continue;
                if (n[i] == 0) {
                    mean[i] = o.mean[i];
                    m2[i] = o.m2[i];
                    n[i] = o.n[i];
                    continue;
                }
                int total = n[i] + o.n[i];
                double delta = o.mean[i] - mean[i];
                m2[i] += o.m2[i] + delta * delta * n[i] * o.n[i] / total;
                mean[i] += delta * o.n[i] / total;
                n[i] = total;
            }
        }
        AggregatedProfile finish(OipParameters.CancellationToken cancellation) {
            int len = mean.length;
            double[] x = new double[len], means = new double[len], sem = new double[len];
            for (int i = 0; i < len; i++) {
                if ((i & 255) == 0) checkCancelled(cancellation);
                x[i] = axisAt(profileType, i, len);
                if (n[i] > 0) {
                    means[i] = mean[i];
                    sem[i] = n[i] > 1
                            ? Math.sqrt(Math.max(0, m2[i]) / (n[i] * (n[i] - 1.0)))
                            : Double.NaN;
                } else {
                    means[i] = Double.NaN; sem[i] = Double.NaN;
                }
            }
            // Copy the counts: x/mean/sem are already snapshots, and handing out the live array
            // would let a later add() rewrite an already-returned result's n against stale means.
            int[] counts = new int[len];
            System.arraycopy(n, 0, counts, 0, len);
            return new AggregatedProfile(
                    source, partner, profileType, groupKey, x, means, sem, counts);
        }
    }
}
