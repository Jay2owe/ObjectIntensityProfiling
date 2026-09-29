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

import ij.ImagePlus;
import ij.gui.Roi;
import sc.fiji.oc3d.core.ingest.RoiLabelImages;
import oip.profile.LabelObjects;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ImageJ ROI inputs: an ROI set as the object definition, and an ROI set restricting which
 * objects are profiled.
 *
 * <p>Object ROIs are converted by oc3d-core: ROI {@code i} becomes label {@code i + 1}; an ROI
 * with a Z position ({@link Roi#getZPosition()}) is drawn on that slice only, one without is
 * drawn on every slice; where ROIs overlap the later ROI wins. Region ROIs follow the same
 * Z rule, so objects and regions agree.</p>
 */
public final class OipRoiInputs {

    private OipRoiInputs() {
    }

    /**
     * Convert an ROI set ({@code .zip} or single {@code .roi}) into a label image sized and
     * calibrated like {@code reference}, titled after the ROI file.
     */
    public static ImagePlus labelsFromRoiSet(ImagePlus reference, String path) throws IOException {
        requireFile(path, "objects_roi");
        return RoiLabelImages.fromRoiSetFile(reference, path);
    }

    /** Convert ROIs into a label image sized and calibrated like {@code reference}. */
    public static ImagePlus labelsFromRois(ImagePlus reference, Roi[] rois) {
        return RoiLabelImages.fromRois(reference, rois);
    }

    /** Load a region ROI set; every ROI must enclose an area. */
    public static Roi[] regionRois(String path) throws IOException {
        requireFile(path, "region_roi");
        Roi[] rois = RoiLabelImages.loadRoiSet(path);
        validateRegion(rois, Integer.MAX_VALUE, new File(path).getName());
        return rois;
    }

    /**
     * Reject a region set that would restrict objects wrongly rather than visibly: an empty set,
     * a line or point selection, or an ROI positioned beyond the label stack.
     */
    static void validateRegion(Roi[] rois, int slices, String source) {
        String from = source == null ? "" : " in " + source;
        if (rois == null || rois.length == 0) {
            throw new IllegalArgumentException("The region ROI set" + from + " contains no ROIs.");
        }
        for (int i = 0; i < rois.length; i++) {
            Roi roi = rois[i];
            String name = "Region ROI " + (i + 1) + (roi != null && roi.getName() != null
                    && roi.getName().trim().length() > 0 ? " (\"" + roi.getName().trim() + "\")" : "")
                    + from;
            if (roi == null) throw new IllegalArgumentException(name + " is missing.");
            if (!roi.isArea()) {
                throw new IllegalArgumentException(name + " is a line, angle or point selection; "
                        + "region ROIs must enclose an area.");
            }
            if (roi.getZPosition() > slices) {
                throw new IllegalArgumentException(name + " is positioned on slice "
                        + roi.getZPosition() + " but the label image has only " + slices
                        + " slice(s).");
            }
        }
    }

    /**
     * Objects whose centroid lies inside the union of the region ROIs, in input order. The
     * centroid is tested at the pixel nearest to it (halves round up) on the slice nearest to it.
     */
    public static List<LabelObjects.ObjectInfo> insideRegion(
            List<LabelObjects.ObjectInfo> objects, Roi[] region) {
        List<LabelObjects.ObjectInfo> kept = new ArrayList<LabelObjects.ObjectInfo>();
        for (LabelObjects.ObjectInfo object : objects) {
            if (inside(object, region)) kept.add(object);
        }
        return kept;
    }

    static boolean inside(LabelObjects.ObjectInfo object, Roi[] region) {
        int x = (int) Math.round(object.cx);
        int y = (int) Math.round(object.cy);
        int slice = (int) Math.round(object.cz) + 1;
        for (Roi roi : region) {
            int position = roi.getZPosition();
            if (position > 0 && position != slice) continue;
            if (roi.contains(x, y)) return true;
        }
        return false;
    }

    private static void requireFile(String path, String option) {
        if (path == null || path.trim().length() == 0) {
            throw new IllegalArgumentException(option + " needs an ROI file path.");
        }
        if (!new File(path).isFile()) {
            throw new IllegalArgumentException(option + " file does not exist: " + path);
        }
    }
}
