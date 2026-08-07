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
import oip.io.OipOutputWriter;
import oip.io.FileNameSupport;
import oip.io.OutputDirectoryLock;
import oip.profile.ObjectProfileResult;
import oip.profile.ObjectProfileFigureWriter;
import oip.profile.OipConfig;
import oip.profile.ProfileAggregator;
import oip.profile.LabelObjects;
import oip.texture.ObjectTextureAnalyzer;
import oip.texture.ObjectTextureResult;
import oip.texture.QuantizationRange;
import sc.fiji.oc3d.core.io.RegexGroupDiscovery;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pairs label and raw image files, fixes quantisation across the batch, and aggregates by object.
 */
public final class OipBatchRunner {

    private static final String SAMPLE_OWNER_FILE = ".OIP_owned_sample";
    private static final String SAMPLE_OWNER_MAGIC = "Object Intensity Profiling sample v1";

    private OipBatchRunner() {
    }

    public static String preview(OipBatchParameters parameters) {
        List<Pairing> pairings = pair(parameters);
        StringBuilder preview = new StringBuilder();
        for (Pairing pairing : pairings) {
            if (preview.length() > 0) preview.append('\n');
            preview.append(pairing.key).append(": ").append(pairing.label.getName());
            for (Map.Entry<String, File> raw : pairing.raw.entrySet()) {
                preview.append(" | ").append(raw.getKey()).append('=').append(raw.getValue().getName());
            }
        }
        return preview.toString();
    }

    public static OipBatchResult run(OipBatchParameters parameters) {
        List<Pairing> pairings = pair(parameters);
        progress(parameters, 0.01, "Scanning fixed quantisation ranges");
        Map<String, QuantizationRange> ranges = scanRanges(parameters, pairings);
        checkCancelled(parameters);

        File outputDirectory = canonicalOutput(parameters.getOutputDirectory());
        if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
            throw new IllegalStateException(
                    "Could not create output directory: " + outputDirectory);
        }
        File stagingRoot = new File(outputDirectory,
                ".OIP_staging_" + UUID.randomUUID().toString());
        if (!stagingRoot.mkdir()) {
            throw new IllegalStateException(
                    "Could not create batch staging directory: " + stagingRoot);
        }

