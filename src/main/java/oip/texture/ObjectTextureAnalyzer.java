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

import ij.ImagePlus;
import oip.ObjectIntensityProfiling;
import oip.OipParameters;
import oip.ParallelTasks;
import oip.profile.LabelObjects.ObjectInfo;
import oip.profile.OipConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs mask-restricted 2D texture measurements for each object and raw channel.
 */
public final class ObjectTextureAnalyzer {

    private ObjectTextureAnalyzer() {
    }

    public static List<ObjectTextureResult> analyze(
            ImagePlus labels,
            Map<String, ImagePlus> rawByChannel,
            List<ObjectInfo> objects,
            Map<String, QuantizationRange> ranges,
            OipConfig config) {
        return analyze(labels, rawByChannel, objects, ranges, config, null, null);
    }

    public static List<ObjectTextureResult> analyze(
            ImagePlus labels,
            Map<String, ImagePlus> rawByChannel,
            List<ObjectInfo> objects,
            Map<String, QuantizationRange> ranges,
            OipConfig config,
            OipParameters.CancellationToken cancellation,
            OipParameters.ProgressListener progress) {
        return analyze(labels, rawByChannel, objects, ranges, config, true,
                cancellation, progress);
    }

    /**
     * Compute texture measurements, optionally deferring class fitting so a
     * batch caller can fit one shared model after all samples are measured.
     *
     * <p>{@code ranges} may be null or incomplete, but any channel it does not cover falls back to
     * scanning that one image — and a range derived from a single image is <strong>not comparable
     * with any other image</strong>. Supply a range spanning the whole batch for every channel
     * whenever GLCM features are to be compared across images.
     */
    public static List<ObjectTextureResult> analyze(
            ImagePlus labels,
            Map<String, ImagePlus> rawByChannel,
            List<ObjectInfo> objects,
            Map<String, QuantizationRange> ranges,
            OipConfig config,
            boolean assignTextureClasses,
            OipParameters.CancellationToken cancellation,
            OipParameters.ProgressListener progress) {
        List<ObjectTextureResult> results = new ArrayList<ObjectTextureResult>();
        if (labels == null || rawByChannel == null || objects == null || config == null) return results;

        final List<TextureJob> jobs = new ArrayList<TextureJob>(
                rawByChannel.size() * objects.size());
        for (Map.Entry<String, ImagePlus> partner : rawByChannel.entrySet()) {
            QuantizationRange range = null;
            if (config.doGlcm) {
                range = ranges == null ? null : ranges.get(partner.getKey());
                if (range == null) {
                    range = QuantizationRange.scan(partner.getValue(), cancellation, null);
                }
            }
            for (ObjectInfo object : objects) {
                jobs.add(new TextureJob(
                        partner.getKey(), partner.getValue(), object, range));
            }
        }
        final int total = jobs.size();
        if (total > 0) {
            results.addAll(ParallelTasks.mapOrdered(
                    total,
                    cancellation,
                    new ParallelTasks.Task<ObjectTextureResult>() {
                        @Override
                        public ObjectTextureResult run(int index) {
                            return analyzeOne(
                                    labels, jobs.get(index), config, cancellation);
                        }
                    },
                    new ParallelTasks.CompletionListener() {
                        @Override
                        public void completed(int index) {
                            update(progress, index + 1, total);
                        }
                    }));
        }
        if (config.doTextureClasses && assignTextureClasses) {
            assignClasses(results, config.textureClasses, cancellation);
        }
        return results;
    }

    private static ObjectTextureResult analyzeOne(
            ImagePlus labels,
            TextureJob job,
            OipConfig config,
            OipParameters.CancellationToken cancellation) {
        ObjectInfo object = job.object;
        boolean suppressed = object.voxelCount < config.minimumTextureVoxels;
        ObjectTextureResult result = new ObjectTextureResult(
                object.label, object.voxelCount, job.partnerName, suppressed);
        if (suppressed) return result;

        if (config.doGlcm) {
            GlcmAccumulator acc = new GlcmAccumulator();
            for (int z = object.zmin; z <= object.zmax; z++) {
                ObjectPatch patch = ObjectPatchBuilder.buildSlice(
                        object, labels, job.partnerImage, z, config.boxPadPct,
                        cancellation);
                ObjectTextureGLCM.Result slice = ObjectTextureGLCM.compute(
                        patch, config.glcmLevels, config.glcmDistance,
                        job.range.min, job.range.max, cancellation);
                acc.add(slice);
            }
            acc.finish(result);
        }
        if (config.doTextureClasses) {
            ObjectPatch patch = ObjectPatchBuilder.buildMIP(
                    object, labels, job.partnerImage, config.boxPadPct, cancellation);
            result.featureVector = ObjectTextureFeatures.computeFeatures(patch, cancellation);
        }
        return result;
    }

    private static final class TextureJob {
        final String partnerName;
        final ImagePlus partnerImage;
        final ObjectInfo object;
        final QuantizationRange range;

        TextureJob(String partnerName, ImagePlus partnerImage,
                   ObjectInfo object, QuantizationRange range) {
            this.partnerName = partnerName;
            this.partnerImage = partnerImage;
            this.object = object;
            this.range = range;
        }
    }

    private static void update(OipParameters.ProgressListener progress, int completed, int total) {
        if (progress != null) {
            progress.onProgress((double) completed / total,
                    "Texture object " + completed + " of " + total);
        }
    }

    /**
     * Fits classes independently per raw channel. Calling this on combined batch results gives
     * object-weighted batch clustering rather than fitting each image separately.
     */
    public static void assignClasses(List<ObjectTextureResult> results, int requestedK) {
        assignClasses(results, requestedK, null);
    }

