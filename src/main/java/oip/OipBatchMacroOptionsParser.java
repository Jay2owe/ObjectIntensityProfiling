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

import java.util.List;
import java.util.Locale;

/** Strict parser for headless, recorder-compatible folder-batch options. */
final class OipBatchMacroOptionsParser {
    private OipBatchMacroOptionsParser() {
    }

    static boolean isBatch(String text) {
        List<String> tokens = OipMacroOptionsParser.tokenize(text == null ? "" : text);
        return !tokens.isEmpty() && "batch".equalsIgnoreCase(tokens.get(0));
    }

    static OipBatchMacroOptions parse(String text) {
        List<String> tokens = OipMacroOptionsParser.tokenize(text == null ? "" : text);
        if (tokens.isEmpty() || !"batch".equalsIgnoreCase(tokens.get(0))) {
            throw new IllegalArgumentException("Batch macro options must start with batch.");
        }
        OipBatchMacroOptions options = new OipBatchMacroOptions();
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int equals = token.indexOf('=');
            if (equals < 0) applyFlag(options, token.toLowerCase(Locale.ROOT));
            else applyValue(options, token.substring(0, equals).toLowerCase(Locale.ROOT),
                    OipMacroOptionsParser.decode(token.substring(equals + 1)));
        }
        validate(options);
        return options;
    }

    private static void applyValue(OipBatchMacroOptions o, String key, String value) {
        if ("labels_folder".equals(key)) o.labelFolder = value;
        else if ("labels_regex".equals(key)) o.labelRegex = value;
        else if ("reference".equals(key)) o.referenceChannel = value;
        else if ("output".equals(key)) o.outputDirectory = value;
        else if ("intensity_norm".equals(key)) {
            o.config.intensityNorm = OipMacroOptionsParser.intensityNorm(value);
        }
        else if ("radial_bins".equals(key)) o.config.radialBins = integer(key, value);
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
            int slot = OipMacroOptionsParser.slot(key, "raw", "_name");
            if (slot >= 0) o.rawNames[slot] = clean(value);
            else if ((slot = OipMacroOptionsParser.slot(key, "raw", "_folder")) >= 0) {
                o.rawFolders[slot] = clean(value);
            } else if ((slot = OipMacroOptionsParser.slot(key, "raw", "_regex")) >= 0) {
                o.rawRegexes[slot] = clean(value);
            } else if ((slot = OipMacroOptionsParser.slot(key, "quant_min", "")) >= 0) {
                o.quantMin[slot] = number(key, value);
            } else if ((slot = OipMacroOptionsParser.slot(key, "quant_max", "")) >= 0) {
                o.quantMax[slot] = number(key, value);
            } else throw new IllegalArgumentException("Unknown batch macro option: " + key);
        }
    }

    private static void applyFlag(OipBatchMacroOptions o, String flag) {
        if ("recursive".equals(flag)) o.recursive = true;
        else if ("no_recursive".equals(flag)) o.recursive = false;
        else if ("radial".equals(flag)) o.config.doRadial = true;
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
        else if ("hide_display".equals(flag) || "no_display".equals(flag)) o.hideDisplay = true;
        else throw new IllegalArgumentException("Unknown batch macro flag: " + flag);
    }

    private static void validate(OipBatchMacroOptions o) {
        if (!hasText(o.labelFolder) || !hasText(o.labelRegex) || !hasText(o.outputDirectory)) {
            throw new IllegalArgumentException(
                    "labels_folder, labels_regex, and output are required for batch mode.");
        }
        int rawCount = 0;
        boolean referenceFound = false;
        for (int i = 0; i < 4; i++) {
            boolean any = hasText(o.rawNames[i]) || hasText(o.rawFolders[i])
                    || hasText(o.rawRegexes[i]) || o.quantMin[i] != null || o.quantMax[i] != null;
            boolean all = hasText(o.rawNames[i]) && hasText(o.rawFolders[i])
                    && hasText(o.rawRegexes[i]);
            if (any && !all) {
                throw new IllegalArgumentException("Raw " + (i + 1)
                        + " requires name, folder, and regex.");
            }
            if (all) {
                rawCount++;
                referenceFound |= o.rawNames[i].equals(o.referenceChannel);
            }
            o.range(i);
        }
        o.validateQuantizationSlots();
        if (rawCount == 0) throw new IllegalArgumentException("At least raw1 is required.");
        if (rawCount > 1 && !hasText(o.referenceChannel)) {
            throw new IllegalArgumentException(
                    "reference is required when more than one raw channel is supplied.");
        }
        if (hasText(o.referenceChannel) && !referenceFound) {
            throw new IllegalArgumentException(
                    "reference is not one of the configured raw channel names.");
        }
    }

    private static int integer(String key, String value) {
        return OipMacroOptionsParser.integer(key, value);
    }

    private static double number(String key, String value) {
        return OipMacroOptionsParser.number(key, value);
    }

    private static String clean(String value) {
        return OipMacroOptions.hasText(value) ? value : null;
    }

    private static boolean hasText(String value) {
        return OipMacroOptions.hasText(value);
    }
}
