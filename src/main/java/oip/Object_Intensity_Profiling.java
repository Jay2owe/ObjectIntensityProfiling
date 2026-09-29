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
import ij.Macro;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import oip.profile.ObjectProfileFigureWriter;
import oip.profile.ObjectProfileResult;
import oip.profile.OipConfig;
import oip.profile.ProfileAggregator;
import oip.profile.ProfileShapeClassifier;
import oip.texture.QuantizationRange;
import oip.texture.ZernikeMoments;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ImageJ menu and macro entry point.
 */
public final class Object_Intensity_Profiling implements PlugIn {

    static final String TITLE = "Object Intensity Profiling";
    /** Longest batch pairing preview shown in the confirmation dialog. */
    static final int PREVIEW_LIMIT = 3500;

    /** True while running without a display; results are then saved, never shown. */
    private boolean headless;

    @Override
    public void run(String argument) {
        execute(Macro.getOptions(), GraphicsEnvironment.isHeadless());
    }

    /**
     * Run with an explicit headless flag (the seam tests use, since JUnit is not headless).
     * Expected failures (bad options, bad inputs) become a one-line log entry plus an error
     * dialog, or are rethrown when headless so a scripted run exits non-zero; only unexpected
     * programming errors get ImageJ's exception window.
     */
    void execute(String macro, boolean headless) {
        this.headless = headless;
        try {
            if (OipMacroOptions.hasText(macro) || headless) {
                runMacroOptions(macro == null ? "" : macro);
            } else runInteractive();
        } catch (ObjectIntensityProfiling.AnalysisCancelledException cancelled) {
            IJ.log(TITLE + ": " + cancelled.getMessage());
            IJ.showStatus(cancelled.getMessage());
            if (headless) throw cancelled;
        } catch (IllegalArgumentException expected) {
            reportExpected(expected);
        } catch (IllegalStateException expected) {
            reportExpected(expected);
        } catch (RuntimeException unexpected) {
            IJ.log(TITLE + ": " + message(unexpected));
            if (headless) throw unexpected;
            IJ.handleException(unexpected);
        }
    }

    private void reportExpected(RuntimeException error) {
        IJ.log(TITLE + ": " + message(error));
        if (headless) throw error;
        IJ.error(TITLE, message(error));
    }

    private void runInteractive() {
        GenericDialog mode = new GenericDialog("Object Intensity Profiling");
        mode.addChoice("Mode", new String[] {"Open images", "Folder batch"}, "Open images");
        showWithoutRecording(mode);
        if (mode.wasCanceled()) return;
        if (mode.getNextChoiceIndex() == 1) runBatchInteractive();
        else runOpenImagesInteractive();
    }

