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
import oip.ObjectIntensityProfiling;
import oip.OipParameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts object geometry from integer-valued ImageJ label images.
 */
public final class LabelObjects {

    private LabelObjects() {
    }

    /** Geometry for one unique positive label value. */
    public static final class ObjectInfo {
        public final int label;
        public double cx;
        public double cy;
        public double cz;
        public int voxelCount;
        public int xmin = Integer.MAX_VALUE;
        public int xmax = Integer.MIN_VALUE;
        public int ymin = Integer.MAX_VALUE;
        public int ymax = Integer.MIN_VALUE;
        public int zmin = Integer.MAX_VALUE;
        public int zmax = Integer.MIN_VALUE;

        private ObjectInfo(int label) {
            this.label = label;
        }

        public boolean isBoxEmpty() {
            return xmax < xmin || ymax < ymin || zmax < zmin;
        }
    }

    /** Treats each unique positive integer pixel value as one object label. */
    public static List<ObjectInfo> extract(ImagePlus labels) {
        return extract(labels, null);
    }

    public static List<ObjectInfo> extract(
            ImagePlus labels, OipParameters.CancellationToken cancellation) {
        List<ObjectInfo> out = new ArrayList<ObjectInfo>();
        if (labels == null || labels.getStack() == null) return out;

        ImageStack stack = labels.getStack();
        Map<Integer, long[]> stats = new LinkedHashMap<Integer, long[]>();
        for (int z = 0; z < stack.getSize(); z++) {
            ImageProcessor ip = stack.getProcessor(z + 1);
            for (int y = 0; y < labels.getHeight(); y++) {
                if ((y & 31) == 0 && cancellation != null && cancellation.isCancelled()) {
                    throw new ObjectIntensityProfiling.AnalysisCancelledException();
                }
                for (int x = 0; x < labels.getWidth(); x++) {
                    int label = label(ip.getf(x, y));
                    if (label == 0) continue;
                    long[] s = stats.get(label);
                    if (s == null) {
                        s = new long[10];
                        s[4] = Long.MAX_VALUE;
                        s[5] = Long.MIN_VALUE;
                        s[6] = Long.MAX_VALUE;
                        s[7] = Long.MIN_VALUE;
                        s[8] = Long.MAX_VALUE;
                        s[9] = Long.MIN_VALUE;
                        stats.put(label, s);
                    }
                    s[0] += x;
                    s[1] += y;
                    s[2] += z;
                    s[3]++;
                    if (x < s[4]) s[4] = x;
                    if (x > s[5]) s[5] = x;
                    if (y < s[6]) s[6] = y;
                    if (y > s[7]) s[7] = y;
                    if (z < s[8]) s[8] = z;
                    if (z > s[9]) s[9] = z;
                }
            }
        }

        for (Map.Entry<Integer, long[]> entry : stats.entrySet()) {
            long[] s = entry.getValue();
            ObjectInfo object = new ObjectInfo(entry.getKey());
            object.voxelCount = s[3] > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) s[3];
            object.cx = (double) s[0] / s[3];
            object.cy = (double) s[1] / s[3];
            object.cz = (double) s[2] / s[3];
            object.xmin = (int) s[4];
            object.xmax = (int) s[5];
            object.ymin = (int) s[6];
            object.ymax = (int) s[7];
            object.zmin = (int) s[8];
            object.zmax = (int) s[9];
            out.add(object);
        }
        return out;
    }

    /**
     * Convert one validated label pixel. Zero is background; every other value
     * must be an exact positive integer that fits in the internal label type.
     */
    public static int label(float value) {
        double exact = value;
        if (!Double.isFinite(exact) || exact < 0.0 || exact > Integer.MAX_VALUE
                || exact != Math.rint(exact)) {
            throw new IllegalArgumentException(
                    "Label pixels must be zero or exact positive integers within the supported range; "
                            + "found: " + value);
        }
        return (int) exact;
    }

    /** Validate every pixel without retaining object geometry. */
    public static boolean validate(
            ImagePlus labels, OipParameters.CancellationToken cancellation) {
        if (labels == null || labels.getStack() == null) {
            throw new IllegalArgumentException("A label image is required.");
        }
        ImageStack stack = labels.getStack();
        boolean positive = false;
        for (int z = 0; z < stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z + 1);
            for (int y = 0; y < labels.getHeight(); y++) {
                if ((y & 31) == 0 && cancellation != null && cancellation.isCancelled()) {
                    throw new ObjectIntensityProfiling.AnalysisCancelledException();
                }
                for (int x = 0; x < labels.getWidth(); x++) {
                    if (label(processor.getf(x, y)) > 0) positive = true;
                }
            }
        }
        return positive;
    }
}
