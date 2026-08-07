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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Strict parser for {@code run("Object Intensity Profiling", "...")} options.
 */
final class OipMacroOptionsParser {

    private OipMacroOptionsParser() {
    }

    static OipMacroOptions parse(String text) {
        OipMacroOptions options = new OipMacroOptions();
        for (String token : tokenize(text == null ? "" : text)) {
            int equals = token.indexOf('=');
            if (equals < 0) applyFlag(options, token.toLowerCase(Locale.ROOT));
            else applyValue(options,
                    token.substring(0, equals).toLowerCase(Locale.ROOT),
                    decode(token.substring(equals + 1)));
        }
        validate(options);
        return options;
    }

    private static void applyValue(OipMacroOptions o, String key, String value) {
        if ("labels".equals(key)) o.labelsTitle = value;
        else if ("labels_path".equals(key)) o.labelsPath = value;
        else if ("source_name".equals(key)) o.sourceName = value;
        else if ("reference".equals(key)) o.referenceChannel = value;
        else if ("output".equals(key) || "save_dir".equals(key)) {
            o.outputDirectory = value;
        } else if ("intensity_norm".equals(key)) {
            o.config.intensityNorm = intensityNorm(value);
        } else if ("radial_bins".equals(key)) o.config.radialBins = integer(key, value);
        else if ("curve_bins".equals(key)) o.config.resampleN = integer(key, value);
        else if ("angular_bins".equals(key)) o.config.angularBins = integer(key, value);
        else if ("shells".equals(key)) o.config.shells = integer(key, value);
        else if ("padding".equals(key)) o.config.boxPadPct = number(key, value);
        else if ("ring_threshold".equals(key)) o.config.ringThresholdPct = number(key, value);
        else if ("reference_threshold".equals(key)) o.config.referenceThreshold = number(key, value);
        else if ("partner_threshold".equals(key)) o.config.partnerThreshold = number(key, value);
        else if ("glcm_levels".equals(key)) o.config.glcmLevels = integer(key, value);
        else if ("glcm_distance".equals(key)) o.config.glcmDistance = integer(key, value);
        else if ("texture_k".equals(key)) o.config.textureClasses = integer(key, value);
        else if ("minimum_texture_voxels".equals(key)) {
            o.config.minimumTextureVoxels = integer(key, value);
        } else {
            int slot = slot(key, "raw", "");
            if (slot >= 0) o.rawTitles[slot] = emptyAsNull(value);
            else if ((slot = slot(key, "raw", "_path")) >= 0) o.rawPaths[slot] = emptyAsNull(value);
            else if ((slot = slot(key, "raw", "_name")) >= 0) o.rawNames[slot] = emptyAsNull(value);
            else if ((slot = slot(key, "quant_min", "")) >= 0) o.quantMin[slot] = number(key, value);
            else if ((slot = slot(key, "quant_max", "")) >= 0) o.quantMax[slot] = number(key, value);
            else throw new IllegalArgumentException("Unknown macro option: " + key);
        }
    }

    private static void applyFlag(OipMacroOptions o, String flag) {
        if ("radial".equals(flag)) o.config.doRadial = true;
        else if ("no_radial".equals(flag)) o.config.doRadial = false;
        else if ("marginal".equals(flag)) o.config.doMarginal = true;
        else if ("no_marginal".equals(flag)) o.config.doMarginal = false;
        else if ("principal".equals(flag)) o.config.doPrincipalAxis = true;
        else if ("no_principal".equals(flag)) o.config.doPrincipalAxis = false;
        else if ("angular".equals(flag)) o.config.doAngular = true;
        else if ("no_angular".equals(flag)) o.config.doAngular = false;
        else if ("shell".equals(flag)) o.config.doShell = true;
        else if ("no_shell".equals(flag)) o.config.doShell = false;
        else if ("correlation".equals(flag)) o.config.doWithinBox = true;
        else if ("no_correlation".equals(flag)) o.config.doWithinBox = false;
        else if ("mask".equals(flag)) o.config.region = OipConfig.Region.OBJECT_VOXELS;
        else if ("box".equals(flag)) o.config.region = OipConfig.Region.WHOLE_BOX;
        else if ("glcm".equals(flag)) o.config.doGlcm = true;
        else if ("no_glcm".equals(flag)) o.config.doGlcm = false;
        else if ("texture_classes".equals(flag)) o.config.doTextureClasses = true;
        else if ("no_texture_classes".equals(flag)) o.config.doTextureClasses = false;
        else if ("save_figures".equals(flag)) o.saveFigures = true;
        else if ("no_figures".equals(flag)) o.saveFigures = false;
        else if ("save_maps".equals(flag)) o.saveClassMaps = true;
        else if ("no_maps".equals(flag)) o.saveClassMaps = false;
        else if ("auto_save".equals(flag) || "autosave".equals(flag)) o.autoSave = true;
        else if ("hide_display".equals(flag) || "no_display".equals(flag)) o.hideDisplay = true;
        else throw new IllegalArgumentException("Unknown macro flag: " + flag);
    }

