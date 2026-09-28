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
package oip.golden;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * JDK-independent canonical text for the plugin's output files.
 *
 * <p>Every CSV field that parses as a number is re-emitted as its raw IEEE bits, so a
 * change in {@code Double.toString} / {@code Float.toString} between JDKs cannot alter a
 * digest while any real numeric change always does. Texture feature columns (Gabor*,
 * Wavelet*) are written by the plugin as {@code float}, so they are canonicalised as float
 * bits. TIFF files are reduced to their dimensions and pixel arrays because the header
 * carries the ImageJ version.</p>
 */
final class CanonicalDump {

    private CanonicalDump() {
    }

    static String canonicalCsv(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        if (lines.isEmpty()) return "";
        List<String> header = split(lines.get(0));
        out.append("H:").append(lines.get(0)).append('\n');
        for (int row = 1; row < lines.size(); row++) {
            List<String> fields = split(lines.get(row));
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) out.append(',');
                String column = i < header.size() ? header.get(i) : "";
                out.append(canonicalField(fields.get(i), isFloatColumn(column)));
            }
            out.append('\n');
        }
        return out.toString();
    }

    static String canonicalTiff(Path tif) throws IOException {
        ImagePlus image = IJ.openImage(tif.toAbsolutePath().toString());
        if (image == null) throw new IOException("Could not open TIFF: " + tif);
        StringBuilder out = new StringBuilder();
        out.append("T:").append(image.getWidth()).append('x').append(image.getHeight())
                .append('x').append(image.getStackSize())
                .append(" bitDepth=").append(image.getBitDepth()).append('\n');
        ImageStack stack = image.getStack();
        for (int z = 1; z <= stack.getSize(); z++) {
            Object pixels = stack.getPixels(z);
            out.append("S").append(z).append(':');
            if (pixels instanceof byte[]) {
                for (byte b : (byte[]) pixels) out.append(Integer.toHexString(b & 0xff)).append(' ');
            } else if (pixels instanceof short[]) {
                for (short s : (short[]) pixels) out.append(Integer.toHexString(s & 0xffff)).append(' ');
            } else if (pixels instanceof float[]) {
                for (float f : (float[]) pixels) {
                    out.append(Integer.toHexString(Float.floatToIntBits(f))).append(' ');
                }
            } else if (pixels instanceof int[]) {
                for (int v : (int[]) pixels) out.append(Integer.toHexString(v)).append(' ');
            } else {
                throw new IOException("Unsupported pixel type in " + tif);
            }
            out.append('\n');
        }
        image.close();
        return out.toString();
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isFloatColumn(String column) {
        return column.startsWith("Gabor") || column.startsWith("Wavelet");
    }

    static String canonicalField(String field, boolean floatColumn) {
        if (field.isEmpty()) return "";
        if (looksNumeric(field)) {
            try {
                if (floatColumn) {
                    return "f" + Integer.toHexString(Float.floatToIntBits(Float.parseFloat(field)));
                }
                return "d" + Long.toHexString(Double.doubleToLongBits(Double.parseDouble(field)));
            } catch (NumberFormatException notNumeric) {
                // fall through to text
            }
        }
        return "s" + field;
    }

    /** Plain decimal / scientific numbers only; rejects Java suffix forms such as "1f". */
    private static boolean looksNumeric(String field) {
        return field.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?|[-+]?Infinity|NaN");
    }

    /** RFC 4180 field splitting for one line (the plugin never writes embedded newlines). */
    static List<String> split(String line) {
        List<String> fields = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