    private void runOpenImagesInteractive() {
        int[] ids = WindowManager.getIDList();
        if (ids == null || ids.length < 1) {
            IJ.error("Object Intensity Profiling",
                    "Open one label image (or use an ROI set) and at least one matching raw "
                            + "image first.");
            return;
        }
        String[] titles = new String[ids.length];
        for (int i = 0; i < ids.length; i++) titles[i] = WindowManager.getImage(ids[i]).getTitle();
        requireUniqueTitles(titles);
        String[] optional = new String[titles.length + 1];
        optional[0] = "<none>";
        System.arraycopy(titles, 0, optional, 1, titles.length);

        GenericDialog dialog = new GenericDialog("Object Intensity Profiling");
        dialog.addMessage("The label image or ROI set defines objects. "
                + "Choose the raw correlation reference.");
        dialog.addChoice("Objects from", OBJECT_SOURCES, OBJECT_SOURCES[0]);
        dialog.addChoice("Label image", titles, titles[0]);
        dialog.addFileField("Object ROI set", "");
        dialog.addFileField("Region ROI set (optional)", "");
        dialog.addChoice("Raw 1", titles, titles[Math.min(1, titles.length - 1)]);
        dialog.addChoice("Raw 2", optional, optional[0]);
        dialog.addChoice("Raw 3", optional, optional[0]);
        dialog.addChoice("Raw 4", optional, optional[0]);
        dialog.addChoice("Correlation reference",
                new String[] {"Raw 1", "Raw 2", "Raw 3", "Raw 4"}, "Raw 1");
        dialog.addMessage("Profiles");
        dialog.addCheckbox("Radial", true);
        dialog.addCheckbox("Marginal X/Y/Z", true);
        dialog.addCheckbox("Principal axis", true);
        dialog.addCheckbox("Angular / ring completeness", true);
        dialog.addCheckbox("Concentric shells", true);
        dialog.addCheckbox("Pearson, overlap and Manders", true);
        dialog.addChoice("Sampling area", new String[] {"Object mask", "Padded box"}, "Object mask");
        dialog.addChoice("Profile normalisation",
                new String[] {"Per-object min/max", "Divide by mean", "Z-score"},
                "Per-object min/max");
        dialog.addNumericField("Radial bins", 20, 0);
        dialog.addNumericField("Curve bins", 50, 0);
        dialog.addNumericField("Angular bins", 12, 0);
        dialog.addNumericField("Shells", 3, 0);
        dialog.addNumericField("Box padding (%)", 0, 1);
        dialog.addNumericField("Ring threshold (%)", 50, 1);
        dialog.addNumericField("Reference threshold", 0, 3);
        dialog.addNumericField("Partner threshold", 0, 3);
        addProfileClassFields(dialog);
        dialog.addMessage("Texture (slow; disabled by default)");
        dialog.addCheckbox("GLCM texture", false);
        dialog.addNumericField("GLCM grey levels", 32, 0);
        dialog.addNumericField("GLCM distance", 1, 0);
        dialog.addCheckbox("Texture classes", false);
        dialog.addNumericField("Texture classes (k)", 4, 0);
        dialog.addNumericField("Minimum texture voxels", 64, 0);
        dialog.addCheckbox("Zernike moments", false);
        dialog.addNumericField("Zernike degree", ZernikeMoments.DEFAULT_DEGREE, 0);
        dialog.addMessage("Optional fixed quantisation. Leave blank for automatic ranges.");
        for (int i = 1; i <= 4; i++) {
            dialog.addStringField("Raw " + i + " quant min", "", 10);
            dialog.addStringField("Raw " + i + " quant max", "", 10);
        }
        dialog.addCheckbox("Save aggregate figures", true);
        dialog.addCheckbox("Save texture class maps", true);
        dialog.addCheckbox("Auto-save", false);
        dialog.addStringField("Output directory", "", 45);
        dialog.addCheckbox("Hide result tables", false);

        boolean recording = Recorder.record;
        try {
            if (recording) Recorder.record = false;
            dialog.showDialog();
        } finally {
            if (recording) Recorder.record = true;
        }
        if (dialog.wasCanceled()) return;

        OipMacroOptions options = new OipMacroOptions();
        boolean fromRois = dialog.getNextChoiceIndex() == 1;
        String labelTitle = titles[dialog.getNextChoiceIndex()];
        String objectRois = dialog.getNextString().trim();
        String regionRois = dialog.getNextString().trim();
        if (fromRois) {
            if (objectRois.length() == 0) {
                throw new IllegalArgumentException("Choose the object ROI set file.");
            }
            options.objectsRoi = objectRois;
        } else {
            if (ids.length < 2) {
                throw new IllegalArgumentException(
                        "Open one label image and at least one matching raw image first.");
            }
            options.labelsTitle = labelTitle;
        }
        if (regionRois.length() > 0) options.regionRoi = regionRois;
        for (int i = 0; i < 4; i++) {
            String choice = i == 0
                    ? titles[dialog.getNextChoiceIndex()]
                    : optional[dialog.getNextChoiceIndex()];
            if (!"<none>".equals(choice)) {
                options.rawTitles[i] = choice;
                options.rawNames[i] = choice;
            }
        }
        int referenceSlot = dialog.getNextChoiceIndex();
        if (!OipMacroOptions.hasText(options.rawNames[referenceSlot])) {
            throw new IllegalArgumentException(
                    "The selected correlation reference raw slot is empty.");
        }
        options.referenceChannel = options.rawNames[referenceSlot];
        options.config.doRadial = dialog.getNextBoolean();
        options.config.doMarginal = dialog.getNextBoolean();
        options.config.doPrincipalAxis = dialog.getNextBoolean();
        options.config.doAngular = dialog.getNextBoolean();
        options.config.doShell = dialog.getNextBoolean();
        options.config.doWithinBox = dialog.getNextBoolean();
        options.config.region = dialog.getNextChoiceIndex() == 0
                ? OipConfig.Region.OBJECT_VOXELS : OipConfig.Region.WHOLE_BOX;
        options.config.intensityNorm = intensityNorm(dialog.getNextChoiceIndex());
        options.config.radialBins = exactInteger("Radial bins", dialog.getNextNumber());
        options.config.resampleN = exactInteger("Curve bins", dialog.getNextNumber());
        options.config.angularBins = exactInteger("Angular bins", dialog.getNextNumber());
        options.config.shells = exactInteger("Shells", dialog.getNextNumber());
        options.config.boxPadPct = dialog.getNextNumber();
        options.config.ringThresholdPct = dialog.getNextNumber();
        options.config.referenceThreshold = dialog.getNextNumber();
        options.config.partnerThreshold = dialog.getNextNumber();
        readProfileClassFields(dialog, options.config);
        options.config.doGlcm = dialog.getNextBoolean();
        options.config.glcmLevels = exactInteger(
                "GLCM grey levels", dialog.getNextNumber());
        options.config.glcmDistance = exactInteger(
                "GLCM distance", dialog.getNextNumber());
        options.config.doTextureClasses = dialog.getNextBoolean();
        options.config.textureClasses = exactInteger(
                "Texture classes", dialog.getNextNumber());
        options.config.minimumTextureVoxels = exactInteger(
                "Minimum texture voxels", dialog.getNextNumber());
        options.config.doZernike = dialog.getNextBoolean();
        options.config.zernikeDegree = exactInteger("Zernike degree", dialog.getNextNumber());
        for (int i = 0; i < 4; i++) {
            options.quantMin[i] = optionalNumber("Raw " + (i + 1) + " quant min",
                    dialog.getNextString());
            options.quantMax[i] = optionalNumber("Raw " + (i + 1) + " quant max",
                    dialog.getNextString());
        }
        options.validateQuantizationSlots();
        options.saveFigures = dialog.getNextBoolean();
        options.saveClassMaps = dialog.getNextBoolean();
        options.autoSave = dialog.getNextBoolean();
        options.outputDirectory = dialog.getNextString();
        options.hideDisplay = dialog.getNextBoolean();
        if (options.autoSave && !OipMacroOptions.hasText(options.outputDirectory)) {
            DirectoryChooser chooser = new DirectoryChooser("Choose output directory");
            options.outputDirectory = chooser.getDirectory();
            if (!OipMacroOptions.hasText(options.outputDirectory)) return;
        }
        if (recording) record(options.toMacroOptions());
        runOptions(options);
    }

