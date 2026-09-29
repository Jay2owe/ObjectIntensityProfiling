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

import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import oip.profile.StackSlices;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Input rules shared by single-image runs and batch preflight: pixel types the measurements can
 * read, and calibration that agrees between the label image and each raw channel.
 */
final class OipInputChecks {

    /** Relative tolerance for pixel sizes; TIFF resolution is stored as a rational number. */
    private static final double SIZE_TOLERANCE = 1.0e-4;

    private OipInputChecks() {
    }

    /** RGB pixels are packed colour values, not intensities or labels. */
    static void requireNotRgb(ImagePlus image, String what) {
        if (image != null && image.getBitDepth() == 24) {
            throw new IllegalArgumentException("RGB colour images are not supported (" + what
                    + "); split the channels first (Image > Color > Split Channels).");
        }
    }

    /**
     * Profiles are measured in the label image's calibration. A raw channel that carries a
     * different pixel size or unit was probably acquired or resampled differently, so it is
     * rejected; an uncalibrated raw channel is accepted.
     */
    static void requireMatchingCalibration(ImagePlus labels, ImagePlus raw, String what) {
        Calibration a = labels.getCalibration();
        Calibration b = raw.getCalibration();
        if (a == null || b == null || !a.scaled() || !b.scaled()) return;
        boolean differs = !close(a.pixelWidth, b.pixelWidth)
                || !close(a.pixelHeight, b.pixelHeight)
                || (labels.getNSlices() > 1 && !close(a.pixelDepth, b.pixelDepth))
                || !unit(a.getUnit()).equals(unit(b.getUnit()));
        if (differs) {
            throw new IllegalArgumentException("Raw calibration differs from the label image ("
                    + what + "): label " + describe(a, labels) + ", raw " + describe(b, raw)
                    + ". Fix it with Image > Properties.");
        }
    }

    /** True when a 3D label image has no calibration, so its voxels are treated as cubes. */
    static boolean uncalibrated3d(ImagePlus labels) {
        return labels.getNSlices() > 1
                && (labels.getCalibration() == null || !labels.getCalibration().scaled());
    }

    static void warnUncalibrated3d(String what) {
        IJ.log("Object Intensity Profiling: " + what + " is a 3D label image without "
                + "calibration, so voxels are treated as cubes. If the Z step differs from the "
                + "pixel size, set it with Image > Properties.");
    }

    /**
     * Logs each virtual stack that the per-run slice cache has read into memory, with its size,
     * so a user with a very large virtual stack knows where the memory went.
     */
    static void logInMemoryCopies(StackSlices labels, Map<String, StackSlices> raws) {
        List<StackSlices> all = new ArrayList<StackSlices>();
        all.add(labels);
        all.addAll(raws.values());
        for (StackSlices slices : all) {
            if (slices == null || !slices.isVirtual()) continue;
            IJ.log(String.format(Locale.ROOT, "Object Intensity Profiling: virtual stack \"%s\" "
                    + "was read into memory once for this run (%.1f MB).",
                    slices.image().getTitle(), slices.bytes() / (1024.0 * 1024.0)));
        }
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) <= SIZE_TOLERANCE * Math.max(Math.abs(left), Math.abs(right));
    }

    private static String unit(String unit) {
        String u = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        if (u.equals("um") || u.equals("micron") || u.equals("microns")
                || u.equals("µm") || u.equals("μm")) {
            return "um";
        }
        return u;
    }

    private static String describe(Calibration c, ImagePlus image) {
        String size = IJ.d2s(c.pixelWidth, 4) + " x " + IJ.d2s(c.pixelHeight, 4)
                + (image.getNSlices() > 1 ? " x " + IJ.d2s(c.pixelDepth, 4) : "");
        return size + " " + c.getUnit();
    }
}