        int objectCount = 0;
        try {
            OipConfig config = parameters.getConfig();
            List<RunRecord> runs = new ArrayList<RunRecord>();
            List<ObjectTextureResult> allTextures = new ArrayList<ObjectTextureResult>();
            ProfileAggregator aggregate = new ProfileAggregator();
            File samplesDirectory = new File(stagingRoot, "Samples");
            if (!samplesDirectory.isDirectory() && !samplesDirectory.mkdirs()) {
                throw new IllegalStateException(
                        "Could not create staged sample output directory: " + samplesDirectory);
            }
            for (int i = 0; i < pairings.size(); i++) {
                Pairing pairing = pairings.get(i);
                progress(parameters, 0.15 + 0.65 * i / Math.max(1, pairings.size()),
                        "Analysing " + pairing.key);
                OipResult result = analyze(parameters, pairing, ranges, i, pairings.size());
                objectCount += objectCount(result);
                for (ObjectProfileResult profile : result.getProfiles()) {
                    checkCancelled(parameters);
                    aggregate.addAll(profile, "(batch)", parameters.getCancellationToken());
                }
                if (config.doTextureClasses) {
                    List<ObjectTextureResult> textures =
                            new ArrayList<ObjectTextureResult>(result.getTextures());
                    runs.add(new RunRecord(pairing, textures));
                    allTextures.addAll(textures);
                }
                try {
                    // Stream large per-object results into the isolated staging tree. Live results
                    // are not touched until every sample and the aggregate have succeeded.
                    OipOutputWriter.save(result,
                            new File(samplesDirectory, FileNameSupport.encode(pairing.key)),
                            parameters.getCancellationToken());
                    writeSampleOwnership(samplesDirectory,
                            FileNameSupport.encode(pairing.key));
                } catch (IOException e) {
                    throw new IllegalStateException("Could not stage sample " + pairing.key, e);
                }
                checkCancelled(parameters);
            }

            if (config.doTextureClasses) {
                progress(parameters, 0.82, "Fitting texture classes across the batch");
                ObjectTextureAnalyzer.assignClasses(allTextures, config.textureClasses,
                        parameters.getCancellationToken());
                for (int i = 0; i < runs.size(); i++) {
                    RunRecord run = runs.get(i);
                    progress(parameters, 0.86 + 0.10 * i / Math.max(1, runs.size()),
                            "Saving texture classes for " + run.pairing.key);
                    ImagePlus labels = open(run.pairing.label, "label");
                    Map<String, ImagePlus> maps;
                    try {
                        maps = parameters.isSaveClassMaps()
                                ? TextureClassMapRenderer.render(labels, run.textures,
                                        parameters.getCancellationToken())
                                : new LinkedHashMap<String, ImagePlus>();
                    } finally {
                        release(labels);
                    }
                    try {
                        OipOutputWriter.saveTextureOutputs(
                                run.textures, ranges, maps, parameters.isSaveClassMaps(),
                                new File(samplesDirectory,
                                        FileNameSupport.encode(run.pairing.key)),
                                parameters.getCancellationToken());
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                "Could not stage texture classes for sample "
                                        + run.pairing.key, e);
                    } finally {
                        for (ImagePlus map : maps.values()) release(map);
                    }
                    checkCancelled(parameters);
                }
            }
            try {
                OipOutputWriter.saveAggregate(
                        aggregate, stagingRoot, parameters.isSaveFigures(),
                        parameters.getCancellationToken());
                writeSampleManifest(samplesDirectory, pairings);
            } catch (IOException e) {
                throw new IllegalStateException("Could not stage batch aggregate.", e);
            }
            checkCancelled(parameters);
            List<String> sampleOutputNames = new ArrayList<String>();
            for (Pairing pairing : pairings) {
                sampleOutputNames.add(FileNameSupport.encode(pairing.key));
            }
            promoteStagedRun(parameters, stagingRoot, sampleOutputNames, null);
            progress(parameters, 1.0, "Complete");
            List<String> keys = new ArrayList<String>();
            for (Pairing pairing : pairings) keys.add(pairing.key);
            return new OipBatchResult(keys, objectCount, parameters.getOutputDirectory());
        } finally {
            cleanupTree(stagingRoot);
        }
    }

    private static OipResult analyze(final OipBatchParameters parameters, Pairing pairing,
                                     Map<String, QuantizationRange> ranges,
                                     int sampleIndex, int sampleCount) {
        ImagePlus labels = open(pairing.label, "label");
        Map<String, ImagePlus> rawImages = new LinkedHashMap<String, ImagePlus>();
        try {
            for (Map.Entry<String, File> raw : pairing.raw.entrySet()) {
                rawImages.put(raw.getKey(), open(raw.getValue(), raw.getKey()));
            }
            final double start = 0.15 + 0.65 * sampleIndex / Math.max(1, sampleCount);
            final double end = 0.15 + 0.65 * (sampleIndex + 1) / Math.max(1, sampleCount);
            OipParameters.Builder builder = OipParameters.builder(labels)
                    .sourceName("Labels")
                    .rawImages(rawImages)
                    .referenceChannel(parameters.getReferenceChannel())
                    .config(parameters.getConfig())
                    .groupKey(pairing.key)
                    .saveFigures(parameters.isSaveFigures())
                    // Batch classes are fitted globally after every sample is measured. Rendering
                    // maps here would retain one redundant full stack per sample with provisional
                    // per-image classes.
                    .saveClassMaps(false)
                    .progressListener(new OipParameters.ProgressListener() {
                        @Override
                        public void onProgress(double fraction, String message) {
                            progress(parameters, start + Math.max(0.0, Math.min(1.0, fraction))
                                    * (end - start), message);
                        }
                    })
                    .cancellationToken(parameters.getCancellationToken());
            for (Map.Entry<String, QuantizationRange> range : ranges.entrySet()) {
                builder.quantizationRange(range.getKey(), range.getValue());
            }
            return ObjectIntensityProfiling.runWithDeferredTextureClasses(builder.build());
        } finally {
            release(labels);
            for (ImagePlus raw : rawImages.values()) release(raw);
        }
    }

    private static Map<String, QuantizationRange> scanRanges(
            OipBatchParameters parameters, List<Pairing> pairings) {
        Map<String, QuantizationRange> ranges =
                new LinkedHashMap<String, QuantizationRange>(parameters.getQuantizationRanges());
        if (!parameters.getConfig().doGlcm) return ranges;
        int totalFiles = Math.max(1, pairings.size() * parameters.getRawSpecs().size());
        int fileIndex = 0;
        for (Pairing pairing : pairings) {
            for (Map.Entry<String, File> entry : pairing.raw.entrySet()) {
                if (parameters.getQuantizationRanges().containsKey(entry.getKey())) {
                    fileIndex++;
                    continue;
                }
                ImagePlus image = open(entry.getValue(), entry.getKey());
                try {
                    final int currentFile = fileIndex;
                    QuantizationRange imageRange = QuantizationRange.scan(
                            image, parameters.getCancellationToken(),
                            new OipParameters.ProgressListener() {
                                @Override
                                public void onProgress(double fraction, String message) {
                                    progress(parameters, 0.01 + 0.13
                                            * (currentFile + fraction) / totalFiles,
                                            "Scanning fixed range");
                                }
                            });
                    QuantizationRange current = ranges.get(entry.getKey());
                    ranges.put(entry.getKey(), current == null
                            ? imageRange : current.include(imageRange));
                } finally {
                    release(image);
                }
                fileIndex++;
            }
            checkCancelled(parameters);
        }
        return ranges;
    }

    private static List<Pairing> pair(OipBatchParameters parameters) {
        validate(parameters);
        File outputDirectory = canonicalOutput(parameters.getOutputDirectory());
        Pattern labelPattern = pattern(parameters.getLabelRegex(), "label");
        Map<String, File> labels = index(
                parameters.getLabelFolder(), labelPattern, parameters.isRecursive(), "label",
                parameters.getCancellationToken(), outputDirectory);
        List<Map<String, File>> rawIndexes = new ArrayList<Map<String, File>>();
        for (OipBatchParameters.RawSpec raw : parameters.getRawSpecs()) {
            rawIndexes.add(index(raw.folder, pattern(raw.regex, raw.channelName),
                    parameters.isRecursive(), raw.channelName,
                    parameters.getCancellationToken(), outputDirectory));
        }
        for (int i = 0; i < rawIndexes.size(); i++) {
            OipBatchParameters.RawSpec spec = parameters.getRawSpecs().get(i);
            for (String rawKey : rawIndexes.get(i).keySet()) {
                if (!labels.containsKey(rawKey)) {
                    throw new IllegalArgumentException(
                            "No label image matched " + spec.channelName
                                    + " raw sample: " + rawKey);
                }
            }
        }

        List<Pairing> pairings = new ArrayList<Pairing>();
        for (Map.Entry<String, File> label : labels.entrySet()) {
            checkCancelled(parameters);
            Map<String, File> raw = new LinkedHashMap<String, File>();
            for (int i = 0; i < parameters.getRawSpecs().size(); i++) {
                OipBatchParameters.RawSpec spec = parameters.getRawSpecs().get(i);
                File match = rawIndexes.get(i).get(label.getKey());
                if (match == null) {
                    throw new IllegalArgumentException("No " + spec.channelName
                            + " raw image matched sample: " + label.getKey());
                }
                raw.put(spec.channelName, match);
            }
            pairings.add(new Pairing(label.getKey(), label.getValue(), raw));
        }
        if (pairings.isEmpty()) {
            throw new IllegalArgumentException("No label files matched: " + parameters.getLabelRegex());
        }
        Collections.sort(pairings, new Comparator<Pairing>() {
            @Override
            public int compare(Pairing left, Pairing right) {
                return left.key.compareTo(right.key);
            }
        });
        List<String> sampleKeys = new ArrayList<String>();
        for (Pairing pairing : pairings) sampleKeys.add(pairing.key);
        FileNameSupport.requireUniqueEncoded(sampleKeys, "Sample");
        preflight(parameters, pairings);
        return pairings;
    }

    private static Map<String, File> index(File folder, Pattern pattern,
                                           boolean recursive, String role,
                                           OipParameters.CancellationToken cancellation,
                                           File excludedDirectory) {
        validateInputTree(folder, recursive, cancellation, excludedDirectory,
                new LinkedHashMap<String, Boolean>());
        List<File> files = new ArrayList<File>();
        Map<String, Map<String, List<File>>> discovered =
                RegexGroupDiscovery.findGroupsRecursive(
                        folder,
                        pattern,
                        0,
                        recursive,
                        RegexGroupDiscovery.GroupOrder.FILENAME,
                        Collections.singleton(excludedDirectory));
        for (Map<String, List<File>> folderGroups : discovered.values()) {
            for (List<File> groupFiles : folderGroups.values()) {
                files.addAll(groupFiles);
            }
        }
        checkCancelled(cancellation);
        Collections.sort(files);
        Map<String, File> index = new LinkedHashMap<String, File>();
        for (File file : files) {
            checkCancelled(cancellation);
            Matcher matcher = pattern.matcher(file.getName());
            if (!matcher.matches()) continue;
            String key = matcher.groupCount() > 0 ? matcher.group(1) : stem(file.getName());
            if (key == null || key.trim().length() == 0) {
                throw new IllegalArgumentException(
                        role + " regular expression produced an empty sample key for: "
                                + file.getName());
            }
            if (index.put(key, file) != null) {
                throw new IllegalArgumentException("More than one " + role
                        + " file has sample key: " + key);
            }
        }
        return index;
    }

    private static void preflight(
            OipBatchParameters parameters, List<Pairing> pairings) {
        for (Pairing pairing : pairings) {
            checkCancelled(parameters);
            ImagePlus labels = open(pairing.label, "label");
            try {
                if (labels.getStack() == null || labels.getStackSize() == 0
                        || labels.getNChannels() != 1 || labels.getNFrames() != 1) {
                    throw new IllegalArgumentException(
                            "Label input must be single-channel and single-timepoint for sample: "
                                    + pairing.key);
                }
                if (!LabelObjects.validate(labels, parameters.getCancellationToken())) {
                    throw new IllegalArgumentException(
                            "Label image contains no positive object labels for sample: "
                                    + pairing.key);
                }
                for (Map.Entry<String, File> entry : pairing.raw.entrySet()) {
                    checkCancelled(parameters);
                    ImagePlus raw = open(entry.getValue(), entry.getKey());
                    try {
                        if (raw.getStack() == null || raw.getStackSize() == 0
                                || raw.getNChannels() != 1 || raw.getNFrames() != 1) {
                            throw new IllegalArgumentException(
                                    "Raw input must be single-channel and single-timepoint: "
                                            + entry.getKey() + " for sample " + pairing.key);
                        }
                        if (raw.getWidth() != labels.getWidth()
                                || raw.getHeight() != labels.getHeight()
                                || raw.getNSlices() != labels.getNSlices()) {
                            throw new IllegalArgumentException(
                                    "Raw image dimensions do not match the label image: "
                                            + entry.getKey() + " for sample " + pairing.key);
                        }
                    } finally {
                        release(raw);
                    }
                }
            } finally {
                release(labels);
            }
        }
    }

    /**
     * Preserves OIP's stricter input policy before the shared chassis performs
     * discovery: links and redirected paths are rejected rather than skipped,
     * and cancellation remains observable while a large tree is inspected.
     */
    private static void validateInputTree(
            File folder, boolean recursive,
            OipParameters.CancellationToken cancellation,
            File excludedDirectory,
            Map<String, Boolean> visitedDirectories) {
        checkCancelled(cancellation);
        rejectLinkedInputDirectory(folder);
        File canonicalFolder = canonical(folder, "input");
        String identity = canonicalFolder.getAbsolutePath();
        if (visitedDirectories.put(identity, true) != null) {
            throw new IllegalArgumentException(
                    "Recursive input contains a directory cycle or alias: " + folder);
        }
        File[] children = folder.listFiles();
        if (children == null) return;
        for (File child : children) {
            checkCancelled(cancellation);
            java.nio.file.attribute.BasicFileAttributes attributes =
                    inputAttributes(child);
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || java.nio.file.Files.isSymbolicLink(child.toPath())) {
                throw new IllegalArgumentException(
                        "Linked or redirected input paths are not allowed: " + child);
            }
            if (recursive && attributes.isDirectory()) {
                File canonicalChild = canonical(child, "input");
                if (canonicalChild.equals(excludedDirectory)) continue;
                if (!canonicalFolder.equals(canonicalChild.getParentFile())) {
                    throw new IllegalArgumentException(
                            "Recursive input directory is redirected: " + child);
                }
                validateInputTree(child, true, cancellation,
                        excludedDirectory, visitedDirectories);
            }
        }
    }

    private static void rejectLinkedInputDirectory(File folder) {
        java.nio.file.attribute.BasicFileAttributes attributes =
                inputAttributes(folder);
        if (!attributes.isDirectory()) {
            throw new IllegalArgumentException("Input folder is not a directory: " + folder);
        }
        if (attributes.isSymbolicLink() || attributes.isOther()
                || java.nio.file.Files.isSymbolicLink(folder.toPath())) {
            throw new IllegalArgumentException(
                    "Linked or redirected input directories are not allowed: " + folder);
        }
    }

    private static java.nio.file.attribute.BasicFileAttributes inputAttributes(File path) {
        try {
            return java.nio.file.Files.readAttributes(path.toPath(),
                    java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not inspect input path: " + path, e);
        }
    }

    private static void checkCancelled(OipParameters.CancellationToken cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ObjectIntensityProfiling.AnalysisCancelledException();
        }
    }

    private static void validate(OipBatchParameters parameters) {
        if (parameters == null) throw new IllegalArgumentException("Batch parameters are required.");
        if (parameters.getLabelFolder() == null || !parameters.getLabelFolder().isDirectory()) {
            throw new IllegalArgumentException("Label folder does not exist.");
        }
        if (parameters.getLabelRegex() == null
                || parameters.getLabelRegex().trim().length() == 0) {
            throw new IllegalArgumentException("Label regular expression is required.");
        }
        if (parameters.getOutputDirectory() == null) {
            throw new IllegalArgumentException("Output directory is required.");
        }
        File outputDirectory = canonicalOutput(parameters.getOutputDirectory());
        if (isWithin(canonical(parameters.getLabelFolder(), "label"), outputDirectory)) {
            throw new IllegalArgumentException(
                    "Input folders must not be inside the output directory.");
        }
        if (parameters.getRawSpecs().isEmpty() || parameters.getRawSpecs().size() > 4) {
            throw new IllegalArgumentException("Configure between 1 and 4 raw channels.");
        }
        Map<String, Boolean> names = new LinkedHashMap<String, Boolean>();
        List<String> rawNames = new ArrayList<String>();
        for (OipBatchParameters.RawSpec spec : parameters.getRawSpecs()) {
            if (spec.folder != null
                    && isWithin(canonical(spec.folder, spec.channelName), outputDirectory)) {
                throw new IllegalArgumentException(
                        "Input folders must not be inside the output directory.");
            }
            if (spec.channelName == null || spec.channelName.trim().length() == 0) {
                throw new IllegalArgumentException("Raw channel names must not be empty.");
            }
            if (spec.regex == null || spec.regex.trim().length() == 0) {
                throw new IllegalArgumentException(
                        "Raw regular expression is required: " + spec.channelName);
            }
            if (names.put(spec.channelName, true) != null) {
                throw new IllegalArgumentException("Raw channel names must be unique.");
            }
            rawNames.add(spec.channelName);
            if (spec.folder == null || !spec.folder.isDirectory()) {
                throw new IllegalArgumentException("Raw folder does not exist: " + spec.channelName);
            }
        }
        FileNameSupport.requireUniqueEncoded(rawNames, "Raw channel");
        if (parameters.getReferenceChannel() == null) {
            throw new IllegalArgumentException(
                    "Choose an explicit correlation reference for a multi-channel batch.");
        }
        if (!names.containsKey(parameters.getReferenceChannel())) {
            throw new IllegalArgumentException(
                    "Batch correlation reference is not a configured raw channel: "
                            + parameters.getReferenceChannel());
        }
        for (String channel : parameters.getQuantizationRanges().keySet()) {
            if (!names.containsKey(channel)) {
                throw new IllegalArgumentException(
                        "Quantisation range is not for a configured raw channel: " + channel);
            }
        }
        OipConfig config = parameters.getConfig();
        if (config.radialBins < 1 || config.angularBins < 1 || config.shells < 1
                || config.resampleN < 1 || config.glcmLevels < 2 || config.glcmLevels > 256
                || config.glcmDistance < 1 || config.textureClasses < 1
                || config.textureClasses > 255 || config.minimumTextureVoxels < 1
                || !Double.isFinite(config.boxPadPct) || config.boxPadPct < 0
                || !Double.isFinite(config.ringThresholdPct)
                || config.ringThresholdPct < 0 || config.ringThresholdPct > 100
                || !Double.isFinite(config.referenceThreshold)
                || !Double.isFinite(config.partnerThreshold)
                || config.region == null || config.intensityNorm == null) {
            throw new IllegalArgumentException("Batch analysis settings are invalid.");
        }
        if (config.radialBins > 10000 || config.angularBins > 10000
                || config.resampleN > 10000 || config.shells > 10000) {
            throw new IllegalArgumentException(
                    "Requested batch profile bin count is too large.");
        }
        if (!config.anyProfileEnabled() && !config.doGlcm && !config.doTextureClasses) {
            throw new IllegalArgumentException("Enable at least one profile or texture measurement.");
        }
    }

    private static File canonical(File file, String role) {
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Could not resolve " + role + " directory: " + file, e);
        }
    }

    private static File canonicalOutput(File file) {
        if (file == null) {
            throw new IllegalArgumentException("Output directory is required.");
        }
        java.nio.file.Path path = file.getAbsoluteFile().toPath().normalize();
        if (java.nio.file.Files.exists(
                path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            java.nio.file.attribute.BasicFileAttributes attributes = inputAttributes(
                    path.toFile());
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || java.nio.file.Files.isSymbolicLink(path)) {
                throw new IllegalArgumentException(
                        "Linked or redirected output directories are not allowed: " + file);
            }
            if (!attributes.isDirectory()) {
                throw new IllegalArgumentException(
                        "Output path is not a directory: " + file);
            }
        }
        return canonical(path.toFile(), "output");
    }

    private static boolean isWithin(File candidate, File directory) {
        return candidate.toPath().startsWith(directory.toPath());
    }

    private static Pattern pattern(String regex, String role) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid " + role + " regular expression: "
                    + e.getMessage(), e);
        }
    }

    private static ImagePlus open(File file, String role) {
        ImagePlus image = IJ.openImage(file.getAbsolutePath());
        if (image == null) throw new IllegalArgumentException(
                "Could not open " + role + " image: " + file);
        return image;
    }

    private static void release(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    private static void progress(OipBatchParameters parameters, double fraction, String message) {
        if (parameters.getProgressListener() != null) {
            parameters.getProgressListener().onProgress(fraction, message);
        }
    }

    private static void checkCancelled(OipBatchParameters parameters) {
        if (parameters.getCancellationToken() != null
                && parameters.getCancellationToken().isCancelled()) {
            throw new ObjectIntensityProfiling.AnalysisCancelledException();
        }
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static int objectCount(OipResult result) {
        if (!result.getProfiles().isEmpty()) return result.getProfiles().size();
        Map<Integer, Boolean> labels = new LinkedHashMap<Integer, Boolean>();
        String firstPartner = null;
        for (ObjectTextureResult texture : result.getTextures()) {
            if (firstPartner == null) firstPartner = texture.partnerChannel;
            if (firstPartner.equals(texture.partnerChannel)) labels.put(texture.label, true);
        }
        return labels.size();
    }

    private static void writeSampleManifest(
            File samplesDirectory, List<Pairing> pairings) throws IOException {
        File manifest = new File(samplesDirectory, ".OIP_samples_manifest.txt");
        List<String> current = new ArrayList<String>();
        for (Pairing pairing : pairings) current.add(FileNameSupport.encode(pairing.key));
        java.nio.file.Files.write(manifest.toPath(), current,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Replace only plugin-owned live outputs after the complete staged run succeeds.
     * Existing owned paths are moved to a backup first and restored on any failure.
     */
    static void promoteStagedRun(
            OipBatchParameters parameters, File stagingRoot,
            List<String> currentSamples, PromotionFault fault) {
        File output = canonicalOutput(parameters.getOutputDirectory());
        try (OutputDirectoryLock ignored = OutputDirectoryLock.acquire(output)) {
            promoteStagedRunLocked(
                    parameters, stagingRoot, currentSamples, fault, output);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not lock the batch output directory for publication.", e);
        }
    }

    private static void promoteStagedRunLocked(
            OipBatchParameters parameters, File stagingRoot,
            List<String> currentSamples, PromotionFault fault, File output) {
        File liveSamples = new File(output, "Samples");
        File stagedSamples = new File(stagingRoot, "Samples");
        File liveManifest = new File(liveSamples, ".OIP_samples_manifest.txt");
        File stagedManifest = new File(stagedSamples, ".OIP_samples_manifest.txt");
        File liveAggregate = new File(output, "Aggregate");
        File liveFigures = new File(output, "Figures");
        File stagedAggregate = new File(stagingRoot, "Aggregate");
        File stagedFigures = new File(stagingRoot, "Figures");
        File backupRoot = new File(output,
                ".OIP_backup_" + UUID.randomUUID().toString());
        List<PathMove> restore = new ArrayList<PathMove>();
        List<File> installed = new ArrayList<File>();
        boolean committed = false;
        try {
            validateContainer(stagingRoot, output, "batch staging");
            validateContainer(stagedSamples, stagingRoot, "staged samples");
            validateContainer(stagedAggregate, stagingRoot, "staged aggregate");
            validateContainer(stagedFigures, stagingRoot, "staged figures");
            validateContainer(liveSamples, output, "live samples");
            validateContainer(liveAggregate, output, "live aggregate");
            validateContainer(liveFigures, output, "live figures");
            if (!backupRoot.mkdir()) {
                throw new IOException("Could not create promotion backup: " + backupRoot);
            }
            if (!liveSamples.isDirectory() && !liveSamples.mkdirs()) {
                throw new IOException("Could not create live sample directory: " + liveSamples);
            }

            Map<String, Boolean> manifestedSamples = new LinkedHashMap<String, Boolean>();
            Map<String, String> manifestedByDiskIdentity =
                    new LinkedHashMap<String, String>();
            if (java.nio.file.Files.exists(liveManifest.toPath(),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                rejectLinkOrReparsePoint(liveManifest);
                if (!liveManifest.isFile()) {
                    throw new IOException(
                            "Batch sample manifest is not a regular file: " + liveManifest);
                }
                for (String encoded : java.nio.file.Files.readAllLines(
                        liveManifest.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                    if (!FileNameSupport.isCanonicalEncoding(encoded)) {
                        throw new IOException(
                                "Batch sample manifest contains an invalid path entry: "
                                        + encoded);
                    }
                    manifestedSamples.put(encoded, true);
                    String folded = diskIdentity(encoded);
                    String previous = manifestedByDiskIdentity.put(folded, encoded);
                    if (previous != null && !previous.equals(encoded)) {
                        throw new IOException(
                                "Batch sample manifest contains names that collide on disk: "
                                        + previous + " and " + encoded);
                    }
                }
            }
            for (String encoded : currentSamples) {
                if (!FileNameSupport.isCanonicalEncoding(encoded)) {
                    throw new IOException("Current sample output name is invalid: " + encoded);
                }
                if (!ownsSample(new File(stagedSamples, encoded), encoded)) {
                    throw new IOException(
                            "Staged sample is missing its ownership marker: " + encoded);
                }
            }
            for (String encoded : manifestedSamples.keySet()) {
                File live = new File(liveSamples, encoded);
                if (live.exists() && !ownsSample(live, encoded)) {
                    throw new IOException(
                            "Manifest-listed sample lacks a valid ownership marker: " + live);
                }
            }
            for (String encoded : currentSamples) {
                String previous = manifestedByDiskIdentity.get(diskIdentity(encoded));
                File live = new File(liveSamples, encoded);
                if (live.exists() && (previous == null || !ownsSample(live, previous))) {
                    throw new IOException(
                            "Refusing to replace unmanifested sample path: " + live);
                }
            }

            Map<String, String> ownedSamples =
                    new LinkedHashMap<String, String>(manifestedByDiskIdentity);
            for (String encoded : currentSamples) {
                String folded = diskIdentity(encoded);
                if (!ownedSamples.containsKey(folded)) {
                    ownedSamples.put(folded, encoded);
                }
            }
            List<String> previousFigures =
                    ObjectProfileFigureWriter.ownedFigureNames(liveFigures);
            List<String> currentFigures =
                    ObjectProfileFigureWriter.ownedFigureNames(stagedFigures);
            // Samples are guarded above by their ownership markers; figures are guarded by the
            // live figure manifest. Without this a figure name that collides with a file the
            // manifest does not list would be replaced during promotion, because only manifested
            // names are backed up and ATOMIC_MOVE overwrites an existing regular file.
            // Validate every target BEFORE moving anything. Each of these is a promotion target
            // that gets backed up and then deleted on commit, so a directory sitting at one of
            // these paths would be carried off wholesale with everything inside it. Sample paths
            // are excluded — they are directories by design, guarded by ownership markers above.
            // Validating first means a rejection needs no rollback at all.
            validateNoUnownedFigureTargets(liveFigures, currentFigures, previousFigures);
            validateRegularIfPresent(
                    new File(liveAggregate, "Aggregate_Profiles.csv"), "live aggregate table");
            for (String figure : previousFigures) {
                validateRegularIfPresent(new File(liveFigures, figure), "live figure");
            }
            validateRegularIfPresent(
                    ObjectProfileFigureWriter.figureManifest(liveFigures), "live figure manifest");

            for (String encoded : ownedSamples.values()) {
                backupIfPresent(new File(liveSamples, encoded),
                        new File(new File(backupRoot, "Samples"), encoded), restore);
            }
            backupIfPresent(liveManifest,
                    new File(new File(backupRoot, "Samples"), liveManifest.getName()), restore);
            backupIfPresent(new File(liveAggregate, "Aggregate_Profiles.csv"),
                    new File(new File(backupRoot, "Aggregate"),
                            "Aggregate_Profiles.csv"), restore);
            for (String figure : previousFigures) {
                backupIfPresent(new File(liveFigures, figure),
                        new File(new File(backupRoot, "Figures"), figure), restore);
            }
            backupIfPresent(ObjectProfileFigureWriter.figureManifest(liveFigures),
                    ObjectProfileFigureWriter.figureManifest(
                            new File(backupRoot, "Figures")), restore);
            checkCancelled(parameters);

            for (String encoded : currentSamples) {
                install(new File(stagedSamples, encoded),
                        new File(liveSamples, encoded), installed, fault);
            }
            install(new File(stagedAggregate, "Aggregate_Profiles.csv"),
                    new File(liveAggregate, "Aggregate_Profiles.csv"), installed, fault);
            for (String figure : currentFigures) {
                install(new File(stagedFigures, figure),
                        new File(liveFigures, figure), installed, fault);
            }
            install(ObjectProfileFigureWriter.figureManifest(stagedFigures),
                    ObjectProfileFigureWriter.figureManifest(liveFigures), installed, fault);
            install(stagedManifest, liveManifest, installed, fault);
            committed = true;
        } catch (RuntimeException e) {
            rollbackPromotion(installed, restore, e, fault);
            throw e;
        } catch (IOException e) {
            rollbackPromotion(installed, restore, e, fault);
            throw new IllegalStateException("Could not publish staged batch outputs.", e);
        } finally {
            if (committed || !hasRemainingBackup(restore)) {
                cleanupTree(backupRoot);
            } else {
                IJ.log("Object Intensity Profiling retained recovery backup: " + backupRoot);
            }
        }
    }

    private static boolean hasRemainingBackup(List<PathMove> restore) {
        for (PathMove path : restore) {
            if (path.source.exists()) return true;
        }
        return false;
    }

    private static String diskIdentity(String encoded) {
        return encoded.toLowerCase(java.util.Locale.ROOT);
    }

    private static void writeSampleOwnership(File samplesDirectory, String encoded)
            throws IOException {
        File sample = new File(samplesDirectory, encoded);
        java.nio.file.Files.write(new File(sample, SAMPLE_OWNER_FILE).toPath(),
                java.util.Arrays.asList(SAMPLE_OWNER_MAGIC, encoded),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean ownsSample(File sample, String encoded) throws IOException {
        if (sample == null || !sample.isDirectory()
                || java.nio.file.Files.isSymbolicLink(sample.toPath())) return false;
        File marker = new File(sample, SAMPLE_OWNER_FILE);
        if (!java.nio.file.Files.isRegularFile(marker.toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
        List<String> lines = java.nio.file.Files.readAllLines(
                marker.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        return lines.size() == 2 && SAMPLE_OWNER_MAGIC.equals(lines.get(0))
                && encoded.equals(lines.get(1));
    }

    private static void backupIfPresent(
            File live, File backup, List<PathMove> restore) throws IOException {
        if (!live.exists()) return;
        rejectLinkOrReparsePoint(live);
        move(live, backup);
        restore.add(new PathMove(backup, live));
    }

    /**
     * Refuses to publish a figure whose live target exists but is not listed in the live figure
     * manifest, mirroring the unmanifested-sample check and the single-image writer's guard.
     */
    private static void validateNoUnownedFigureTargets(
            File directory, List<String> current, List<String> previous) throws IOException {
        Set<String> owned = new LinkedHashSet<String>();
        for (String name : previous) owned.add(diskIdentity(name));
        for (String name : current) {
            File target = new File(directory, name);
            if (java.nio.file.Files.exists(
                    target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && !owned.contains(diskIdentity(name))) {
                throw new IOException("Refusing to overwrite an unmanifested figure: " + target);
            }
        }
    }

    /** Rejects a live promotion target that exists but is not a plain regular file. */
    private static void validateRegularIfPresent(File target, String role) throws IOException {
        if (!java.nio.file.Files.exists(
                target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (!java.nio.file.Files.isRegularFile(
                target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(role + " is not a regular file: " + target);
        }
    }

    private static void install(
            File staged, File live, List<File> installed, PromotionFault fault)
            throws IOException {
        if (!staged.exists()) {
            throw new IOException("Staged output is missing: " + staged);
        }
        rejectLinkOrReparsePoint(staged);
        // ATOMIC_MOVE replaces an existing regular file on both Windows and POSIX, so an
        // uncleared live target would be destroyed rather than reported.
        if (java.nio.file.Files.exists(
                live.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Live output was not cleared before installation: " + live);
        }
        move(staged, live);
        installed.add(live);
        if (fault != null) fault.afterInstall(live, installed.size());
    }

    private static void move(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create output directory: " + parent);
        }
        if (parent == null || !destination.getCanonicalFile().getParentFile()
                .equals(parent.getCanonicalFile())) {
            throw new IOException("Output path is not a direct child of its container: "
                    + destination);
        }
        try {
            java.nio.file.Files.move(source.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            java.nio.file.Files.move(source.toPath(), destination.toPath());
        }
    }

    private static void rollbackPromotion(
            List<File> installed, List<PathMove> restore, Throwable cause,
            PromotionFault fault) {
        IOException rollbackFailure = null;
        for (int i = installed.size() - 1; i >= 0; i--) {
            try {
                deletePath(installed.get(i));
            } catch (IOException e) {
                if (rollbackFailure == null) rollbackFailure = e;
                else rollbackFailure.addSuppressed(e);
            }
        }
        for (int i = restore.size() - 1; i >= 0; i--) {
            PathMove path = restore.get(i);
            try {
                if (path.source.exists()) {
                    if (fault != null) fault.beforeRestore(
                            path.source, path.destination);
                    move(path.source, path.destination);
                }
            } catch (IOException e) {
                if (rollbackFailure == null) rollbackFailure = e;
                else rollbackFailure.addSuppressed(e);
            }
        }
        if (rollbackFailure != null) cause.addSuppressed(rollbackFailure);
    }

    private static void deletePath(File path) throws IOException {
        if (path == null || !path.exists()) return;
        if (path.isDirectory() && !java.nio.file.Files.isSymbolicLink(path.toPath())) {
            deleteTree(path, null);
        } else {
            java.nio.file.Files.deleteIfExists(path.toPath());
        }
    }

    private static void cleanupTree(File root) {
        if (root == null || !root.exists()) return;
        try {
            deletePath(root);
        } catch (IOException ignored) {
            IJ.log("Object Intensity Profiling could not remove temporary directory: " + root);
        }
    }

    private static void validateContainer(File path, File root, String role)
            throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        java.nio.file.Path lexical = path.getAbsoluteFile().toPath().normalize();
        if (!lexical.startsWith(canonicalRoot.toPath())) {
            throw new IOException(role + " path escapes its root: " + path);
        }
        if (java.nio.file.Files.exists(path.toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            rejectLinkOrReparsePoint(path);
            if (!path.getCanonicalFile().toPath().startsWith(canonicalRoot.toPath())) {
                throw new IOException(role + " resolves outside its root: " + path);
            }
        }
    }

    private static void rejectLinkOrReparsePoint(File path) throws IOException {
        java.nio.file.attribute.BasicFileAttributes attributes =
                java.nio.file.Files.readAttributes(path.toPath(),
                        java.nio.file.attribute.BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()
                || java.nio.file.Files.isSymbolicLink(path.toPath())) {
            throw new IOException("Linked or redirected output paths are not allowed: " + path);
        }
    }

    private static void deleteTree(
            File root, final OipParameters.CancellationToken cancellation) throws IOException {
        java.nio.file.Files.walkFileTree(root.toPath(),
                new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    @Override
                    public java.nio.file.FileVisitResult visitFile(
                            java.nio.file.Path file,
                            java.nio.file.attribute.BasicFileAttributes attributes)
                            throws IOException {
                        checkCancelled(cancellation);
                        java.nio.file.Files.delete(file);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult postVisitDirectory(
                            java.nio.file.Path directory, IOException error) throws IOException {
                        checkCancelled(cancellation);
                        if (error != null) throw error;
                        java.nio.file.Files.delete(directory);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
    }

    private static final class Pairing {
        final String key;
        final File label;
        final Map<String, File> raw;

        Pairing(String key, File label, Map<String, File> raw) {
            this.key = key;
            this.label = label;
            this.raw = raw;
        }
    }

    private static final class RunRecord {
        final Pairing pairing;
        final List<ObjectTextureResult> textures;

        RunRecord(Pairing pairing, List<ObjectTextureResult> textures) {
            this.pairing = pairing;
            this.textures = textures;
        }
    }

    private static final class PathMove {
        final File source;
        final File destination;

        PathMove(File source, File destination) {
            this.source = source;
            this.destination = destination;
        }
    }

    interface PromotionFault {
        void afterInstall(File live, int installedCount) throws IOException;

        void beforeRestore(File backup, File live) throws IOException;
    }
}
