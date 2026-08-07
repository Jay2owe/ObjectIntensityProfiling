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
import oip.profile.ObjectProfileResult;
import oip.profile.ProfileAggregator;
import oip.texture.ObjectTextureResult;
import oip.texture.QuantizationRange;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Output bundle returned by the public Java API.
 */
public final class OipResult {
    private final OipParameters parameters;
    private final List<ObjectProfileResult> profiles;
    private final List<ObjectTextureResult> textures;
    private final ProfileAggregator aggregate;
    private final Map<String, QuantizationRange> quantizationRanges;
    private final Map<String, ImagePlus> classMaps;
    private File outputDirectory;

    OipResult(OipParameters parameters,
              List<ObjectProfileResult> profiles,
              List<ObjectTextureResult> textures,
              ProfileAggregator aggregate,
              Map<String, QuantizationRange> quantizationRanges,
              Map<String, ImagePlus> classMaps) {
        this.parameters = parameters;
        this.profiles = Collections.unmodifiableList(
                new ArrayList<ObjectProfileResult>(profiles));
        this.textures = Collections.unmodifiableList(
                new ArrayList<ObjectTextureResult>(textures));
        this.aggregate = aggregate;
        this.quantizationRanges = Collections.unmodifiableMap(
                new LinkedHashMap<String, QuantizationRange>(quantizationRanges));
        this.classMaps = Collections.unmodifiableMap(
                new LinkedHashMap<String, ImagePlus>(classMaps));
        this.outputDirectory = parameters.isAutoSave() ? parameters.getOutputDirectory() : null;
    }

    public OipParameters getParameters() {
        return parameters;
    }

    public List<ObjectProfileResult> getProfiles() {
        return profiles;
    }

    public List<ObjectTextureResult> getTextures() {
        return textures;
    }

    public List<ProfileAggregator.AggregatedProfile> getAggregatedProfiles() {
        return aggregate.results();
    }

    public Map<String, QuantizationRange> getQuantizationRanges() {
        return quantizationRanges;
    }

    public Map<String, ImagePlus> getClassMaps() {
        return classMaps;
    }

    public File getOutputDirectory() {
        return outputDirectory;
    }

}