    private void runBatchInteractive() {
        DirectoryChooser labelChooser = new DirectoryChooser("Choose label-image folder");
        String labelDirectory = labelChooser.getDirectory();
        if (!OipMacroOptions.hasText(labelDirectory)) return;

        GenericDialog dialog = new GenericDialog("Object Intensity Profiling - Folder batch");
        dialog.addMessage("Capture group 1 in every regular expression is the sample key.");
        dialog.addStringField("Label regex", "(.*)_labels?\\.tif{1,2}", 42);
        dialog.addStringField("Raw 1 name", "Raw1", 16);
        dialog.addStringField("Raw 1 folder", labelDirectory, 42);
        dialog.addStringField("Raw 1 regex", "(.*)_raw1\\.tif{1,2}", 42);
        for (int i = 2; i <= 4; i++) {
            dialog.addStringField("Raw " + i + " name", "", 16);
            dialog.addStringField("Raw " + i + " folder", labelDirectory, 42);
            dialog.addStringField("Raw " + i + " regex", "", 42);
        }
        dialog.addChoice("Correlation reference",
                new String[] {"Raw 1", "Raw 2", "Raw 3", "Raw 4"}, "Raw 1");
        dialog.addStringField("Output directory",
                new File(labelDirectory, "Object Intensity Profiling Results").getAbsolutePath(), 42);
        dialog.addCheckbox("Include subfolders", true);
        dialog.addMessage("Profiles");
        dialog.addCheckbox("Radial", true);
        dialog.addCheckbox("Marginal X/Y/Z", true);
        dialog.addCheckbox("Principal axis", true);
        dialog.addCheckbox("Angular / ring completeness", true);
        dialog.addCheckbox("Concentric shells", true);
        dialog.addCheckbox("Pearson, overlap and Manders", true);
        dialog.addChoice("Sampling area", new String[] {"Object mask", "Padded box"}, "Object mask");
        dialog.addChoice("Profile normalisation",
                new String[] {"Per-object min/max", "Divide by mean", "Z-score"},
                "Per-object min/max");
        dialog.addNumericField("Radial bins", 20, 0);
        dialog.addNumericField("Curve bins", 50, 0);
        dialog.addNumericField("Angular bins", 12, 0);
        dialog.addNumericField("Shells", 3, 0);
        dialog.addNumericField("Box padding (%)", 0, 1);
        dialog.addNumericField("Ring threshold (%)", 50, 1);
        dialog.addNumericField("Reference threshold", 0, 3);
        dialog.addNumericField("Partner threshold", 0, 3);
        addProfileClassFields(dialog);
        dialog.addMessage("Texture (slow; disabled by default)");
        dialog.addCheckbox("GLCM texture (slow)", false);
        dialog.addNumericField("GLCM grey levels", 32, 0);
        dialog.addNumericField("GLCM distance", 1, 0);
        dialog.addCheckbox("Texture classes (slow)", false);
        dialog.addNumericField("Texture classes (k)", 4, 0);
        dialog.addNumericField("Minimum texture voxels", 64, 0);
        dialog.addCheckbox("Zernike moments", false);
        dialog.addNumericField("Zernike degree", ZernikeMoments.DEFAULT_DEGREE, 0);
        dialog.addMessage("Optional fixed batch quantisation. Leave blank for automatic ranges.");
        for (int i = 1; i <= 4; i++) {
            dialog.addStringField("Raw " + i + " quant min", "", 10);
            dialog.addStringField("Raw " + i + " quant max", "", 10);
        }
        dialog.addCheckbox("Save aggregate figures", true);
        dialog.addCheckbox("Save texture class maps", true);
        boolean recording = Recorder.record;
        showWithoutRecording(dialog);
        if (dialog.wasCanceled()) return;

        OipBatchMacroOptions options = new OipBatchMacroOptions();
        options.labelFolder = labelDirectory;
        options.labelRegex = dialog.getNextString();
        for (int i = 0; i < 4; i++) {
            options.rawNames[i] = dialog.getNextString();
            options.rawFolders[i] = dialog.getNextString();
            options.rawRegexes[i] = dialog.getNextString();
        }
        int referenceSlot = dialog.getNextChoiceIndex();
        options.outputDirectory = dialog.getNextString();
        options.recursive = dialog.getNextBoolean();
        options.config.doRadial = dialog.getNextBoolean();
        options.config.doMarginal = dialog.getNextBoolean();
        options.config.doPrincipalAxis = dialog.getNextBoolean();
        options.config.doAngular = dialog.getNextBoolean();
        options.config.doShell = dialog.getNextBoolean();
        options.config.doWithinBox = dialog.getNextBoolean();
        options.config.region = dialog.getNextChoiceIndex() == 0
                ? OipConfig.Region.OBJECT_VOXELS : OipConfig.Region.WHOLE_BOX;
        options.config.intensityNorm = intensityNorm(dialog.getNextChoiceIndex());
        options.config.radialBins = exactInteger("Radial bins", dialog.getNextNumber());
        options.config.resampleN = exactInteger("Curve bins", dialog.getNextNumber());
        options.config.angularBins = exactInteger("Angular bins", dialog.getNextNumber());
        options.config.shells = exactInteger("Shells", dialog.getNextNumber());
        options.config.boxPadPct = dialog.getNextNumber();
        options.config.ringThresholdPct = dialog.getNextNumber();
        options.config.referenceThreshold = dialog.getNextNumber();
        options.config.partnerThreshold = dialog.getNextNumber();
        readProfileClassFields(dialog, options.config);
        options.config.doGlcm = dialog.getNextBoolean();
        options.config.glcmLevels = exactInteger(
                "GLCM grey levels", dialog.getNextNumber());
        options.config.glcmDistance = exactInteger(
                "GLCM distance", dialog.getNextNumber());
        options.config.doTextureClasses = dialog.getNextBoolean();
        options.config.textureClasses = exactInteger(
                "Texture classes", dialog.getNextNumber());
        options.config.minimumTextureVoxels = exactInteger(
                "Minimum texture voxels", dialog.getNextNumber());
        options.config.doZernike = dialog.getNextBoolean();
        options.config.zernikeDegree = exactInteger("Zernike degree", dialog.getNextNumber());
        for (int i = 0; i < 4; i++) {
            options.quantMin[i] = optionalNumber("Raw " + (i + 1) + " quant min",
                    dialog.getNextString());
            options.quantMax[i] = optionalNumber("Raw " + (i + 1) + " quant max",
                    dialog.getNextString());
        }
        options.validateQuantizationSlots();
        options.saveFigures = dialog.getNextBoolean();
        options.saveClassMaps = dialog.getNextBoolean();
        if (!OipMacroOptions.hasText(options.rawNames[referenceSlot])) {
            throw new IllegalArgumentException(
                    "The selected batch correlation reference raw slot is empty.");
        }
        options.referenceChannel = options.rawNames[referenceSlot];

        OipBatchParameters parameters = batchParameters(options);
        IJ.resetEscape();
        OipBatchRunner.PreparedBatch prepared = OipBatchRunner.prepare(parameters);
        GenericDialog confirmation = new GenericDialog("Confirm batch pairing");
        confirmation.addMessage(previewText(prepared.previewLines(), PREVIEW_LIMIT));
        confirmation.enableYesNoCancel("Run batch", "Back");
        showWithoutRecording(confirmation);
        if (confirmation.wasCanceled() || !confirmation.wasOKed()) return;

        if (recording) record(options.toMacroOptions());
        runBatch(parameters, prepared, options.hideDisplay);
    }

