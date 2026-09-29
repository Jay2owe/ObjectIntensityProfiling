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
import oip.cluster.KMeans;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Groups objects by the shape of one normalised profile curve (radial by default), per partner
 * channel, with the same deterministic k-means as texture classes.
 *
 * <p>Curves are already normalised per object ({@code intensity_norm}), so no further
 * standardisation is applied: the class distance is in the same normalised units as the curve.
 * Objects whose curve is missing or has an empty (NaN) bin are excluded and get no class.</p>
 */
public final class ProfileShapeClassifier {

    /** The curve family used as each object's shape vector. */
    public enum Family {
        RADIAL("radial", ProfileAggregator.RADIAL, "radial"),
        SHELL("shell", ProfileAggregator.SHELL_INTENSITY, "shell"),
        ANGULAR("angular", ProfileAggregator.ANGULAR, "angular"),
        PC_MAJOR("pc_major", ProfileAggregator.PC_MAJOR, "principal"),
        MARGINAL_X("marginal_x", ProfileAggregator.MARGINAL_X, "marginal"),
        MARGINAL_Y("marginal_y", ProfileAggregator.MARGINAL_Y, "marginal");

        /** Macro value for {@code profile_class_type=}. */
        public final String macroValue;
        /** {@code ProfileType} written to the output tables. */
        public final String profileType;
        /** Macro flag of the profile family this curve needs. */
        public final String requiredFlag;

        Family(String macroValue, String profileType, String requiredFlag) {
            this.macroValue = macroValue;
            this.profileType = profileType;
            this.requiredFlag = requiredFlag;
        }

        public static Family parse(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            for (Family family : values()) {
                if (family.macroValue.equals(normalized)) return family;
            }
            throw new IllegalArgumentException("profile_class_type must be radial, shell, angular, "
                    + "pc_major, marginal_x or marginal_y; got: " + value);
        }

        boolean enabledIn(OipConfig config) {
            switch (this) {
                case SHELL:
                    return config.doShell;
                case ANGULAR:
                    return config.doAngular;
                case PC_MAJOR:
                    return config.doPrincipalAxis;
                case MARGINAL_X:
                case MARGINAL_Y:
                    return config.doMarginal;
                case RADIAL:
                default:
                    return config.doRadial;
            }
        }

        double[] curve(ObjectProfileResult.PartnerProfiles partner) {
            switch (this) {
                case SHELL:
                    return partner.shellNorm;
                case ANGULAR:
                    return partner.angularNorm;
                case PC_MAJOR:
                    return partner.pcMajorNorm;
                case MARGINAL_X:
                    return partner.marginalXNorm;
                case MARGINAL_Y:
                    return partner.marginalYNorm;
                case RADIAL:
                default:
                    return partner.radialNorm;
            }
        }
    }

    /** One object's curve for one partner: the compact record a batch retains. */
    public static final class Curve {
        public final String source;
        public final int label;
        public final int voxelCount;
        public final String partner;
        public final double[] values;

        public Curve(String source, int label, int voxelCount, String partner, double[] values) {
            this.source = source;
            this.label = label;
            this.voxelCount = voxelCount;
            this.partner = partner;
            this.values = values;
        }
    }

    /** Class for one object and partner; {@code classLabel} is -1 when the object was excluded. */
    public static final class Assignment {
        public final Curve curve;
        public final int classLabel;
        public final double distance;

        Assignment(Curve curve, int classLabel, double distance) {
            this.curve = curve;
            this.classLabel = classLabel;
            this.distance = distance;
        }
    }

    /** Group key of class-mean curves in aggregates and figure names. */
    public static final String GROUP = "Profile classes";

    private ProfileShapeClassifier() {
    }

    /** Reject a class curve whose profile family is switched off. */
    public static void validate(OipConfig config) {
        if (!config.doProfileClasses) return;
        if (config.profileClassFamily == null) {
            throw new IllegalArgumentException("A profile class curve type is required.");
        }
        if (config.profileClasses < 1 || config.profileClasses > 255) {
            throw new IllegalArgumentException("profile_k must be between 1 and 255.");
        }
        if (!config.profileClassFamily.enabledIn(config)) {
            Family family = config.profileClassFamily;
            throw new IllegalArgumentException("profile_class_type=" + family.macroValue
                    + " needs the " + family.requiredFlag + " profile; remove no_"
                    + family.requiredFlag + " or choose another profile_class_type.");
        }
    }

