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
import oip.profile.ProfileShapeClassifier;
import oip.texture.ZernikeMoments;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single table of analysis flags and {@code key=value} options shared by the single-image
 * and folder-batch macro parsers, so every analysis option is parsed and recorded identically in
 * both modes. Mode-specific options (inputs, output, display) stay in each parser.
 */
final class OipConfigOptions {

    /** Flag or key name to its duplicate-detection family ("glcm" and "no_glcm" share one). */
    private static final Map<String, String> FAMILIES;

    static {
        Map<String, String> families = new HashMap<String, String>();
        pair(families, "radial", "no_radial");
        pair(families, "marginal", "no_marginal");
        pair(families, "principal", "no_principal");
        pair(families, "angular", "no_angular");
        pair(families, "shell", "no_shell");
        pair(families, "correlation", "no_correlation");
        pair(families, "mask", "box");
        pair(families, "glcm", "no_glcm");
        pair(families, "texture_classes", "no_texture_classes");
        pair(families, "zernike", "no_zernike");
        pair(families, "profile_classes", "no_profile_classes");
        // Mode-level options that are also on/off pairs or aliases.
        pair(families, "save_figures", "no_figures");
        pair(families, "save_maps", "no_maps");
        pair(families, "hide_display", "no_display");
        pair(families, "auto_save", "autosave");
        pair(families, "recursive", "no_recursive");
        pair(families, "output", "save_dir");
        FAMILIES = Collections.unmodifiableMap(families);
    }

    private OipConfigOptions() {
    }

    private static void pair(Map<String, String> families, String first, String second) {
        families.put(first, first);
        families.put(second, first);
    }

    /**
     * Duplicate-detection family for a lower-case flag or key; options in one family may be
     * given at most once per options string.
     */
    static String family(String name) {
        String family = FAMILIES.get(name);
        return family == null ? name : family;
    }

    /** @return true when the flag belonged to the analysis configuration. */
    static boolean applyFlag(OipConfig c, String flag) {
        if ("radial".equals(flag)) c.doRadial = true;
        else if ("no_radial".equals(flag)) c.doRadial = false;
        else if ("marginal".equals(flag)) c.doMarginal = true;
        else if ("no_marginal".equals(flag)) c.doMarginal = false;
        else if ("principal".equals(flag)) c.doPrincipalAxis = true;
        else if ("no_principal".equals(flag)) c.doPrincipalAxis = false;
        else if ("angular".equals(flag)) c.doAngular = true;
        else if ("no_angular".equals(flag)) c.doAngular = false;
        else if ("shell".equals(flag)) c.doShell = true;
        else if ("no_shell".equals(flag)) c.doShell = false;
        else if ("correlation".equals(flag)) c.doWithinBox = true;
        else if ("no_correlation".equals(flag)) c.doWithinBox = false;
        else if ("mask".equals(flag)) c.region = OipConfig.Region.OBJECT_VOXELS;
        else if ("box".equals(flag)) c.region = OipConfig.Region.WHOLE_BOX;
        else if ("glcm".equals(flag)) c.doGlcm = true;
        else if ("no_glcm".equals(flag)) c.doGlcm = false;
        else if ("texture_classes".equals(flag)) c.doTextureClasses = true;
        else if ("no_texture_classes".equals(flag)) c.doTextureClasses = false;
        else if ("zernike".equals(flag)) c.doZernike = true;
        else if ("no_zernike".equals(flag)) c.doZernike = false;
        else if ("profile_classes".equals(flag)) c.doProfileClasses = true;
        else if ("no_profile_classes".equals(flag)) c.doProfileClasses = false;
        else return false;
        return true;
    }

    /** @return true when the key belonged to the analysis configuration. */
    static boolean applyValue(OipConfig c, String key, String value) {
        if ("intensity_norm".equals(key)) c.intensityNorm = intensityNorm(value);
        else if ("radial_bins".equals(key)) c.radialBins = integer(key, value);
        else if ("curve_bins".equals(key)) c.resampleN = integer(key, value);
        else if ("angular_bins".equals(key)) c.angularBins = integer(key, value);
        else if ("shells".equals(key)) c.shells = integer(key, value);
        else if ("padding".equals(key)) c.boxPadPct = number(key, value);
        else if ("ring_threshold".equals(key)) c.ringThresholdPct = number(key, value);
        else if ("reference_threshold".equals(key)) c.referenceThreshold = number(key, value);
        else if ("partner_threshold".equals(key)) c.partnerThreshold = number(key, value);
        else if ("glcm_levels".equals(key)) c.glcmLevels = integer(key, value);
        else if ("glcm_distance".equals(key)) c.glcmDistance = integer(key, value);
        else if ("texture_k".equals(key)) c.textureClasses = integer(key, value);
        else if ("minimum_texture_voxels".equals(key)) c.minimumTextureVoxels = integer(key, value);
        else if ("zernike_degree".equals(key)) {
            c.zernikeDegree = integerInRange(key, value, 1, ZernikeMoments.MAX_DEGREE);
        } else if ("profile_k".equals(key)) c.profileClasses = integerInRange(key, value, 1, 255);
        else if ("profile_class_type".equals(key)) {
            c.profileClassFamily = ProfileShapeClassifier.Family.parse(value);
        }
        else return false;
        return true;
    }

    /** Record every analysis option, including false flags, in a stable order. */
    static void appendTokens(List<String> tokens, OipConfig config) {
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
        tokens.add(config.doZernike ? "zernike" : "no_zernike");
        tokens.add("zernike_degree=" + config.zernikeDegree);
        tokens.add(config.doProfileClasses ? "profile_classes" : "no_profile_classes");
        tokens.add("profile_k=" + config.profileClasses);
        tokens.add("profile_class_type=" + config.profileClassFamily.macroValue);
    }

    /** Cross-option checks shared by both parsers, run after every option has been read. */
    static void validate(OipConfig config) {
        ProfileShapeClassifier.validate(config);
    }

    static int integer(String key, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number; got: " + value);
        }
    }

    static int integerInRange(String key, String value, int minimum, int maximum) {
        int parsed = integer(key, value);
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and "
                    + maximum + "; got: " + parsed);
        }
        return parsed;
    }

    static double number(String key, String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be numeric; got: " + value);
        }
    }

    static OipConfig.IntensityNorm intensityNorm(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("minmax".equals(normalized)) return OipConfig.IntensityNorm.PER_OBJECT_MINMAX;
        if ("mean".equals(normalized)) return OipConfig.IntensityNorm.DIVIDE_BY_MEAN;
        if ("zscore".equals(normalized)) return OipConfig.IntensityNorm.ZSCORE;
        throw new IllegalArgumentException(
                "intensity_norm must be minmax, mean, or zscore; got: " + value);
    }

    static String intensityNorm(OipConfig.IntensityNorm value) {
        if (value == OipConfig.IntensityNorm.DIVIDE_BY_MEAN) return "mean";
        if (value == OipConfig.IntensityNorm.ZSCORE) return "zscore";
        return "minmax";
    }
}