    private static void validate(OipMacroOptions o) {
        if (OipMacroOptions.hasText(o.labelsTitle) && OipMacroOptions.hasText(o.labelsPath)) {
            throw new IllegalArgumentException("Use labels or labels_path, not both.");
        }
        if (!OipMacroOptions.hasText(o.labelsTitle) && !OipMacroOptions.hasText(o.labelsPath)) {
            throw new IllegalArgumentException("labels or labels_path is required.");
        }
        int rawCount = 0;
        for (int i = 0; i < 4; i++) {
            if (OipMacroOptions.hasText(o.rawTitles[i]) && OipMacroOptions.hasText(o.rawPaths[i])) {
                throw new IllegalArgumentException("Use raw" + (i + 1) + " or raw"
                        + (i + 1) + "_path, not both.");
            }
            if (OipMacroOptions.hasText(o.rawTitles[i]) || OipMacroOptions.hasText(o.rawPaths[i])) {
                rawCount++;
            }
        }
        o.validateQuantizationSlots();
        if (rawCount == 0) throw new IllegalArgumentException("At least raw1 is required.");
        if (rawCount > 1 && !OipMacroOptions.hasText(o.referenceChannel)) {
            throw new IllegalArgumentException(
                    "reference is required when more than one raw channel is supplied.");
        }
        if (o.autoSave && !OipMacroOptions.hasText(o.outputDirectory)) {
            throw new IllegalArgumentException("output is required with auto_save.");
        }
    }

    static int slot(String key, String prefix, String suffix) {
        if (!key.startsWith(prefix) || !key.endsWith(suffix)) return -1;
        String middle = key.substring(prefix.length(), key.length() - suffix.length());
        if (middle.length() != 1 || middle.charAt(0) < '1' || middle.charAt(0) > '4') return -1;
        return middle.charAt(0) - '1';
    }

    static int integer(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer.");
        }
    }

    static double number(String key, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be numeric.");
        }
    }

    static OipConfig.IntensityNorm intensityNorm(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("minmax".equals(normalized)) return OipConfig.IntensityNorm.PER_OBJECT_MINMAX;
        if ("mean".equals(normalized)) return OipConfig.IntensityNorm.DIVIDE_BY_MEAN;
        if ("zscore".equals(normalized)) return OipConfig.IntensityNorm.ZSCORE;
        throw new IllegalArgumentException(
                "intensity_norm must be minmax, mean, or zscore.");
    }

    private static String emptyAsNull(String value) {
        return OipMacroOptions.hasText(value) && !"<none>".equals(value) ? value : null;
    }

    static String decode(String value) {
        if (value.length() >= 2 && value.charAt(0) == '['
                && value.charAt(value.length() - 1) == ']') {
            value = value.substring(1, value.length() - 1);
        }
        if (value.startsWith("oip-escaped:")) {
            value = unescape(value.substring("oip-escaped:".length()));
        }
        return value;
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '%' && i + 2 < value.length()) {
                String code = value.substring(i + 1, i + 3);
                if ("25".equalsIgnoreCase(code)) {
                    out.append('%'); i += 2; continue;
                }
                if ("5B".equalsIgnoreCase(code)) {
                    out.append('['); i += 2; continue;
                }
                if ("5D".equalsIgnoreCase(code)) {
                    out.append(']'); i += 2; continue;
                }
            }
            out.append(value.charAt(i));
        }
        return out.toString();
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean bracket = false;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '[') bracket = true;
            if (character == ']') bracket = false;
            if (Character.isWhitespace(character) && !bracket) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else current.append(character);
        }
        if (bracket) throw new IllegalArgumentException("Unclosed bracketed macro value.");
        if (current.length() > 0) tokens.add(current.toString());
        return tokens;
    }
}