    /**
     * Join preview lines up to {@code limit} characters, saying how many samples were left out
     * rather than silently cutting a line.
     */
    static String previewText(List<String> lines, int limit) {
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (String line : lines) {
            if (shown > 0 && text.length() + 1 + line.length() > limit) break;
            if (shown > 0) text.append('\n');
            text.append(line);
            shown++;
        }
        int omitted = lines.size() - shown;
        if (omitted > 0) {
            text.append("\n... and ").append(omitted).append(omitted == 1
                    ? " more sample (" : " more samples (").append(lines.size())
                    .append(" in total)");
        }
        return text.toString();
    }

    private static final String[] OBJECT_SOURCES = {"Label image", "ROI set file"};

    private static final String[] PROFILE_CLASS_CURVES = {
        "Radial", "Shell", "Angular", "Principal major", "Marginal X", "Marginal Y"
    };

    private static void addProfileClassFields(GenericDialog dialog) {
        dialog.addCheckbox("Profile-shape classes", false);
        dialog.addChoice("Profile class curve", PROFILE_CLASS_CURVES, PROFILE_CLASS_CURVES[0]);
        dialog.addNumericField("Profile classes (k)", 4, 0);
    }

    private static void readProfileClassFields(GenericDialog dialog, OipConfig config) {
        config.doProfileClasses = dialog.getNextBoolean();
        config.profileClassFamily =
                ProfileShapeClassifier.Family.values()[dialog.getNextChoiceIndex()];
        config.profileClasses = exactInteger("Profile classes (k)", dialog.getNextNumber());
        OipConfigOptions.validate(config);
    }

