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

import oip.profile.OipConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class OipMacroOptionsParserTest {

    @Test
    public void parsesAndRecordsCompleteOptionsWithoutLosingFalseFlags() {
        String macro = "labels=[Objects] raw1=[DAPI] raw1_name=[Reference] "
                + "raw2_path=[C:/data/marker.tif] raw2_name=[Marker] reference=[Reference] "
                + "no_radial marginal principal no_angular shell correlation box "
                + "glcm texture_classes radial_bins=24 curve_bins=60 angular_bins=16 "
                + "shells=4 padding=15 texture_k=3 minimum_texture_voxels=80 "
                + "intensity_norm=zscore "
                + "quant_min1=0 quant_max1=4095 no_figures no_maps "
                + "auto_save output=[C:/results] hide_display";
        OipMacroOptions parsed = OipMacroOptionsParser.parse(macro);

        assertFalse(parsed.config.doRadial);
        assertTrue(parsed.config.doMarginal);
        assertFalse(parsed.config.doAngular);
        assertEquals(OipConfig.Region.WHOLE_BOX, parsed.config.region);
        assertTrue(parsed.config.doGlcm);
        assertTrue(parsed.config.doTextureClasses);
        assertEquals(OipConfig.IntensityNorm.ZSCORE, parsed.config.intensityNorm);
        assertEquals(60, parsed.config.resampleN);
        assertEquals(15.0, parsed.config.boxPadPct, 0.0);
        assertNotNull(parsed.range(0));
        assertFalse(parsed.saveFigures);
        assertFalse(parsed.saveClassMaps);

        OipMacroOptions roundTrip = OipMacroOptionsParser.parse(parsed.toMacroOptions());
        assertFalse(roundTrip.config.doRadial);
        assertFalse(roundTrip.config.doAngular);
        assertEquals(OipConfig.IntensityNorm.ZSCORE, roundTrip.config.intensityNorm);
        assertEquals("Reference", roundTrip.referenceChannel);
        assertEquals("C:/results", roundTrip.outputDirectory);
        assertFalse(roundTrip.saveFigures);
        assertFalse(roundTrip.saveClassMaps);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownOption() {
        OipMacroOptionsParser.parse("labels=[Objects] raw1=[Raw] mystery=true");
    }

    @Test(expected = IllegalArgumentException.class)
    public void requiresReferenceForMultipleRawChannels() {
        OipMacroOptionsParser.parse(
                "labels=[Objects] raw1=[Raw A] raw2=[Raw B]");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsManualRangeForUnconfiguredRawSlot() {
        OipMacroOptionsParser.parse(
                "labels=[Objects] raw1=[Raw A] quant_min2=0 quant_max2=255");
    }

    @Test
    public void batchMacroRoundTripsAllAnalysisAndRangeOptions() {
        String macro = "batch labels_folder=[C:/labels] "
                + "labels_regex=[(sample[0-9]+)_labels\\.tif] "
                + "raw1_name=[Signal] raw1_folder=[C:/raw] raw1_regex=[(.*)_raw.tif] "
                + "raw2_name=[Ref] raw2_folder=[C:/ref] raw2_regex=[(.*)_ref.tif] "
                + "reference=[Ref] output=[C:/out] no_recursive no_radial marginal "
                + "principal angular shell correlation mask glcm texture_classes "
                + "radial_bins=9 curve_bins=17 angular_bins=8 shells=5 padding=12 "
                + "ring_threshold=40 reference_threshold=2 partner_threshold=3 "
                + "glcm_levels=16 glcm_distance=2 texture_k=3 minimum_texture_voxels=20 "
                + "intensity_norm=mean quant_min1=-5 quant_max1=50 "
                + "no_figures save_maps hide_display";
        OipBatchMacroOptions parsed = OipBatchMacroOptionsParser.parse(macro);
        OipBatchMacroOptions roundTrip =
                OipBatchMacroOptionsParser.parse(parsed.toMacroOptions());
        assertFalse(roundTrip.recursive);
        assertFalse(roundTrip.config.doRadial);
        assertEquals(17, roundTrip.config.resampleN);
        assertEquals(16, roundTrip.config.glcmLevels);
        assertEquals(OipConfig.IntensityNorm.DIVIDE_BY_MEAN,
                roundTrip.config.intensityNorm);
        assertEquals("(sample[0-9]+)_labels\\.tif", roundTrip.labelRegex);
        assertEquals(-5.0, roundTrip.range(0).min, 0.0);
        assertEquals("Ref", roundTrip.referenceChannel);
        assertFalse(roundTrip.saveFigures);
        assertTrue(roundTrip.saveClassMaps);
        assertTrue(roundTrip.hideDisplay);
        assertTrue(Object_Intensity_Profiling.recorded(parsed.toMacroOptions())
                .contains("_labels\\\\.tif"));
    }

    @Test
    public void outputDoesNotEnableAutoSaveWithoutExplicitFlag() {
        OipMacroOptions parsed = OipMacroOptionsParser.parse(
                "labels=[Objects] raw1=[Raw] output=[C:/results]");
        assertFalse(parsed.autoSave);
        assertFalse(parsed.toMacroOptions().contains("output="));
    }

    @Test
    public void reservedEscapePrefixRoundTripsLiterally() {
        OipMacroOptions options = new OipMacroOptions();
        options.labelsTitle = " oip-escaped:%5Bobjects%5D ";
        options.sourceName = "oip-escaped:%5Bsource%5D";
        options.rawTitles[0] = " Raw ";
        options.rawNames[0] = " oip-escaped:%5Bsignal%5D ";

        OipMacroOptions parsed =
                OipMacroOptionsParser.parse(options.toMacroOptions());

        assertEquals(options.labelsTitle, parsed.labelsTitle);
        assertEquals(options.sourceName, parsed.sourceName);
        assertEquals(options.rawTitles[0], parsed.rawTitles[0]);
        assertEquals(options.rawNames[0], parsed.rawNames[0]);
    }
}
