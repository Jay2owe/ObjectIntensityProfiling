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
package oip;

import ij.ImagePlus;
import oip.io.FileNameSupport;
import oip.io.OipOutputWriter;
import oip.profile.LabelObjects;
import oip.profile.LabelObjects.ObjectInfo;
import oip.profile.ObjectIntensityProfiler;
import oip.profile.ObjectProfileResult;
import oip.profile.OipConfig;
import oip.profile.ProfileAggregator;
import oip.texture.ObjectTextureAnalyzer;
import oip.texture.ObjectTextureResult;
import oip.texture.QuantizationRange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public, headless Java facade for Object Intensity Profiling.
 */
public final class ObjectIntensityProfiling {

    private ObjectIntensityProfiling() {
    }

    public static OipResult run(ImagePlus labels, Map<String, ImagePlus> rawImages) {
        return run(OipParameters.builder(labels).rawImages(rawImages).build());
    }

    public static OipResult run(ImagePlus labels, Map<String, ImagePlus> rawImages,
                                String referenceChannel) {
        return run(OipParameters.builder(labels)
                .rawImages(rawImages)
                .referenceChannel(referenceChannel)
                .build());
    }

    public static OipResult run(OipParameters parameters) {
        return run(parameters, true);
    }

    static OipResult runWithDeferredTextureClasses(OipParameters parameters) {
        return run(parameters, false);
    }

    private static OipResult run(OipParameters parameters, boolean assignTextureClasses) {
        validate(parameters);
        OipConfig config = parameters.getConfig();
        progress(parameters, 0.02, "Extracting objects");
        List<ObjectInfo> objects = LabelObjects.extract(
                parameters.getLabelImage(), parameters.getCancellationToken());
        if (objects.isEmpty()) {
            throw new IllegalArgumentException("The label image contains no positive object labels.");
        }
        checkCancelled(parameters);

        progress(parameters, 0.12, "Computing intensity profiles");
        List<ObjectProfileResult> profiles = config.anyProfileEnabled()
                ? ObjectIntensityProfiler.profile(
                        parameters.getLabelImage(),
                        parameters.getRawImages(),
                        objects,
                        parameters.getSourceName(),
                        parameters.getReferenceChannel(),
                        parameters.getLabelImage().getCalibration(),
                        config,
                        parameters.getCancellationToken(),
                        scaled(parameters, 0.12, 0.50))
                : new ArrayList<ObjectProfileResult>();
        checkCancelled(parameters);

        Map<String, QuantizationRange> ranges =
                new LinkedHashMap<String, QuantizationRange>();
        if (config.doGlcm) {
            progress(parameters, 0.52, "Determining fixed texture ranges");
            for (Map.Entry<String, ImagePlus> entry : parameters.getRawImages().entrySet()) {
                QuantizationRange supplied = parameters.getQuantizationRanges().get(entry.getKey());
                ranges.put(entry.getKey(), supplied == null
                        ? QuantizationRange.scan(entry.getValue(),
                                parameters.getCancellationToken(),
                                scaled(parameters, 0.52, 0.60))
                        : supplied);
            }
        }

        List<ObjectTextureResult> textures = new ArrayList<ObjectTextureResult>();
        if (config.doGlcm || config.doTextureClasses) {
            progress(parameters, 0.60, "Computing texture measurements");
            textures = ObjectTextureAnalyzer.analyze(
                    parameters.getLabelImage(), parameters.getRawImages(),
                    objects, ranges, config,
                    assignTextureClasses,
                    parameters.getCancellationToken(),
                    scaled(parameters, 0.60, 0.86));
        }
        checkCancelled(parameters);

        ProfileAggregator aggregate = new ProfileAggregator();
        for (ObjectProfileResult result : profiles) {
            checkCancelled(parameters);
            aggregate.addAll(result, parameters.getGroupKey(),
                    parameters.getCancellationToken());
        }
        Map<String, ImagePlus> classMaps = parameters.isSaveClassMaps()
                ? TextureClassMapRenderer.render(parameters.getLabelImage(), textures,
                        parameters.getCancellationToken())
                : new LinkedHashMap<String, ImagePlus>();

        OipResult result = new OipResult(
                parameters, profiles, textures, aggregate, ranges, classMaps);
        if (parameters.isAutoSave()) {
            progress(parameters, 0.90, "Saving results");
            try {
                OipOutputWriter.saveTransactional(result, parameters.getOutputDirectory(),
                        parameters.getCancellationToken());
            } catch (IOException e) {
                throw new IllegalStateException("Could not save Object Intensity Profiling results.", e);
            }
        }
        progress(parameters, 1.0, "Complete");
        return result;
    }