    /** Record exactly one runnable line, replacing ImageJ's bare command line. */
    private static void record(String options) {
        Recorder.disableCommandRecording();
        Recorder.recordString("run(\"" + TITLE + "\", \"" + recorded(options) + "\");\n");
    }

    OipBatchParameters batchParameters(final OipBatchMacroOptions options) {
        options.validateQuantizationSlots();
        OipBatchParameters.Builder builder = OipBatchParameters.builder(
                        new File(options.labelFolder), options.labelRegex,
                        new File(options.outputDirectory))
                .recursive(options.recursive)
                .config(options.config)
                .referenceChannel(options.referenceChannel)
                .saveFigures(options.saveFigures)
                .saveClassMaps(options.saveClassMaps)
                .progressListener(new OipParameters.ProgressListener() {
                    @Override
                    public void onProgress(double fraction, String message) {
                        IJ.showStatus("Object Intensity Profiling batch: " + message);
                        IJ.showProgress(fraction);
                    }
                })
                .cancellationToken(new OipParameters.CancellationToken() {
                    @Override
                    public boolean isCancelled() {
                        return IJ.escapePressed();
                    }
                });
        for (int i = 0; i < 4; i++) {
            if (!OipMacroOptions.hasText(options.rawNames[i])
                    && !OipMacroOptions.hasText(options.rawRegexes[i])) continue;
            if (!OipMacroOptions.hasText(options.rawNames[i])
                    || !OipMacroOptions.hasText(options.rawRegexes[i])
                    || !OipMacroOptions.hasText(options.rawFolders[i])) {
                throw new IllegalArgumentException("Raw " + (i + 1)
                        + " needs a channel name, folder, and regular expression.");
            }
            builder.addRawChannel(options.rawNames[i], new File(options.rawFolders[i]),
                    options.rawRegexes[i]);
            QuantizationRange range = options.range(i);
            if (range != null) builder.quantizationRange(options.rawNames[i], range);
        }
        return builder.build();
    }

