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

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static oip.OipMacroOptionsParserTest.assertRejected;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OipBatchMacroOptionsParserTest {

    private static final String SINGLE = "labels=[Objects] raw1=[Raw] ";
    private static final String BATCH = "batch labels_folder=[C:/labels] "
            + "labels_regex=[(.*)_labels\\.tif] raw1_name=[Raw] raw1_folder=[C:/raw] "
            + "raw1_regex=[(.*)_raw\\.tif] output=[C:/out] ";

    /** Every analysis option, each set away from its default. */
    private static final String[] ANALYSIS_OPTIONS = {
        "no_radial", "no_marginal", "no_principal", "no_angular", "no_shell",
        "no_correlation", "box", "glcm", "texture_classes", "intensity_norm=zscore",
        "radial_bins=9", "curve_bins=17", "angular_bins=8", "shells=5", "padding=12.5",
        "ring_threshold=40", "reference_threshold=2", "partner_threshold=3",
        "glcm_levels=16", "glcm_distance=2", "texture_k=3", "minimum_texture_voxels=20",
        "zernike", "zernike_degree=4", "profile_k=2", "profile_class_type=shell"
    };

    @Test
    public void profileClassOptionsAreAcceptedInBothModesAndRoundTrip() {
        String options = "profile_classes profile_k=2 profile_class_type=shell";
        oip.profile.OipConfig single = OipMacroOptionsParser.parse(SINGLE + options).config;
        oip.profile.OipConfig batch = OipBatchMacroOptionsParser.parse(BATCH + options).config;
        for (oip.profile.OipConfig config : new oip.profile.OipConfig[] {single, batch}) {
            assertTrue(config.doProfileClasses);
            assertEquals(2, config.profileClasses);
            assertEquals(oip.profile.ProfileShapeClassifier.Family.SHELL,
                    config.profileClassFamily);
        }
        assertEquals(recordedConfig(single), recordedConfig(batch));
        OipBatchMacroOptions parsed = OipBatchMacroOptionsParser.parse(BATCH + options);
        assertEquals(recordedConfig(parsed.config), recordedConfig(
                OipBatchMacroOptionsParser.parse(parsed.toMacroOptions()).config));
        assertTrue((" " + parsed.toMacroOptions() + " ").contains(" profile_classes "));
        assertTrue((" " + new OipBatchMacroOptions().toMacroOptions() + " ")
                .contains(" no_profile_classes "));
    }

    @Test
    public void profileClassOptionsAreValidatedInBothModes() {
        String message = "profile_class_type=angular needs the angular profile; remove "
                + "no_angular or choose another profile_class_type.";
        assertRejected(SINGLE + "profile_classes profile_class_type=angular no_angular", message);
        assertRejected(BATCH + "profile_classes profile_class_type=angular no_angular", message);
        assertRejected(SINGLE + "profile_classes profile_k=0",
                "profile_k must be between 1 and 255; got: 0");
        assertRejected(BATCH + "profile_class_type=spiral", "profile_class_type must be radial");
        assertRejected(SINGLE + "profile_classes no_profile_classes",
                "profile_classes and no_profile_classes");
        // The curve family is only checked when classes are requested.
        OipMacroOptionsParser.parse(SINGLE + "profile_class_type=angular no_angular");
    }

    @Test
    public void zernikeOptionsAreAcceptedInBothModesAndRangeChecked() {
        assertEquals(4, OipMacroOptionsParser.parse(SINGLE + "zernike zernike_degree=4")
                .config.zernikeDegree);
        assertTrue(OipBatchMacroOptionsParser.parse(BATCH + "zernike zernike_degree=4")
                .config.doZernike);
        assertRejected(SINGLE + "zernike zernike_degree=0",
                "zernike_degree must be between 1 and 20; got: 0");
        assertRejected(BATCH + "zernike zernike_degree=21",
                "zernike_degree must be between 1 and 20; got: 21");
        assertRejected(SINGLE + "zernike no_zernike", "zernike and no_zernike");
    }

    @Test
    public void everyAnalysisOptionParsesIdenticallyInBothModes() {
        for (String option : ANALYSIS_OPTIONS) {
            String single = recordedConfig(OipMacroOptionsParser.parse(SINGLE + option).config);
            String batch = recordedConfig(
                    OipBatchMacroOptionsParser.parse(BATCH + option).config);
            assertEquals(option, single, batch);
            assertTrue(option + " must change the recorded configuration",
                    !single.equals(recordedConfig(new oip.profile.OipConfig())));
        }
        StringBuilder all = new StringBuilder();
        for (String option : ANALYSIS_OPTIONS) all.append(option).append(' ');
        assertEquals(recordedConfig(OipMacroOptionsParser.parse(SINGLE + all).config),
                recordedConfig(OipBatchMacroOptionsParser.parse(BATCH + all).config));
    }

    @Test
    public void everyAnalysisOptionIsRecordedAndRoundTrips() {
        StringBuilder all = new StringBuilder();
        for (String option : ANALYSIS_OPTIONS) all.append(option).append(' ');
        OipBatchMacroOptions parsed = OipBatchMacroOptionsParser.parse(BATCH + all);
        OipBatchMacroOptions again = OipBatchMacroOptionsParser.parse(parsed.toMacroOptions());
        assertEquals(recordedConfig(parsed.config), recordedConfig(again.config));
        for (String option : ANALYSIS_OPTIONS) {
            assertTrue(option, (" " + parsed.toMacroOptions() + " ").contains(" " + option + " ")
                    || option.startsWith("padding") || option.contains("threshold"));
        }
    }

    @Test
    public void batchAcceptsSaveDirAsOutputAlias() {
        OipBatchMacroOptions parsed = OipBatchMacroOptionsParser.parse(
                "batch labels_folder=[C:/labels] labels_regex=[(.*)_labels\\.tif] "
                        + "raw1_name=[Raw] raw1_folder=[C:/raw] raw1_regex=[(.*)_raw\\.tif] "
                        + "save_dir=[C:/elsewhere]");
        assertEquals("C:/elsewhere", parsed.outputDirectory);
    }

    @Test
    public void batchAcceptsAutoSaveAsANoOpBecauseBatchesAlwaysSave() {
        OipBatchMacroOptions parsed = OipBatchMacroOptionsParser.parse(BATCH + "auto_save");
        assertEquals("C:/out", parsed.outputDirectory);
        OipBatchMacroOptionsParser.parse(BATCH + "autosave");
    }

    @Test
    public void batchRejectsSourceNameWithTheBatchEquivalent() {
        assertRejected(BATCH + "source_name=[Nuclei]", "capture group 1 of labels_regex");
    }

    @Test
    public void singleImageInputsInBatchModeNameTheBatchKeys() {
        assertRejected(BATCH + "labels=[Objects]", "labels is a single-image option");
        assertRejected(BATCH + "raw2_path=[C:/x.tif]", "raw2_path is a single-image option");
    }

    @Test
    public void batchRejectsRepeatsAndConflicts() {
        assertRejected(BATCH + "recursive no_recursive", "recursive and no_recursive");
        assertRejected(BATCH + "save_dir=[C:/b]", "output and save_dir");
        assertRejected(BATCH + "shells=3 shells=4", "given more than once: shells");
    }

    @Test
    public void batchStillRejectsUnknownOptions() {
        assertRejected(BATCH + "mystery=1", "Unknown batch macro option: mystery");
        assertRejected(BATCH + "mystery", "Unknown batch macro flag: mystery");
        assertRejected(BATCH + "batch", "batch must be the first macro option");
    }

    private static String recordedConfig(oip.profile.OipConfig config) {
        List<String> tokens = new ArrayList<String>();
        OipConfigOptions.appendTokens(tokens, config);
        return OipMacroOptions.join(tokens);
    }
}
