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

import java.util.ArrayList;
import java.util.List;

/** ImageJ macro options for a complete folder-batch run. */
final class OipBatchMacroOptions {
    String labelFolder;
    String labelRegex;
    final String[] rawNames = new String[4];
    final String[] rawFolders = new String[4];
    final String[] rawRegexes = new String[4];
    final Double[] quantMin = new Double[4];
    final Double[] quantMax = new Double[4];
    String referenceChannel;
    String outputDirectory;
    boolean recursive = true;
    boolean saveFigures = true;
    boolean saveClassMaps = true;
    boolean hideDisplay;
    final OipConfig config = new OipConfig();

    String toMacroOptions() {
        validateQuantizationSlots();
        List<String> tokens = new ArrayList<String>();
        tokens.add("batch");
        OipMacroOptions.append(tokens, "labels_folder", labelFolder);
        OipMacroOptions.append(tokens, "labels_regex", labelRegex);
        for (int i = 0; i < 4; i++) {
            int slot = i + 1;
            if (!OipMacroOptions.hasText(rawNames[i])
                    && !OipMacroOptions.hasText(rawRegexes[i])) continue;
            OipMacroOptions.append(tokens, "raw" + slot + "_name", rawNames[i]);
            OipMacroOptions.append(tokens, "raw" + slot + "_folder", rawFolders[i]);
            OipMacroOptions.append(tokens, "raw" + slot + "_regex", rawRegexes[i]);
            if (quantMin[i] != null) tokens.add("quant_min" + slot + "=" + quantMin[i]);
            if (quantMax[i] != null) tokens.add("quant_max" + slot + "=" + quantMax[i]);
        }
        OipMacroOptions.append(tokens, "reference", referenceChannel);
        OipMacroOptions.append(tokens, "output", outputDirectory);
        tokens.add(recursive ? "recursive" : "no_recursive");
        tokens.add(config.doRadial ? "radial" : "no_radial");
        tokens.add(config.doMarginal ? "marginal" : "no_marginal");
        tokens.add(config.doPrincipalAxis ? "principal" : "no_principal");
        tokens.add(config.doAngular ? "angular" : "no_angular");
        tokens.add(config.doShell ? "shell" : "no_shell");
        tokens.add(config.doWithinBox ? "correlation" : "no_correlation");
        tokens.add(config.region == OipConfig.Region.OBJECT_VOXELS ? "mask" : "box");
        tokens.add("intensity_norm=" + OipMacroOptions.intensityNorm(config.intensityNorm));
        tokens.add(config.doGlcm ? "glcm" : "no_glcm");
        tokens.add(config.doTextureClasses ? "texture_classes" : "no_texture_classes");
        tokens.add("radial_bins=" + config.radialBins);
        tokens.add("curve_bins=" + config.resampleN);
        tokens.add("angular_bins=" + config.angularBins);
        tokens.add("shells=" + config.shells);
        tokens.add("padding=" + config.boxPadPct);
        tokens.add("ring_threshold=" + config.ringThresholdPct);
        tokens.add("reference_threshold=" + config.referenceThreshold);
        tokens.add("partner_threshold=" + config.partnerThreshold);
        tokens.add("glcm_levels=" + config.glcmLevels);
        tokens.add("glcm_distance=" + config.glcmDistance);
        tokens.add("texture_k=" + config.textureClasses);
        tokens.add("minimum_texture_voxels=" + config.minimumTextureVoxels);
        tokens.add(saveFigures ? "save_figures" : "no_figures");
        tokens.add(saveClassMaps ? "save_maps" : "no_maps");
        if (hideDisplay) tokens.add("hide_display");
        return OipMacroOptions.join(tokens);
    }

    QuantizationRange range(int index) {
        if (quantMin[index] == null && quantMax[index] == null) return null;
        if (quantMin[index] == null || quantMax[index] == null) {
            throw new IllegalArgumentException("Both quant_min" + (index + 1)
                    + " and quant_max" + (index + 1) + " are required.");
        }
        return new QuantizationRange(quantMin[index], quantMax[index]);
    }

    void validateQuantizationSlots() {
        for (int i = 0; i < rawNames.length; i++) {
            QuantizationRange range = range(i);
            if (range != null && (!OipMacroOptions.hasText(rawNames[i])
                    || !OipMacroOptions.hasText(rawFolders[i])
                    || !OipMacroOptions.hasText(rawRegexes[i]))) {
                throw new IllegalArgumentException("quant_min" + (i + 1)
                        + " and quant_max" + (i + 1)
                        + " require a configured batch raw slot " + (i + 1) + ".");
            }
        }
    }
}