    private void runBatchOptions(OipBatchMacroOptions options) {
        IJ.resetEscape();
        OipBatchParameters parameters = batchParameters(options);
        runBatch(parameters, OipBatchRunner.prepare(parameters), options.hideDisplay);
    }

    private void runBatch(OipBatchParameters parameters,
                          OipBatchRunner.PreparedBatch prepared, boolean hideDisplay) {
        OipBatchResult result = OipBatchRunner.run(parameters, prepared);
        IJ.log(TITLE + ": batch complete; " + result.getSampleCount() + " samples, "
                + result.getObjectCount() + " objects; output "
                + result.getOutputDirectory().getAbsolutePath());
        if (!hideDisplay && !headless) {
            IJ.showMessage("Object Intensity Profiling",
                    "Batch complete.\nSamples: " + result.getSampleCount()
                            + "\nObjects: " + result.getObjectCount()
                            + "\nOutput: " + result.getOutputDirectory().getAbsolutePath());
        }
    }

    private void runMacro(String macro) {
        runOptions(OipMacroOptionsParser.parse(macro));
    }

    void runMacroOptions(String macro) {
        if (OipBatchMacroOptionsParser.isBatch(macro)) {
            runBatchOptions(OipBatchMacroOptionsParser.parse(macro));
        } else runMacro(macro);
    }

