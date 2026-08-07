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
package oip.texture;

/**
 * Plain row-major intensity and mask patch for per-object morphometry primitives.
 */
public final class ObjectPatch {
    public final float[] intensity;
    public final byte[] mask;
    public final int width;
    public final int height;
    public final double pixelSize_um;

    public ObjectPatch(float[] intensity, byte[] mask, int width, int height, double pixelSize_um) {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
        long size = (long) width * (long) height;
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("patch is too large");
        }
        if (intensity == null) {
            throw new IllegalArgumentException("intensity must not be null");
        }
        if (mask == null) {
            throw new IllegalArgumentException("mask must not be null");
        }
        if (intensity.length != (int) size) {
            throw new IllegalArgumentException("intensity length must equal width * height");
        }
        if (mask.length != (int) size) {
            throw new IllegalArgumentException("mask length must equal width * height");
        }
        this.intensity = intensity;
        this.mask = mask;
        this.width = width;
        this.height = height;
        this.pixelSize_um = pixelSize_um;
    }

    public int index(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("coordinates outside patch");
        }
        return y * width + x;
    }

    public boolean containsObjectPixel(int x, int y) {
        return mask[index(x, y)] != 0;
    }

    public int objectPixelCount() {
        int count = 0;
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] != 0) count++;
        }
        return count;
    }
}
