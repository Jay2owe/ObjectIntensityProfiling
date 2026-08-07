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

import oip.profile.OipConfig;
import oip.texture.QuantizationRange;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable folder-batch input bundle.
 *
 * <p>Capture group 1 in each regular expression is the sample key used to pair one label image
 * with its raw channels. If a pattern has no capture groups, the filename without its extension is
 * used instead.</p>
 */
public final class OipBatchParameters {
    private final File labelFolder;
    private final String labelRegex;
    private final List<RawSpec> rawSpecs;
    private final String referenceChannel;
    private final boolean recursive;
    private final OipConfig config;
    private final File outputDirectory;
    private final boolean saveFigures;
    private final boolean saveClassMaps;
    private final Map<String, QuantizationRange> quantizationRanges;
    private final OipParameters.ProgressListener progressListener;
    private final OipParameters.CancellationToken cancellationToken;

    private OipBatchParameters(Builder builder) {
        labelFolder = builder.labelFolder;
        labelRegex = builder.labelRegex;
        rawSpecs = Collections.unmodifiableList(new ArrayList<RawSpec>(builder.rawSpecs));
        String requestedReference = nonBlankExact(builder.referenceChannel);
        referenceChannel = requestedReference == null && rawSpecs.size() == 1
                ? rawSpecs.get(0).channelName : requestedReference;
        recursive = builder.recursive;
        config = builder.config == null ? new OipConfig() : builder.config.copy();
        outputDirectory = builder.outputDirectory;
        saveFigures = builder.saveFigures;
        saveClassMaps = builder.saveClassMaps;
        quantizationRanges = Collections.unmodifiableMap(
                new LinkedHashMap<String, QuantizationRange>(builder.quantizationRanges));
        progressListener = builder.progressListener;
        cancellationToken = builder.cancellationToken;
    }

    public static Builder builder(File labelFolder, String labelRegex, File outputDirectory) {
        return new Builder(labelFolder, labelRegex, outputDirectory);
    }

    public File getLabelFolder() {
        return labelFolder;
    }

    public String getLabelRegex() {
        return labelRegex;
    }

    public List<RawSpec> getRawSpecs() {
        return rawSpecs;
    }

    public String getReferenceChannel() {
        return referenceChannel;
    }

    public boolean isRecursive() {
        return recursive;
    }

    public OipConfig getConfig() {
        return config.copy();
    }

    public File getOutputDirectory() {
        return outputDirectory;
    }

    public boolean isSaveFigures() {
        return saveFigures;
    }

    public boolean isSaveClassMaps() {
        return saveClassMaps;
    }

    public Map<String, QuantizationRange> getQuantizationRanges() {
        return quantizationRanges;
    }

    public OipParameters.ProgressListener getProgressListener() {
        return progressListener;
    }

    public OipParameters.CancellationToken getCancellationToken() {
        return cancellationToken;
    }

    public static final class RawSpec {
        public final String channelName;
        public final File folder;
        public final String regex;

        private RawSpec(String channelName, File folder, String regex) {
            this.channelName = channelName;
            this.folder = folder;
            this.regex = regex;
        }
    }

    public static final class Builder {
        private final File labelFolder;
        private final String labelRegex;
        private final File outputDirectory;
        private final List<RawSpec> rawSpecs = new ArrayList<RawSpec>();
        private String referenceChannel;
        private boolean recursive = true;
        private OipConfig config = new OipConfig();
        private boolean saveFigures = true;
        private boolean saveClassMaps = true;
        private final Map<String, QuantizationRange> quantizationRanges =
                new LinkedHashMap<String, QuantizationRange>();
        private OipParameters.ProgressListener progressListener;
        private OipParameters.CancellationToken cancellationToken;

        private Builder(File labelFolder, String labelRegex, File outputDirectory) {
            this.labelFolder = labelFolder;
            this.labelRegex = labelRegex;
            this.outputDirectory = outputDirectory;
        }

        public Builder addRawChannel(String name, File folder, String regex) {
            rawSpecs.add(new RawSpec(name, folder, regex));
            return this;
        }

        public Builder referenceChannel(String referenceChannel) {
            this.referenceChannel = referenceChannel;
            return this;
        }

        public Builder recursive(boolean recursive) {
            this.recursive = recursive;
            return this;
        }

        public Builder config(OipConfig config) {
            this.config = config;
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

        public Builder progressListener(OipParameters.ProgressListener progressListener) {
            this.progressListener = progressListener;
            return this;
        }

        public Builder cancellationToken(OipParameters.CancellationToken cancellationToken) {
            this.cancellationToken = cancellationToken;
            return this;
        }

        public OipBatchParameters build() {
            return new OipBatchParameters(this);
        }

        /** Override the otherwise automatic fixed batch range for one raw channel. */
        public Builder quantizationRange(String channelName, QuantizationRange range) {
            if (channelName == null || range == null) {
                throw new IllegalArgumentException("Channel name and quantisation range are required.");
            }
            quantizationRanges.put(channelName, range);
            return this;
        }
    }

    private static String nonBlankExact(String text) {
        if (text == null || text.trim().length() == 0) return null;
        return text;
    }
}