    private void runOptions(OipMacroOptions options) {
        options.validateQuantizationSlots();
        List<ImagePlus> opened = new ArrayList<ImagePlus>();
        try {
            boolean fromRois = OipMacroOptions.hasText(options.objectsRoi);
            ImagePlus labels = fromRois ? null
                    : resolve(options.labelsTitle, options.labelsPath, "label", opened);
            Map<String, ImagePlus> raw = new LinkedHashMap<String, ImagePlus>();
            Map<String, QuantizationRange> ranges =
                    new LinkedHashMap<String, QuantizationRange>();
            for (int i = 0; i < 4; i++) {
                if (!OipMacroOptions.hasText(options.rawTitles[i])
                        && !OipMacroOptions.hasText(options.rawPaths[i])) continue;
                ImagePlus image = resolve(options.rawTitles[i], options.rawPaths[i],
                        "raw " + (i + 1), opened);
                String name = OipMacroOptions.hasText(options.rawNames[i])
                        ? options.rawNames[i] : image.getTitle();
                if (raw.containsKey(name)) {
                    throw new IllegalArgumentException("Raw channel names must be unique: " + name);
                }
                raw.put(name, image);
                QuantizationRange range = options.range(i);
                if (range != null) ranges.put(name, range);
            }

            if (fromRois) {
                // The first raw image supplies the dimensions and calibration of the ROI labels.
                labels = roiLabels(raw.values().iterator().next(), options.objectsRoi);
            }
            String reference = OipMacroOptions.hasText(options.referenceChannel)
                    ? options.referenceChannel
                    : raw.size() == 1 ? raw.keySet().iterator().next() : null;
            OipParameters.Builder builder = OipParameters.builder(labels)
                    .rawImages(raw)
                    .sourceName(options.sourceName)
                    .referenceChannel(reference)
                    .config(options.config)
                    .saveFigures(options.saveFigures)
                    .saveClassMaps(options.saveClassMaps)
                    .progressListener(new OipParameters.ProgressListener() {
                        @Override
                        public void onProgress(double fraction, String message) {
                            IJ.showStatus("Object Intensity Profiling: " + message);
                            IJ.showProgress(fraction);
                        }
                    })
                    .cancellationToken(new OipParameters.CancellationToken() {
                        @Override
                        public boolean isCancelled() {
                            return IJ.escapePressed();
                        }
                    });
            for (Map.Entry<String, QuantizationRange> range : ranges.entrySet()) {
                builder.quantizationRange(range.getKey(), range.getValue());
            }
            if (OipMacroOptions.hasText(options.regionRoi)) {
                builder.regionRois(regionRois(options.regionRoi),
                        new File(options.regionRoi).getName());
            }
            if (options.autoSave) builder.autoSave(new File(options.outputDirectory));
            IJ.resetEscape();
            OipResult result = ObjectIntensityProfiling.run(builder.build());
            if (!options.hideDisplay && !headless) show(result);
            IJ.showStatus("Object Intensity Profiling complete: "
                    + result.getProfiles().size() + " objects");
        } finally {
            for (ImagePlus image : opened) {
                image.changes = false;
                image.close();
            }
        }
    }

    private static void show(OipResult result) {
        ResultsTable summaries = OipTables.summaries(result);
        if (summaries.size() > 0) summaries.show("Object Intensity Profiles");
        OipConfig config = result.getParameters().getConfig();
        if (config.doGlcm || config.doTextureClasses) {
            ResultsTable textures = OipTables.textures(result);
            if (textures.size() > 0) textures.show("Object Texture");
        }
        ResultsTable profileClasses = OipTables.profileClasses(result);
        if (profileClasses.size() > 0) profileClasses.show("Object Profile Classes");
        ResultsTable zernike = OipTables.zernike(result);
        if (zernike.size() > 0) zernike.show("Object Zernike");
        for (ImagePlus figure : aggregateFigures(result)) figure.show();
        for (ImagePlus map : result.getClassMaps().values()) map.show();
    }

