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

import ij.measure.ResultsTable;
import oip.profile.ObjectProfileResult;
import oip.texture.ObjectTextureFeatures;
import oip.texture.ObjectTextureResult;

/**
 * Converts API results into ImageJ result tables for interactive use.
 */
public final class OipTables {

    private OipTables() {
    }

    public static ResultsTable summaries(OipResult result) {
        ResultsTable table = new ResultsTable();
        for (ObjectProfileResult object : result.getProfiles()) {
            for (ObjectProfileResult.PartnerProfiles partner : object.byPartner.values()) {
                table.incrementCounter();
                table.addValue("Source", object.sourceChannel);
                table.addValue("Label", object.label);
                table.addValue("Voxel count", object.voxelCount);
                table.addValue("Partner", partner.partnerChannel);
                add(table, "Elongation", object.elongation);
                add(table, "Flatness", object.flatness);
                add(table, "Radial core/edge", partner.radialCoreEdge);
                add(table, "Radial peak", partner.radialPeakR);
                add(table, "Radial polarity", partner.radialPolarity);
                add(table, "Ring completeness", partner.ringCompleteness);
                add(table, "Angular uniformity", partner.angularUniformity);
                add(table, "Shell inner", partner.shellInner);
                add(table, "Shell middle", partner.shellMid);
                add(table, "Shell outer", partner.shellOuter);
                add(table, "Shell overlap inner", partner.shellOverlapInner);
                add(table, "Shell overlap middle", partner.shellOverlapMid);
                add(table, "Shell overlap outer", partner.shellOverlapOuter);
                add(table, "Principal-axis polarity", partner.pcMajorPolarity);
                add(table, "Pearson", partner.withinBoxPearson);
                add(table, "Overlap coefficient", partner.withinBoxOverlap);
                add(table, "Manders M1", partner.mandersM1);
                add(table, "Manders M2", partner.mandersM2);
            }
        }
        return table;
    }

    public static ResultsTable textures(OipResult result) {
        ResultsTable table = new ResultsTable();
        for (ObjectTextureResult texture : result.getTextures()) {
            table.incrementCounter();
            table.addValue("Label", texture.label);
            table.addValue("Voxel count", texture.voxelCount);
            table.addValue("Partner", texture.partnerChannel);
            table.addValue("Suppressed", texture.suppressed ? 1 : 0);
            table.addValue("GLCM reliable", texture.glcmReliable ? 1 : 0);
            table.addValue("Feature valid", texture.featureVector != null
                    && texture.featureVector.valid ? 1 : 0);
            table.addValue("Feature reliable", texture.featureVector != null
                    && texture.featureVector.reliable ? 1 : 0);
            add(table, "Contrast", texture.contrast);
            add(table, "ASM", texture.asm);
            add(table, "Correlation", texture.correlation);
            add(table, "Entropy", texture.entropy);
            add(table, "Homogeneity", texture.homogeneity);
            for (int i = 0; i < ObjectTextureFeatures.DEFAULT_FEATURE_DIM; i++) {
                double feature = texture.featureVector != null
                        && texture.featureVector.valid
                        && i < texture.featureVector.features.length
                        ? texture.featureVector.features[i] : Double.NaN;
                // Same four Gabor orientations as Texture/Object_Texture.csv, labelled by angle
                // rather than by index. The CSV closes up the space ("Gabor45") to match its own
                // no-space heading style; these keep it to match the table's.
                table.addValue(i < 4 ? "Gabor " + (i * 45) : "Wavelet " + (i - 3), feature);
            }
            add(table, "Texture class",
                    texture.classLabel >= 0 ? texture.classLabel + 1 : Double.NaN);
            add(table, "Class distance", texture.classDistance);
        }
        return table;
    }

    /**
     * Writes every column on every row, including the undefined ones.
     *
     * <p>Skipping {@code addValue} for a non-finite value does not leave the cell empty. A column
     * created on a later row is allocated zero-filled, so every earlier row reads 0.0 — and 0 is a
     * meaningful value for all of these measurements, so a suppressed object's undefined GLCM or a
     * hollow object's undefined core/edge ratio was indistinguishable from a measured zero. It also
     * made the column order depend on which object happened to be measurable first. ResultsTable
     * renders these cells as {@code NaN}, where the CSVs leave them blank; both say "no value",
     * and neither can now be misread as a measurement of zero.
     */
    private static void add(ResultsTable table, String heading, double value) {
        table.addValue(heading, value);
    }
}
