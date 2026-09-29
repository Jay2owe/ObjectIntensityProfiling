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

import ij.ImagePlus;
import ij.process.ImageProcessor;
import oip.ObjectIntensityProfiling;
import oip.OipParameters;
import oip.profile.LabelObjects;
import oip.profile.LabelObjects.ObjectInfo;
import oip.profile.StackSlices;

/**
 * Builds masked 2D patches directly from a label image and a dimension-matched raw stack.
 */
public final class ObjectPatchBuilder {

    private ObjectPatchBuilder() {
    }

    public static ObjectPatch buildSlice(ObjectInfo object, ImagePlus labels, ImagePlus raw,
                                         int z, double paddingPercent) {
        return buildSlice(object, labels, raw, z, paddingPercent, null);
    }

    public static ObjectPatch buildSlice(
            ObjectInfo object, ImagePlus labels, ImagePlus raw, int z, double paddingPercent,
            OipParameters.CancellationToken cancellation) {
        Crop crop = crop(object, labels, paddingPercent);
        int slice = clamp(z, 0, labels.getStackSize() - 1);
        return slicePatch(object, crop, labels.getStack().getProcessor(slice + 1),
                raw.getStack().getProcessor(slice + 1), pixelWidth(raw), cancellation);
    }

    /** As {@link #buildSlice(ObjectInfo, ImagePlus, ImagePlus, int, double,
     *  OipParameters.CancellationToken)}, reading slices from per-run caches. */
    public static ObjectPatch buildSlice(
            ObjectInfo object, StackSlices labels, StackSlices raw, int z, double paddingPercent,
            OipParameters.CancellationToken cancellation) {
        Crop crop = crop(object, labels, paddingPercent);
        int slice = clamp(z, 0, labels.size() - 1);
        return slicePatch(object, crop, labels.slice(slice), raw.slice(slice),
                pixelWidth(raw.image()), cancellation);
    }

    private static ObjectPatch slicePatch(
            ObjectInfo object, Crop crop, ImageProcessor labelProcessor,
            ImageProcessor rawProcessor, double pixelWidth,
            OipParameters.CancellationToken cancellation) {
        float[] intensity = new float[crop.width * crop.height];
        byte[] mask = new byte[intensity.length];
        for (int y = crop.y0; y <= crop.y1; y++) {
            if ((y & 31) == 0) checkCancelled(cancellation);
            for (int x = crop.x0; x <= crop.x1; x++) {
                int index = crop.index(x, y);
                if (LabelObjects.label(labelProcessor.getf(x, y)) == object.label) {
                    intensity[index] = rawProcessor.getf(x, y);
                    mask[index] = 1;
                }
            }
        }
        return new ObjectPatch(intensity, mask, crop.width, crop.height, pixelWidth);
    }

    public static ObjectPatch buildMIP(ObjectInfo object, ImagePlus labels, ImagePlus raw,
                                       double paddingPercent) {
        return buildMIP(object, labels, raw, paddingPercent, null);
    }

    public static ObjectPatch buildMIP(
            ObjectInfo object, ImagePlus labels, ImagePlus raw, double paddingPercent,
            OipParameters.CancellationToken cancellation) {
        Crop crop = crop(object, labels, paddingPercent);
        int z0 = clamp(object.zmin, 0, labels.getStackSize() - 1);
        int z1 = clamp(object.zmax, 0, labels.getStackSize() - 1);
        ImageProcessor[] labelSlices = new ImageProcessor[z1 - z0 + 1];
        ImageProcessor[] rawSlices = new ImageProcessor[labelSlices.length];
        for (int z = z0; z <= z1; z++) {
            checkCancelled(cancellation);
            labelSlices[z - z0] = labels.getStack().getProcessor(z + 1);
            rawSlices[z - z0] = raw.getStack().getProcessor(z + 1);
        }
        return mip(object, crop, labelSlices, rawSlices, pixelWidth(raw), cancellation);
    }

    /** As {@link #buildMIP(ObjectInfo, ImagePlus, ImagePlus, double,
     *  OipParameters.CancellationToken)}, reading slices from per-run caches. */
    public static ObjectPatch buildMIP(
            ObjectInfo object, StackSlices labels, StackSlices raw, double paddingPercent,
            OipParameters.CancellationToken cancellation) {
        Crop crop = crop(object, labels, paddingPercent);
        int z0 = clamp(object.zmin, 0, labels.size() - 1);
        int z1 = clamp(object.zmax, 0, labels.size() - 1);
        ImageProcessor[] labelSlices = new ImageProcessor[z1 - z0 + 1];
        ImageProcessor[] rawSlices = new ImageProcessor[labelSlices.length];
        for (int z = z0; z <= z1; z++) {
            labelSlices[z - z0] = labels.slice(z);
            rawSlices[z - z0] = raw.slice(z);
        }
        return mip(object, crop, labelSlices, rawSlices, pixelWidth(raw.image()), cancellation);
    }