    /** Every object's curve of one family, in profile then partner order. */
    public static List<Curve> curves(List<ObjectProfileResult> profiles, Family family) {
        List<Curve> curves = new ArrayList<Curve>();
        for (ObjectProfileResult object : profiles) {
            for (ObjectProfileResult.PartnerProfiles partner : object.byPartner.values()) {
                double[] values = family.curve(partner);
                curves.add(new Curve(object.sourceChannel, object.label, object.voxelCount,
                        partner.partnerChannel, values == null ? null : values.clone()));
            }
        }
        return curves;
    }

    /**
     * Fit classes per partner over every usable curve and assign them. The result has one entry
     * per input curve, in input order, so a batch can fit once over all samples' curves.
     */
    public static List<Assignment> classify(List<Curve> curves, int requestedK,
                                            OipParameters.CancellationToken cancellation) {
        if (requestedK < 1) throw new IllegalArgumentException("profile_k must be positive.");
        Map<String, List<Integer>> usableByPartner = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < curves.size(); i++) {
            if ((i & 255) == 0) checkCancelled(cancellation);
            Curve curve = curves.get(i);
            if (!usable(curve.values)) continue;
            List<Integer> group = usableByPartner.get(curve.partner);
            if (group == null) {
                group = new ArrayList<Integer>();
                usableByPartner.put(curve.partner, group);
            }
            group.add(i);
        }
        int[] classes = new int[curves.size()];
        double[] distances = new double[curves.size()];
        java.util.Arrays.fill(classes, -1);
        java.util.Arrays.fill(distances, Double.NaN);
        for (List<Integer> group : usableByPartner.values()) {
            checkCancelled(cancellation);
            int length = curves.get(group.get(0)).values.length;
            List<double[]> vectors = new ArrayList<double[]>(group.size());
            for (int index : group) {
                double[] values = curves.get(index).values;
                if (values.length != length) {
                    throw new IllegalArgumentException(
                            "All profile curves for one partner must have the same length.");
                }
                vectors.add(values);
            }
            int k = Math.min(requestedK, vectors.size());
            double[][] centroids = KMeans.fit(vectors, k, cancellation);
            for (int j = 0; j < group.size(); j++) {
                if ((j & 255) == 0) checkCancelled(cancellation);
                int nearest = KMeans.nearest(vectors.get(j), centroids);
                classes[group.get(j)] = nearest;
                distances[group.get(j)] = Math.sqrt(
                        KMeans.squaredDistance(vectors.get(j), centroids[nearest]));
            }
        }
        List<Assignment> assignments = new ArrayList<Assignment>(curves.size());
        for (int i = 0; i < curves.size(); i++) {
            assignments.add(new Assignment(curves.get(i), classes[i], distances[i]));
        }
        return assignments;
    }

    /** Object-weighted class-mean curves, grouped under the partner with one curve per class. */
    public static ProfileAggregator classCurves(List<Assignment> assignments, Family family,
                                                OipParameters.CancellationToken cancellation) {
        // Partners in first-seen order, then classes in number order, so tables and figures list
        // Class 1, Class 2, ... regardless of which class the first object fell into.
        Map<String, Integer> maxClass = new LinkedHashMap<String, Integer>();
        for (Assignment assignment : assignments) {
            Integer previous = maxClass.get(assignment.curve.partner);
            if (previous == null || assignment.classLabel > previous) {
                maxClass.put(assignment.curve.partner, assignment.classLabel);
            }
        }
        ProfileAggregator aggregate = new ProfileAggregator();
        for (Map.Entry<String, Integer> partner : maxClass.entrySet()) {
            for (int c = 0; c <= partner.getValue(); c++) {
                for (Assignment assignment : assignments) {
                    if (assignment.classLabel != c
                            || !assignment.curve.partner.equals(partner.getKey())) continue;
                    aggregate.add(partner.getKey(), className(c), family.profileType,
                            GROUP, assignment.curve.values, cancellation);
                }
            }
        }
        return aggregate;
    }

    /** One-based display name of a zero-based class index. */
    public static String className(int classLabel) {
        return "Class " + (classLabel + 1);
    }

    private static boolean usable(double[] values) {
        if (values == null || values.length == 0) return false;
        for (double value : values) {
            if (!Double.isFinite(value)) return false;
        }
        return true;
    }

    private static void checkCancelled(OipParameters.CancellationToken cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ObjectIntensityProfiling.AnalysisCancelledException();
        }
    }
}
