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
import oip.profile.OipConfig;
import oip.texture.QuantizationRange;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable input bundle for the public Object Intensity Profiling Java API.
 */
public final class OipParameters {

    public interface ProgressListener {
        void onProgress(double fraction, String message);
    }

    public interface CancellationToken {
        boolean isCancelled();
    }

    private static final ProgressListener NO_PROGRESS = new ProgressListener() {
        @Override
        public void onProgress(double fraction, String message) {
        }
    };
    private static final CancellationToken NEVER_CANCELLED = new CancellationToken() {
        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    private final ImagePlus labelImage;
    private final Map<String, ImagePlus> rawImages;
    private final String sourceName;
    private final String referenceChannel;
    private final OipConfig config;
    private final Map<String, QuantizationRange> quantizationRanges;
    private final String groupKey;
    private final boolean autoSave;
    private final boolean saveFigures;
    private final boolean saveClassMaps;
    private final File outputDirectory;
    private final ProgressListener progressListener;
    private final CancellationToken cancellationToken;

    private OipParameters(Builder builder) {
        labelImage = builder.labelImage;
        rawImages = Collections.unmodifiableMap(
                new LinkedHashMap<String, ImagePlus>(builder.rawImages));
        sourceName = clean(builder.sourceName) == null && labelImage != null
                ? labelImage.getTitle() : clean(builder.sourceName);
        String requestedReference = nonBlankExact(builder.referenceChannel);
        referenceChannel = requestedReference == null && rawImages.size() == 1
                ? rawImages.keySet().iterator().next() : requestedReference;
        config = builder.config == null ? new OipConfig() : builder.config.copy();
        quantizationRanges = Collections.unmodifiableMap(
                new LinkedHashMap<String, QuantizationRange>(builder.quantizationRanges));
        groupKey = clean(builder.groupKey) == null ? "(all)" : clean(builder.groupKey);
        autoSave = builder.autoSave;
        saveFigures = builder.saveFigures;
        saveClassMaps = builder.saveClassMaps;
        outputDirectory = builder.outputDirectory;
        progressListener = builder.progressListener == null ? NO_PROGRESS : builder.progressListener;
        cancellationToken = builder.cancellationToken == null ? NEVER_CANCELLED : builder.cancellationToken;
    }

    public static Builder builder(ImagePlus labelImage) {
        return new Builder(labelImage);
    }

    /**
     * Create a builder containing the same settings, suitable for changing a
     * small number of immutable options.
     */
    public Builder toBuilder() {
        Builder builder = new Builder(labelImage);
        builder.rawImages.putAll(rawImages);
        builder.sourceName = sourceName;
        builder.referenceChannel = referenceChannel;
        builder.config = config.copy();
        builder.quantizationRanges.putAll(quantizationRanges);
        builder.groupKey = groupKey;
        builder.autoSave = autoSave;
        builder.saveFigures = saveFigures;
        builder.saveClassMaps = saveClassMaps;
        builder.outputDirectory = outputDirectory;
        builder.progressListener = progressListener;
        builder.cancellationToken = cancellationToken;
        return builder;
    }

    public ImagePlus getLabelImage() {
        return labelImage;
    }

    public Map<String, ImagePlus> getRawImages() {
        return rawImages;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getReferenceChannel() {
        return referenceChannel;
    }

    public OipConfig getConfig() {
        return config.copy();
    }

    public Map<String, QuantizationRange> getQuantizationRanges() {
        return quantizationRanges;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public boolean isAutoSave() {
        return autoSave;
    }

    public boolean isSaveFigures() {
        return saveFigures;
    }

    public boolean isSaveClassMaps() {
        return saveClassMaps;
    }

    public File getOutputDirectory() {
        return outputDirectory;
    }

    public ProgressListener getProgressListener() {
        return progressListener;
    }

    public CancellationToken getCancellationToken() {
        return cancellationToken;
    }

    public static final class Builder {
        private final ImagePlus labelImage;
        private final Map<String, ImagePlus> rawImages =
                new LinkedHashMap<String, ImagePlus>();
        private String sourceName;
        private String referenceChannel;
        private OipConfig config = new OipConfig();
        private final Map<String, QuantizationRange> quantizationRanges =
                new LinkedHashMap<String, QuantizationRange>();
        private String groupKey = "(all)";
        private boolean autoSave;
        private boolean saveFigures = true;
        private boolean saveClassMaps = true;
        private File outputDirectory;
        private ProgressListener progressListener;
        private CancellationToken cancellationToken;

        private Builder(ImagePlus labelImage) {
            this.labelImage = labelImage;
        }

        public Builder addRawImage(String channelName, ImagePlus image) {
            String name = nonBlankExact(channelName);
            if (name == null) throw new IllegalArgumentException("Raw channel name must not be empty.");
            rawImages.put(name, image);
            return this;
        }

        public Builder rawImages(Map<String, ImagePlus> images) {
            rawImages.clear();
            if (images != null) rawImages.putAll(images);
            return this;
        }

        public Builder sourceName(String sourceName) {
            this.sourceName = sourceName;
            return this;
        }

        public Builder referenceChannel(String referenceChannel) {
            this.referenceChannel = referenceChannel;
            return this;
        }

        public Builder config(OipConfig config) {
            this.config = config;
            return this;
        }

        public Builder quantizationRange(String channelName, QuantizationRange range) {
            quantizationRanges.put(channelName, range);
            return this;
        }

        public Builder groupKey(String groupKey) {
            this.groupKey = groupKey;
            return this;
        }

        public Builder autoSave(File outputDirectory) {
            this.autoSave = true;
            this.outputDirectory = outputDirectory;
            return this;
        }

        public Builder autoSave(boolean autoSave) {
            this.autoSave = autoSave;
            return this;
        }

        public Builder saveFigures(boolean saveFigures) {
            this.saveFigures = saveFigures;
            return this;
        }

        public Builder saveClassMaps(boolean saveClassMaps) {
            this.saveClassMaps = saveClassMaps;
            return this;
        }

        public Builder progressListener(ProgressListener progressListener) {
            this.progressListener = progressListener;
            return this;
        }

        public Builder cancellationToken(CancellationToken cancellationToken) {
            this.cancellationToken = cancellationToken;
            return this;
        }

        public OipParameters build() {
            return new OipParameters(this);
        }
    }

    private static String clean(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        return trimmed.length() == 0 ? null : trimmed;
    }

    private static String nonBlankExact(String text) {
        if (text == null || text.trim().length() == 0) return null;
        return text;
    }
}
