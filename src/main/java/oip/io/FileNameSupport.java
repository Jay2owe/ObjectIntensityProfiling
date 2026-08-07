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
package oip.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Collision-free encoding of user/sample names as one portable path component. */
public final class FileNameSupport {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private FileNameSupport() {
    }

    public static String encode(String value) {
        if (value == null || value.length() == 0) return "%00";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length);
        for (byte item : bytes) {
            int unsigned = item & 0xff;
            if ((unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-') {
                out.append((char) unsigned);
            } else {
                out.append('%');
                out.append(HEX[unsigned >>> 4]);
                out.append(HEX[unsigned & 0x0f]);
            }
        }
        String encoded = out.toString();
        String upper = encoded.toUpperCase(Locale.ROOT);
        String base = upper;
        if (isWindowsDevice(base)) {
            int first = bytes[0] & 0xff;
            encoded = "%" + HEX[first >>> 4] + HEX[first & 0x0f]
                    + encoded.substring(1);
        }
        return encoded;
    }

    public static void requireUniqueEncoded(Collection<String> values, String role) {
        Map<String, String> owners = new LinkedHashMap<String, String>();
        for (String value : values) {
            String encoded = encode(value);
            String folded = encoded.toLowerCase(Locale.ROOT);
            String previous = owners.put(folded, value);
            if (previous != null && !previous.equals(value)) {
                throw new IllegalArgumentException(role + " names collide on disk: "
                        + previous + " and " + value);
            }
        }
    }

    /** True only for a path component produced exactly by {@link #encode(String)}. */
    public static boolean isCanonicalEncoding(String value) {
        if (value == null || value.length() == 0) return false;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length();) {
            char item = value.charAt(i);
            if ((item >= 'A' && item <= 'Z') || (item >= 'a' && item <= 'z')
                    || (item >= '0' && item <= '9') || item == '-') {
                bytes.write((byte) item);
                i++;
            } else if (item == '%' && i + 2 < value.length()) {
                int high = hex(value.charAt(i + 1));
                int low = hex(value.charAt(i + 2));
                if (high < 0 || low < 0) return false;
                bytes.write((high << 4) | low);
                i += 3;
            } else {
                return false;
            }
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            return value.equals(encode(decoded));
        } catch (CharacterCodingException invalidUtf8) {
            return false;
        }
    }

    /**
     * Publish a completed temporary file without ever replacing an existing target.
     * A hard link gives atomic no-replace publication where supported; a same-directory
     * move without {@code REPLACE_EXISTING} is the portable fallback.
     */
    public static void publishNoReplace(Path temporary, Path target) throws IOException {
        try {
            Files.createLink(target, temporary);
            return;
        } catch (FileAlreadyExistsException collision) {
            throw collision;
        } catch (UnsupportedOperationException unsupported) {
            Files.move(temporary, target);
            return;
        } catch (IOException linkFailure) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw linkFailure;
            try {
                Files.move(temporary, target);
            } catch (IOException moveFailure) {
                moveFailure.addSuppressed(linkFailure);
                throw moveFailure;
            }
        }
    }

    private static int hex(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        return -1;
    }

    private static boolean isWindowsDevice(String base) {
        if ("CON".equals(base) || "PRN".equals(base) || "AUX".equals(base)
                || "NUL".equals(base)) return true;
        if (base.length() == 4 && (base.startsWith("COM") || base.startsWith("LPT"))) {
            char digit = base.charAt(3);
            return digit >= '1' && digit <= '9';
        }
        return false;
    }
}
