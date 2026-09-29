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
        final OipBatchMacroOptions options = new OipBatchMacroOptions();
        OipMacroOptionsParser.forEachOption(tokens, 1, new OipMacroOptionsParser.OptionSink() {
            @Override
            public void flag(String flag) {
                applyFlag(options, flag);
            }

            @Override
            public void value(String key, String value) {
                applyValue(options, key, value);
            }
        });
        validate(options);
        return options;
    }

    private static void applyValue(OipBatchMacroOptions o, String key, String value) {
        if (OipConfigOptions.applyValue(o.config, key, value)) return;
        if ("labels_folder".equals(key)) o.labelFolder = value;
        else if ("labels_regex".equals(key)) o.labelRegex = value;
        else if ("reference".equals(key)) o.referenceChannel = value;
        // save_dir is accepted as an alias for output, as in single-image mode.
        else if ("output".equals(key) || "save_dir".equals(key)) o.outputDirectory = value;
        else if ("objects_roi".equals(key) || "region_roi".equals(key)) {
            throw new IllegalArgumentException(key + " is available for single images only in "
                    + "this version; run each image separately or convert the ROI sets to label "
                    + "images first.");
        } else if ("source_name".equals(key)) {
            throw new IllegalArgumentException("source_name is not used in batch mode: "
                    + "every sample is named by capture group 1 of labels_regex.");
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
            } else if (isSingleImageKey(key)) {
                throw new IllegalArgumentException(key + " is a single-image option; batch mode "
                        + "uses labels_folder, labels_regex and rawN_name/rawN_folder/rawN_regex.");
            } else throw new IllegalArgumentException("Unknown batch macro option: " + key);
        }
    }

    private static void applyFlag(OipBatchMacroOptions o, String flag) {
        if (OipConfigOptions.applyFlag(o.config, flag)) return;
        if ("recursive".equals(flag)) o.recursive = true;
        else if ("no_recursive".equals(flag)) o.recursive = false;
        else if ("save_figures".equals(flag)) o.saveFigures = true;
        else if ("no_figures".equals(flag)) o.saveFigures = false;
        else if ("save_maps".equals(flag)) o.saveClassMaps = true;
        else if ("no_maps".equals(flag)) o.saveClassMaps = false;
        else if ("hide_display".equals(flag) || "no_display".equals(flag)) o.hideDisplay = true;
        // Batch results are always saved to output, so auto_save is accepted and changes nothing.
        else if ("auto_save".equals(flag) || "autosave".equals(flag)) return;
        else if ("batch".equals(flag)) {
            throw new IllegalArgumentException("batch must be the first macro option.");
        } else throw new IllegalArgumentException("Unknown batch macro flag: " + flag);
    }

    private static boolean isSingleImageKey(String key) {
        return "labels".equals(key) || "labels_path".equals(key)
                || OipMacroOptionsParser.slot(key, "raw", "") >= 0
                || OipMacroOptionsParser.slot(key, "raw", "_path") >= 0;
    }

    private static void validate(OipBatchMacroOptions o) {
        OipConfigOptions.validate(o.config);
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