    private static ObjectPatch mip(
            ObjectInfo object, Crop crop, ImageProcessor[] labelSlices,
            ImageProcessor[] rawSlices, double pixelWidth,
            OipParameters.CancellationToken cancellation) {
        float[] intensity = new float[crop.width * crop.height];
        byte[] mask = new byte[intensity.length];
        java.util.Arrays.fill(intensity, Float.NEGATIVE_INFINITY);
        for (int s = 0; s < labelSlices.length; s++) {
            checkCancelled(cancellation);
            ImageProcessor labelProcessor = labelSlices[s];
            ImageProcessor rawProcessor = rawSlices[s];
            for (int y = crop.y0; y <= crop.y1; y++) {
                if ((y & 31) == 0) checkCancelled(cancellation);
                for (int x = crop.x0; x <= crop.x1; x++) {
                    int index = crop.index(x, y);
                    if (LabelObjects.label(labelProcessor.getf(x, y)) != object.label) continue;
                    float value = rawProcessor.getf(x, y);
                    if (Float.isFinite(value)) {
                        if (value > intensity[index]) intensity[index] = value;
                        mask[index] = 1;
                    }
                }
            }
        }
        for (int i = 0; i < intensity.length; i++) {
            if (mask[i] == 0) intensity[i] = Float.NaN;
        }
        return new ObjectPatch(intensity, mask, crop.width, crop.height, pixelWidth);
    }

    private static Crop crop(ObjectInfo object, ImagePlus labels, double paddingPercent) {
        if (object == null || object.isBoxEmpty()) {
            throw new IllegalArgumentException("Object must have a bounding box.");
        }
        if (labels == null || labels.getStack() == null) {
            throw new IllegalArgumentException("Label image must contain pixels.");
        }
        return crop(object, labels.getWidth(), labels.getHeight(), paddingPercent);
    }

    private static Crop crop(ObjectInfo object, StackSlices labels, double paddingPercent) {
        if (object == null || object.isBoxEmpty()) {
            throw new IllegalArgumentException("Object must have a bounding box.");
        }
        if (labels == null) {
            throw new IllegalArgumentException("Label image must contain pixels.");
        }
        return crop(object, labels.getWidth(), labels.getHeight(), paddingPercent);
    }

    private static Crop crop(ObjectInfo object, int width, int height, double paddingPercent) {
        int padX = padding(paddingPercent, object.xmax - object.xmin + 1, width);
        int padY = padding(paddingPercent, object.ymax - object.ymin + 1, height);
        return new Crop(
                lower(object.xmin, padX),
                upper(object.xmax, padX, width),
                lower(object.ymin, padY),
                upper(object.ymax, padY, height));
    }

    private static int padding(double percent, int extent, int dimension) {
        double requested = Math.max(0.0, percent) / 100.0 * extent;
        if (!Double.isFinite(requested) || requested >= dimension) return dimension;
        return Math.max(0, (int) Math.ceil(requested));
    }

    private static int lower(int coordinate, int padding) {
        return (int) Math.max(0L, (long) coordinate - padding);
    }

    private static int upper(int coordinate, int padding, int dimension) {
        return (int) Math.min(dimension - 1L, (long) coordinate + padding);
    }

    private static double pixelWidth(ImagePlus image) {
        if (image.getCalibration() == null || image.getCalibration().pixelWidth <= 0) return 1.0;
        return image.getCalibration().pixelWidth;
    }

    private static void checkCancelled(OipParameters.CancellationToken cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ObjectIntensityProfiling.AnalysisCancelledException();
        }
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static final class Crop {
        final int x0;
        final int x1;
        final int y0;
        final int y1;
        final int width;
        final int height;

        Crop(int x0, int x1, int y0, int y1) {
            this.x0 = x0;
            this.x1 = x1;
            this.y0 = y0;
            this.y1 = y1;
            this.width = x1 - x0 + 1;
            this.height = y1 - y0 + 1;
        }

        int index(int x, int y) {
            return (y - y0) * width + (x - x0);
        }
    }
}