    public static void assignClasses(List<ObjectTextureResult> results, int requestedK,
                                     OipParameters.CancellationToken cancellation) {
        Map<String, List<ObjectTextureResult>> byPartner =
                new LinkedHashMap<String, List<ObjectTextureResult>>();
        for (ObjectTextureResult result : results) {
            checkCancelled(cancellation);
            if (result.featureVector == null || !result.featureVector.valid
                    || !result.featureVector.reliable
                    || !finite(result.featureVector.features)) continue;
            List<ObjectTextureResult> group = byPartner.get(result.partnerChannel);
            if (group == null) {
                group = new ArrayList<ObjectTextureResult>();
                byPartner.put(result.partnerChannel, group);
            }
            group.add(result);
        }
        for (List<ObjectTextureResult> group : byPartner.values()) {
            checkCancelled(cancellation);
            int dim = group.get(0).featureVector.features.length;
            for (ObjectTextureResult result : group) {
                if (result.featureVector.features.length != dim) {
                    throw new IllegalArgumentException("feature dimensionality mismatch");
                }
            }
            // The eight features carry two different units — Gabor responses are in intensity
            // units, wavelet energies in squared intensity units — so on 16-bit data the wavelet
            // scales are ~1e4 times larger and raw Euclidean distance is decided almost entirely
            // by one of them. Standardise each dimension over the population being fitted so all
            // eight features actually contribute to the clustering.
            double[] centre = new double[dim];
            double[] scale = new double[dim];
            standardization(group, dim, centre, scale, cancellation);

            List<ObjectTextureFeatures.FeatureVector> vectors =
                    new ArrayList<ObjectTextureFeatures.FeatureVector>();
            for (ObjectTextureResult result : group) {
                vectors.add(standardize(result.featureVector, centre, scale));
            }
            int k = Math.max(1, Math.min(requestedK, vectors.size()));
            double[][] centroids = ObjectTextureFeatures.fitCentroids(vectors, k, cancellation);
            for (int i = 0; i < group.size(); i++) {
                checkCancelled(cancellation);
                ObjectTextureFeatures.ClassAssignment assignment =
                        ObjectTextureFeatures.assignToCentroids(vectors.get(i), centroids);
                group.get(i).classLabel = assignment.classLabel;
                group.get(i).classDistance = assignment.classDistance;
            }
        }
    }

    /** Per-dimension mean and population standard deviation; a constant dimension gets scale 1. */
    private static void standardization(List<ObjectTextureResult> group, int dim,
                                        double[] centre, double[] scale,
                                        OipParameters.CancellationToken cancellation) {
        for (ObjectTextureResult result : group) {
            checkCancelled(cancellation);
            for (int d = 0; d < dim; d++) centre[d] += result.featureVector.features[d];
        }
        for (int d = 0; d < dim; d++) centre[d] /= group.size();
        for (ObjectTextureResult result : group) {
            checkCancelled(cancellation);
            for (int d = 0; d < dim; d++) {
                double delta = result.featureVector.features[d] - centre[d];
                scale[d] += delta * delta;
            }
        }
        for (int d = 0; d < dim; d++) {
            double sd = Math.sqrt(scale[d] / group.size());
            scale[d] = sd > 0.0 && !Double.isInfinite(sd) && !Double.isNaN(sd) ? sd : 1.0;
        }
    }

    private static ObjectTextureFeatures.FeatureVector standardize(
            ObjectTextureFeatures.FeatureVector vector, double[] centre, double[] scale) {
        float[] standardized = new float[centre.length];
        for (int d = 0; d < centre.length; d++) {
            standardized[d] = (float) ((vector.features[d] - centre[d]) / scale[d]);
        }
        return new ObjectTextureFeatures.FeatureVector(
                standardized, vector.valid, vector.reliable);
    }

    private static boolean finite(float[] values) {
        if (values == null || values.length == 0) return false;
        for (float value : values) {
            if (!Float.isFinite(value)) return false;
        }
        return true;
    }

    private static void checkCancelled(OipParameters.CancellationToken cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ObjectIntensityProfiling.AnalysisCancelledException();
        }
    }

    private static final class GlcmAccumulator {
        double contrast;
        double asm;
        double correlation;
        double entropy;
        double homogeneity;
        int slices;
        int correlatedSlices;
        int pairs;
        boolean reliable = true;

        void add(ObjectTextureGLCM.Result result) {
            if (result == null || !result.valid) return;
            contrast += result.contrast;
            asm += result.asm;
            entropy += result.entropy;
            homogeneity += result.homogeneity;
            if (Double.isFinite(result.correlation)) {
                correlation += result.correlation;
                correlatedSlices++;
            } else {
                reliable = false;
            }
            reliable &= result.reliable;
            pairs += result.coOccurrencePairs;
            slices++;
        }

        void finish(ObjectTextureResult target) {
            if (slices == 0) return;
            target.contrast = contrast / slices;
            target.asm = asm / slices;
            // Average the slices that define a correlation and flag the object unreliable,
            // rather than discarding a measurement that most slices supported. This matches
            // ObjectTextureGLCM's per-direction policy; the two layers must agree, or the
            // per-direction average is silently thrown away here.
            target.correlation = correlatedSlices > 0
                    ? correlation / correlatedSlices : Double.NaN;
            target.entropy = entropy / slices;
            target.homogeneity = homogeneity / slices;
            target.coOccurrencePairs = pairs;
            target.glcmReliable = reliable;
        }
    }
}