    static List<ImagePlus> aggregateFigures(OipResult result) {
        ProfileAggregator aggregate = new ProfileAggregator();
        for (ObjectProfileResult profile : result.getProfiles()) {
            aggregate.addAll(profile, result.getParameters().getGroupKey(),
                    result.getParameters().getCancellationToken());
        }
        return ObjectProfileFigureWriter.createFigures(
                aggregate, null, result.getParameters().getCancellationToken());
    }

    private static ImagePlus roiLabels(ImagePlus reference, String path) {
        try {
            return OipRoiInputs.labelsFromRoiSet(reference, path);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the object ROI set " + path
                    + ": " + e.getMessage(), e);
        }
    }

    private static ij.gui.Roi[] regionRois(String path) {
        try {
            return OipRoiInputs.regionRois(path);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the region ROI set " + path
                    + ": " + e.getMessage(), e);
        }
    }

    private static ImagePlus resolve(String title, String path, String role,
                                     List<ImagePlus> opened) {
        if (OipMacroOptions.hasText(path)) {
            ImagePlus image = IJ.openImage(path);
            if (image == null) {
                throw new IllegalArgumentException("Could not open " + role + " image: " + path);
            }
            opened.add(image);
            return image;
        }
        return openImageByTitle(title, role);
    }

    /** Find the one open image with this exact title; ambiguous titles are an error. */
    static ImagePlus openImageByTitle(String title, String role) {
        int[] ids = WindowManager.getIDList();
        ImagePlus found = null;
        if (ids != null) {
            for (int id : ids) {
                ImagePlus candidate = WindowManager.getImage(id);
                if (candidate == null || !candidate.getTitle().equals(title)) continue;
                if (found != null) throw duplicateTitle(title);
                found = candidate;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("No open image is titled \"" + title
                    + "\" (" + role + " image).");
        }
        return found;
    }

    static void requireUniqueTitles(String[] titles) {
        Set<String> seen = new HashSet<String>();
        for (String title : titles) {
            if (!seen.add(title)) throw duplicateTitle(title);
        }
    }

    private static IllegalArgumentException duplicateTitle(String title) {
        return new IllegalArgumentException("More than one open image is titled \"" + title
                + "\"; rename one (Image > Rename...) so the right image is used.");
    }

    /** One-line message; wrapped causes (such as the I/O error behind a failed save) are kept. */
    static String message(Throwable error) {
        String message = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            String detail = cause.getMessage() == null
                    ? cause.getClass().getSimpleName() : cause.getMessage();
            if (!message.contains(detail)) message = message + " (" + detail + ")";
        }
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private static Double optionalNumber(String label, String text) {
        if (!OipMacroOptions.hasText(text)) return null;
        try {
            return Double.valueOf(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be numeric or blank.");
        }
    }

    static int exactInteger(String name, double value) {
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be an exact whole number.");
        }
        return (int) value;
    }

    private static OipConfig.IntensityNorm intensityNorm(int index) {
        if (index == 1) return OipConfig.IntensityNorm.DIVIDE_BY_MEAN;
        if (index == 2) return OipConfig.IntensityNorm.ZSCORE;
        return OipConfig.IntensityNorm.PER_OBJECT_MINMAX;
    }

    /** Escape an options string for inclusion inside an ImageJ macro string literal. */
    static String recorded(String options) {
        return options.replace("\\", "\\\\");
    }

    private static void showWithoutRecording(GenericDialog dialog) {
        boolean recording = Recorder.record;
        try {
            if (recording) Recorder.record = false;
            dialog.showDialog();
        } finally {
            if (recording) Recorder.record = true;
        }
    }
}
