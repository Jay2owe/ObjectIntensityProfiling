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
package oip.profile;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only per-run slice cache: one {@link ImageProcessor} per slice, fetched once.
 *
 * <p>{@link ImageStack#getProcessor(int)} rescans the whole slice for its display range on every
 * call, and the per-object loops used to call it for every slice of every object. A cache is
 * built once, serially, before any parallel work starts, and is then only read with
 * {@link ImageProcessor#getf(int, int)}, which is safe from several threads. Never call a
 * mutating method (such as {@code setRoi} or {@code snapshot}) on a cached processor.
 *
 * <p>For an ordinary stack the cached processors share the stack's pixel arrays, so no pixels are
 * copied. For a virtual stack every slice is read from disk once and kept in memory for the run;
 * {@link #isVirtual()} and {@link #bytes()} let the caller report that cost.
 */
public final class StackSlices {

    private final ImagePlus image;
    private final ImageProcessor[] slices;
    private final int width;
    private final int height;
    private final boolean virtual;

    private StackSlices(ImagePlus image, ImageProcessor[] slices, int width, int height,
                        boolean virtual) {
        this.image = image;
        this.slices = slices;
        this.width = width;
        this.height = height;
        this.virtual = virtual;
    }

    /** Fetches every slice of {@code image} once; call it before starting parallel work. */
    public static StackSlices of(ImagePlus image) {
        if (image == null || image.getStack() == null) {
            throw new IllegalArgumentException("Image must contain pixels.");
        }
        ImageStack stack = image.getStack();
        ImageProcessor[] slices = new ImageProcessor[stack.getSize()];
        for (int z = 0; z < slices.length; z++) {
            slices[z] = stack.getProcessor(z + 1);
        }
        return new StackSlices(image, slices, stack.getWidth(), stack.getHeight(),
                stack.isVirtual());
    }

    /** Builds one cache per channel, keeping the map's order. */
    public static Map<String, StackSlices> ofAll(Map<String, ImagePlus> images) {
        Map<String, StackSlices> out = new LinkedHashMap<String, StackSlices>();
        if (images == null) return out;
        for (Map.Entry<String, ImagePlus> entry : images.entrySet()) {
            ImagePlus value = entry.getValue();
            out.put(entry.getKey(), value == null || value.getStack() == null ? null : of(value));
        }
        return out;
    }

    /** Slice {@code z}, counted from 0. */
    public ImageProcessor slice(int z) {
        return slices[z];
    }

    public int size() {
        return slices.length;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** The image the cache was built from (for calibration and titles). */
    public ImagePlus image() {
        return image;
    }

    /** True when the source was a virtual stack, so the cache holds a full in-memory copy. */
    public boolean isVirtual() {
        return virtual;
    }

    /** Approximate pixel memory held by the cached slices. */
    public long bytes() {
        long bytesPerPixel = image.getBitDepth() == 24 ? 4 : Math.max(1, image.getBitDepth() / 8);
        return (long) width * height * slices.length * bytesPerPixel;
    }
}