    private static void validate(OipParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("Parameters must not be null.");
        }
        ImagePlus labels = parameters.getLabelImage();
        if (labels == null || labels.getStack() == null || labels.getStackSize() == 0) {
            throw new IllegalArgumentException("A label image is required.");
        }
        if (labels.getNChannels() != 1 || labels.getNFrames() != 1) {
            throw new IllegalArgumentException(
                    "The label input must be a single-channel, single-timepoint image.");
        }
        if (parameters.getRawImages().isEmpty() || parameters.getRawImages().size() > 4) {
            throw new IllegalArgumentException("Provide between 1 and 4 raw intensity images.");
        }
        for (String channel : parameters.getRawImages().keySet()) {
            if (channel == null || channel.trim().length() == 0) {
                throw new IllegalArgumentException("Raw channel names must not be empty.");
            }
        }
        FileNameSupport.requireUniqueEncoded(
                parameters.getRawImages().keySet(), "Raw channel");
        if (parameters.getReferenceChannel() == null) {
            throw new IllegalArgumentException(
                    "Choose an explicit correlation reference when using multiple raw channels.");
        }
        if (!parameters.getRawImages().containsKey(parameters.getReferenceChannel())) {
            throw new IllegalArgumentException(
                    "Correlation reference is not one of the supplied raw channels: "
                            + parameters.getReferenceChannel());
        }
        for (Map.Entry<String, ImagePlus> entry : parameters.getRawImages().entrySet()) {
            ImagePlus raw = entry.getValue();
            if (raw == null || raw.getStack() == null || raw.getStackSize() == 0) {
                throw new IllegalArgumentException("Raw image is missing for channel: " + entry.getKey());
            }
            if (raw.getNChannels() != 1 || raw.getNFrames() != 1) {
                throw new IllegalArgumentException("Raw inputs must be single-channel, single-timepoint images: "
                        + entry.getKey());
            }
            if (raw.getWidth() != labels.getWidth()
                    || raw.getHeight() != labels.getHeight()
                    || raw.getNSlices() != labels.getNSlices()) {
                throw new IllegalArgumentException("Raw image dimensions do not match the label image: "
                        + entry.getKey());
            }
        }
        for (String channel : parameters.getQuantizationRanges().keySet()) {
            if (!parameters.getRawImages().containsKey(channel)) {
                throw new IllegalArgumentException(
                        "Quantisation range is not for a supplied raw channel: " + channel);
            }
        }
        OipConfig config = parameters.getConfig();
        if (config.radialBins < 1 || config.angularBins < 1 || config.shells < 1
                || config.resampleN < 1 || config.glcmLevels < 2 || config.glcmDistance < 1
                || config.textureClasses < 1 || config.minimumTextureVoxels < 1
                || config.boxPadPct < 0) {
            throw new IllegalArgumentException("Profile, texture, and padding values must be positive.");
        }
        if (config.glcmLevels > 256 || config.textureClasses > 255
                || config.radialBins > 10000 || config.angularBins > 10000
                || config.resampleN > 10000 || config.shells > 10000) {
            throw new IllegalArgumentException("Requested bin, grey-level, or class count is too large.");
        }
        if (!Double.isFinite(config.boxPadPct)
                || !Double.isFinite(config.ringThresholdPct)
                || config.ringThresholdPct < 0 || config.ringThresholdPct > 100
                || !Double.isFinite(config.referenceThreshold)
                || !Double.isFinite(config.partnerThreshold)) {
            throw new IllegalArgumentException("Padding and threshold values must be finite and valid.");
        }
        if (config.region == null || config.intensityNorm == null) {
            throw new IllegalArgumentException("Sampling region and intensity normalisation are required.");
        }
        if (!config.anyProfileEnabled() && !config.doGlcm && !config.doTextureClasses) {
            throw new IllegalArgumentException("Enable at least one profile or texture measurement.");
        }
        if (parameters.isAutoSave() && parameters.getOutputDirectory() == null) {
            throw new IllegalArgumentException("An output directory is required when auto-save is enabled.");
        }
    }

    private static void progress(OipParameters parameters, double fraction, String message) {
        parameters.getProgressListener().onProgress(fraction, message);
    }

    private static void checkCancelled(OipParameters parameters) {
        if (parameters.getCancellationToken().isCancelled()) {
            throw new AnalysisCancelledException();
        }
    }

    private static OipParameters.ProgressListener scaled(
            final OipParameters parameters, final double start, final double end) {
        return new OipParameters.ProgressListener() {
            @Override
            public void onProgress(double fraction, String message) {
                progress(parameters, start + Math.max(0.0, Math.min(1.0, fraction)) * (end - start),
                        message);
            }
        };
    }

    public static final class AnalysisCancelledException extends RuntimeException {
        public AnalysisCancelledException() {
            super("Object Intensity Profiling was cancelled.");
        }
    }
}
