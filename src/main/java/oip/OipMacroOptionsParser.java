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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strict parser for {@code run("Object Intensity Profiling", "...")} options.
 */
final class OipMacroOptionsParser {

    private OipMacroOptionsParser() {
    }

    static OipMacroOptions parse(String text) {
        final OipMacroOptions options = new OipMacroOptions();
        forEachOption(tokenize(text == null ? "" : text), 0, new OptionSink() {
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

    /** Receives each option once it has passed the duplicate check. */
    interface OptionSink {
        void flag(String flag);

        void value(String key, String value);
    }

    /**
     * Split tokens into lower-case flags and {@code key=value} pairs, rejecting an option (or a
     * conflicting member of its on/off family, such as {@code glcm no_glcm}) given twice.
     */
    static void forEachOption(List<String> tokens, int start, OptionSink sink) {
        Map<String, String> seen = new HashMap<String, String>();
        for (int i = start; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int equals = token.indexOf('=');
            String name = (equals < 0 ? token : token.substring(0, equals)).toLowerCase(Locale.ROOT);
            String previous = seen.put(OipConfigOptions.family(name), name);
            if (previous != null) {
                throw new IllegalArgumentException(previous.equals(name)
                        ? "Macro option given more than once: " + name
                        : "Macro options conflict or repeat: " + previous + " and " + name);
            }
            if (equals < 0) sink.flag(name);
            else sink.value(name, decode(token.substring(equals + 1)));
        }
    }

    private static void applyValue(OipMacroOptions o, String key, String value) {
        if (OipConfigOptions.applyValue(o.config, key, value)) return;
        if ("labels".equals(key)) o.labelsTitle = value;
        else if ("labels_path".equals(key)) o.labelsPath = value;
        else if ("objects_roi".equals(key)) o.objectsRoi = value;
        else if ("region_roi".equals(key)) o.regionRoi = value;
        else if ("source_name".equals(key)) o.sourceName = value;
        else if ("reference".equals(key)) o.referenceChannel = value;
        else if ("output".equals(key) || "save_dir".equals(key)) {
            o.outputDirectory = value;
        } else {
            int slot = slot(key, "raw", "");
            if (slot >= 0) o.rawTitles[slot] = emptyAsNull(value);
            else if ((slot = slot(key, "raw", "_path")) >= 0) o.rawPaths[slot] = emptyAsNull(value);
            else if ((slot = slot(key, "raw", "_name")) >= 0) o.rawNames[slot] = emptyAsNull(value);
            else if ((slot = slot(key, "quant_min", "")) >= 0) o.quantMin[slot] = number(key, value);
            else if ((slot = slot(key, "quant_max", "")) >= 0) o.quantMax[slot] = number(key, value);
            else if (isBatchOnlyKey(key)) {
                throw new IllegalArgumentException(key
                        + " is a folder-batch option; start the options with batch.");
            } else throw new IllegalArgumentException("Unknown macro option: " + key);
        }
    }

    private static void applyFlag(OipMacroOptions o, String flag) {
        if (OipConfigOptions.applyFlag(o.config, flag)) return;
        if ("save_figures".equals(flag)) o.saveFigures = true;
        else if ("no_figures".equals(flag)) o.saveFigures = false;
        else if ("save_maps".equals(flag)) o.saveClassMaps = true;
        else if ("no_maps".equals(flag)) o.saveClassMaps = false;
        else if ("auto_save".equals(flag) || "autosave".equals(flag)) o.autoSave = true;
        else if ("hide_display".equals(flag) || "no_display".equals(flag)) o.hideDisplay = true;
        else if ("batch".equals(flag)) {
            throw new IllegalArgumentException("batch must be the first macro option.");
        } else if ("recursive".equals(flag) || "no_recursive".equals(flag)) {
            throw new IllegalArgumentException(flag
                    + " is a folder-batch option; start the options with batch.");
        } else throw new IllegalArgumentException("Unknown macro flag: " + flag);
    }

    private static boolean isBatchOnlyKey(String key) {
        return "labels_folder".equals(key) || "labels_regex".equals(key)
                || slot(key, "raw", "_folder") >= 0 || slot(key, "raw", "_regex") >= 0;
    }

    private static void validate(OipMacroOptions o) {
        OipConfigOptions.validate(o.config);
        int labelSources = (OipMacroOptions.hasText(o.labelsTitle) ? 1 : 0)
                + (OipMacroOptions.hasText(o.labelsPath) ? 1 : 0)
                + (OipMacroOptions.hasText(o.objectsRoi) ? 1 : 0);
        if (labelSources > 1) {
            throw new IllegalArgumentException(
                    "Use only one of labels, labels_path and objects_roi to define the objects.");
        }
        if (labelSources == 0) {
            throw new IllegalArgumentException("labels, labels_path or objects_roi is required.");
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

    static double number(String key, String value) {
        return OipConfigOptions.number(key, value);
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
