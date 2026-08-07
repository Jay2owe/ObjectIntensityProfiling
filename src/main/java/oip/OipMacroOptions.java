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

/**
 * Macro-facing options independent of ImageJ windows.
 */
final class OipMacroOptions {
    String labelsTitle;
    String labelsPath;
    String sourceName;
    final String[] rawTitles = new String[4];
    final String[] rawPaths = new String[4];
    final String[] rawNames = new String[4];
    final Double[] quantMin = new Double[4];
    final Double[] quantMax = new Double[4];
    String referenceChannel;
    String outputDirectory;
    boolean autoSave;
    boolean hideDisplay;
    boolean saveFigures = true;
    boolean saveClassMaps = true;
    final OipConfig config = new OipConfig();

    String toMacroOptions() {
        validateQuantizationSlots();
        List<String> tokens = new ArrayList<String>();
        append(tokens, "labels", labelsTitle);
        append(tokens, "labels_path", labelsPath);
        append(tokens, "source_name", sourceName);
        for (int i = 0; i < rawTitles.length; i++) {
            append(tokens, "raw" + (i + 1), rawTitles[i]);
            append(tokens, "raw" + (i + 1) + "_path", rawPaths[i]);
            append(tokens, "raw" + (i + 1) + "_name", rawNames[i]);
            if (quantMin[i] != null) tokens.add("quant_min" + (i + 1) + "=" + quantMin[i]);
            if (quantMax[i] != null) tokens.add("quant_max" + (i + 1) + "=" + quantMax[i]);
        }
        append(tokens, "reference", referenceChannel);
        tokens.add(config.doRadial ? "radial" : "no_radial");
        tokens.add(config.doMarginal ? "marginal" : "no_marginal");
        tokens.add(config.doPrincipalAxis ? "principal" : "no_principal");
        tokens.add(config.doAngular ? "angular" : "no_angular");
        tokens.add(config.doShell ? "shell" : "no_shell");
        tokens.add(config.doWithinBox ? "correlation" : "no_correlation");
        tokens.add(config.region == OipConfig.Region.OBJECT_VOXELS ? "mask" : "box");
        tokens.add("intensity_norm=" + intensityNorm(config.intensityNorm));
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
        if (autoSave) {
            tokens.add("auto_save");
            append(tokens, "output", outputDirectory);
        }
        if (hideDisplay) tokens.add("hide_display");
        return join(tokens);
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
        for (int i = 0; i < rawTitles.length; i++) {
            QuantizationRange range = range(i);
            if (range != null && !hasText(rawTitles[i]) && !hasText(rawPaths[i])) {
                throw new IllegalArgumentException("quant_min" + (i + 1)
                        + " and quant_max" + (i + 1)
                        + " require raw slot " + (i + 1) + ".");
            }
        }
    }

    static void append(List<String> tokens, String key, String value) {
        if (!hasText(value)) return;
        String normalized = value;
        if (normalized.indexOf('"') >= 0
                || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    "Macro values cannot contain quotes or line breaks.");
        }
        if (normalized.startsWith("oip-escaped:")
                || normalized.indexOf('[') >= 0 || normalized.indexOf(']') >= 0) {
            normalized = "oip-escaped:" + normalized
                    .replace("%", "%25")
                    .replace("[", "%5B")
                    .replace("]", "%5D");
        }
        tokens.add(key + "=[" + normalized + "]");
    }

    static String join(List<String> tokens) {
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (out.length() > 0) out.append(' ');
            out.append(token);
        }
        return out.toString();
    }

    static String intensityNorm(OipConfig.IntensityNorm value) {
        if (value == OipConfig.IntensityNorm.DIVIDE_BY_MEAN) return "mean";
        if (value == OipConfig.IntensityNorm.ZSCORE) return "zscore";
        return "minmax";
    }

    static boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
