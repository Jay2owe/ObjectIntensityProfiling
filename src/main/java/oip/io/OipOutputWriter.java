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
package oip.io;

import ij.ImagePlus;
import ij.io.FileSaver;
import oip.OipResult;
import oip.ObjectIntensityProfiling;
import oip.OipParameters;
import oip.profile.ObjectProfileFigureWriter;
import oip.profile.ObjectProfileResult;
import oip.profile.ProfileAggregator;
import oip.texture.ObjectTextureFeatures;
import oip.texture.ObjectTextureResult;
import oip.texture.QuantizationRange;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writes the standalone plugin's complete, deterministic auto-save tree.
 */
public final class OipOutputWriter {

    private static final String MAP_MANIFEST = ".OIP_maps_manifest.txt";
    private static final String[] OWNED_CSV_FILES = {
        "Profiles/Per_Object_Profiles.csv",
        "Profiles/Object_Summaries.csv",
        "Texture/Object_Texture.csv",
        "Texture/Quantization_Ranges.csv",
        "Aggregate/Aggregate_Profiles.csv"
    };

    private OipOutputWriter() {
    }

    public static void save(OipResult result, File root) throws IOException {
        save(result, root, null);
    }

    public static void save(OipResult result, File root,
                            OipParameters.CancellationToken cancellation) throws IOException {
        if (result == null) throw new IllegalArgumentException("Result must not be null.");
        if (root == null) throw new IllegalArgumentException("Output directory must not be null.");

        File profiles = directory(root, "Profiles");
        File texture = directory(root, "Texture");
        File aggregate = directory(root, "Aggregate");
        File figures = directory(root, "Figures");
        File maps = directory(root, "Maps");

        writeCurves(new File(profiles, "Per_Object_Profiles.csv"), result, cancellation);
        writeSummaries(new File(profiles, "Object_Summaries.csv"), result, cancellation);
        writeTextures(new File(texture, "Object_Texture.csv"),
                result.getTextures(), cancellation);
        writeRanges(new File(texture, "Quantization_Ranges.csv"),
                result.getQuantizationRanges(), cancellation);
        writeAggregate(new File(aggregate, "Aggregate_Profiles.csv"), result, cancellation);

        if (result.getParameters().isSaveFigures()) {
            ProfileAggregator figureAggregate = new ProfileAggregator();
            for (ObjectProfileResult profile : result.getProfiles()) {
                checkCancelled(cancellation);
                figureAggregate.addAll(profile, result.getParameters().getGroupKey(), cancellation);
            }
            ObjectProfileFigureWriter.writeFigures(figures, figureAggregate, null, cancellation);
        } else {
            ObjectProfileFigureWriter.clearFigures(figures, cancellation);
        }
        writeClassMaps(maps, result.getClassMaps(),
                result.getParameters().isSaveClassMaps(), cancellation);
    }

    /**
     * Stage a complete standalone result tree, then replace only plugin-owned
     * live files under an exclusive output lock. Any publication failure
     * restores the previous complete result.
     */
    public static void saveTransactional(
            OipResult result, File root,
            OipParameters.CancellationToken cancellation) throws IOException {
        if (result == null) throw new IllegalArgumentException("Result must not be null.");
        File output = prepareOutputRoot(root);
        File staging = new File(output, ".OIP_staging_" + UUID.randomUUID().toString());
        java.nio.file.Files.createDirectory(staging.toPath());
        try {
            save(result, staging, cancellation);
            checkCancelled(cancellation);
            promoteStagedOutput(output, staging, cancellation, null);
        } finally {
            cleanupTree(staging);
        }
    }

    /**
     * Rewrite only texture tables and globally assigned class maps. Batch mode
     * uses this after per-sample profiles have already been streamed to disk.
     */
    public static void saveTextureOutputs(
            List<ObjectTextureResult> textures,
            Map<String, QuantizationRange> ranges,
            Map<String, ImagePlus> classMaps,
            boolean saveClassMaps,
            File root,
            OipParameters.CancellationToken cancellation) throws IOException {
        if (root == null) throw new IllegalArgumentException("Output directory must not be null.");
        File texture = directory(root, "Texture");
        File maps = directory(root, "Maps");
        writeTextures(new File(texture, "Object_Texture.csv"), textures, cancellation);
        writeRanges(new File(texture, "Quantization_Ranges.csv"), ranges, cancellation);
        writeClassMaps(maps, classMaps, saveClassMaps, cancellation);
    }

    /** Write object-weighted aggregate curves and figures for a combined batch. */
    public static void saveAggregate(ProfileAggregator aggregate, File root,
                                     boolean saveFigures) throws IOException {
        saveAggregate(aggregate, root, saveFigures, null);
    }

    public static void saveAggregate(ProfileAggregator aggregate, File root,
                                     boolean saveFigures,
                                     OipParameters.CancellationToken cancellation)
            throws IOException {
        if (aggregate == null) throw new IllegalArgumentException("Aggregate must not be null.");
        File aggregateDirectory = directory(root, "Aggregate");
        writeAggregate(new File(aggregateDirectory, "Aggregate_Profiles.csv"),
                aggregate, cancellation);
        File figureDirectory = directory(root, "Figures");
        if (saveFigures) {
            ObjectProfileFigureWriter.writeFigures(
                    figureDirectory, aggregate, null, cancellation);
        } else {
            ObjectProfileFigureWriter.clearFigures(figureDirectory, cancellation);
        }
    }

    private static void writeCurves(File file, OipResult result,
                                    OipParameters.CancellationToken cancellation)
            throws IOException {
        PendingCsv pending = writer(file);
        PrintWriter writer = pending.writer;
        try {
            writer.println("Source,Label,VoxelCount,Partner,ProfileType,Bin,AxisNorm,ValueRaw,ValueNorm");
            for (ObjectProfileResult object : result.getProfiles()) {
                checkCancelled(cancellation);
                for (ObjectProfileResult.PartnerProfiles partner : object.byPartner.values()) {
                    checkCancelled(cancellation);
                    curve(writer, object, partner, ProfileAggregator.RADIAL,
                            partner.radialRaw, partner.radialNorm, cancellation);
                    curve(writer, object, partner, ProfileAggregator.MARGINAL_X,
                            partner.marginalXRaw, partner.marginalXNorm, cancellation);
                    curve(writer, object, partner, ProfileAggregator.MARGINAL_Y,
                            partner.marginalYRaw, partner.marginalYNorm, cancellation);
                    curve(writer, object, partner, ProfileAggregator.MARGINAL_Z,
                            partner.marginalZRaw, partner.marginalZNorm, cancellation);
                    curve(writer, object, partner, ProfileAggregator.PC_MAJOR,
                            partner.pcMajorRaw, partner.pcMajorNorm, cancellation);
                    curve(writer, object, partner, ProfileAggregator.PC_MINOR,
                            partner.pcMinorRaw, partner.pcMinorNorm, cancellation);
                    curve(writer, object, partner, ProfileAggregator.PC_THIRD,
                            partner.pcThirdRaw, partner.pcThirdNorm, cancellation);
                    curve(writer, object, partner, ProfileAggregator.ANGULAR,
                            partner.angularRaw, partner.angularNorm, cancellation);
                    curve(writer, object, partner, ProfileAggregator.SHELL_INTENSITY,
                            partner.shellRaw, partner.shellNorm, cancellation);
                    curve(writer, object, partner, ProfileAggregator.SHELL_OVERLAP,
                            partner.shellOverlap, partner.shellOverlap, cancellation);
                }
            }
        } finally {
            close(pending);
        }
    }

    private static void curve(PrintWriter writer, ObjectProfileResult object,
                              ObjectProfileResult.PartnerProfiles partner, String type,
                              double[] raw, double[] normalized,
                              OipParameters.CancellationToken cancellation) {
        if (raw == null) return;
        for (int i = 0; i < raw.length; i++) {
            if ((i & 255) == 0) checkCancelled(cancellation);
            writer.println(CsvSupport.field(object.sourceChannel) + ","
                    + object.label + "," + object.voxelCount + ","
                    + CsvSupport.field(partner.partnerChannel) + ","
                    + CsvSupport.field(type) + "," + i + ","
                    + CsvSupport.number(ProfileAggregator.axisAt(type, i, raw.length)) + ","
                    + CsvSupport.number(raw[i]) + ","
                    + CsvSupport.number(normalized == null ? Double.NaN : normalized[i]));
        }
    }

    private static void writeSummaries(File file, OipResult result,
                                       OipParameters.CancellationToken cancellation)
            throws IOException {
        PendingCsv pending = writer(file);
        PrintWriter writer = pending.writer;
        try {
            writer.println("Source,Label,VoxelCount,Partner,Elongation,Flatness,"
                    + "RadialCoreEdge,RadialPeakR,RadialPolarity,RingCompleteness,"
                    + "AngularUniformity,ShellInner,ShellMid,ShellOuter,"
                    + "ShellOverlapInner,ShellOverlapMid,ShellOverlapOuter,PrincipalMajorPolarity,"
                    + "Pearson,OverlapCoefficient,MandersM1,MandersM2");
            for (ObjectProfileResult object : result.getProfiles()) {
                checkCancelled(cancellation);
                for (ObjectProfileResult.PartnerProfiles partner : object.byPartner.values()) {
                    checkCancelled(cancellation);
                    writer.println(CsvSupport.field(object.sourceChannel) + ","
                            + object.label + "," + object.voxelCount + ","
                            + CsvSupport.field(partner.partnerChannel) + ","
                            + CsvSupport.number(object.elongation) + ","
                            + CsvSupport.number(object.flatness) + ","
                            + CsvSupport.number(partner.radialCoreEdge) + ","
                            + CsvSupport.number(partner.radialPeakR) + ","
                            + CsvSupport.number(partner.radialPolarity) + ","
                            + CsvSupport.number(partner.ringCompleteness) + ","
                            + CsvSupport.number(partner.angularUniformity) + ","
                            + CsvSupport.number(partner.shellInner) + ","
                            + CsvSupport.number(partner.shellMid) + ","
                            + CsvSupport.number(partner.shellOuter) + ","
                            + CsvSupport.number(partner.shellOverlapInner) + ","
                            + CsvSupport.number(partner.shellOverlapMid) + ","
                            + CsvSupport.number(partner.shellOverlapOuter) + ","
                            + CsvSupport.number(partner.pcMajorPolarity) + ","
                            + CsvSupport.number(partner.withinBoxPearson) + ","
                            + CsvSupport.number(partner.withinBoxOverlap) + ","
                            + CsvSupport.number(partner.mandersM1) + ","
                            + CsvSupport.number(partner.mandersM2));
                }
            }
        } finally {
            close(pending);
        }
    }

    private static void writeTextures(
                                      File file, List<ObjectTextureResult> textures,
                                      OipParameters.CancellationToken cancellation)
            throws IOException {
        PendingCsv pending = writer(file);
        PrintWriter writer = pending.writer;
        try {
            writer.println("Label,VoxelCount,Partner,Suppressed,GLCMReliable,FeatureValid,"
                    + "FeatureReliable,CoOccurrencePairs,"
                    + "Contrast,ASM,Correlation,Entropy,Homogeneity,"
                    + "Gabor0,Gabor45,Gabor90,Gabor135,Wavelet1,Wavelet2,Wavelet3,Wavelet4,"
                    + "TextureClass,ClassDistance");
            for (ObjectTextureResult texture : textures) {
                checkCancelled(cancellation);
                writer.print(texture.label + "," + texture.voxelCount + ","
                        + CsvSupport.field(texture.partnerChannel) + ","
                        + texture.suppressed + "," + texture.glcmReliable + ","
                        + (texture.featureVector != null
                                && texture.featureVector.valid) + ","
                        + (texture.featureVector != null
                                && texture.featureVector.reliable) + ","
                        + texture.coOccurrencePairs + ","
                        + CsvSupport.number(texture.contrast) + ","
                        + CsvSupport.number(texture.asm) + ","
                        + CsvSupport.number(texture.correlation) + ","
                        + CsvSupport.number(texture.entropy) + ","
                        + CsvSupport.number(texture.homogeneity));
                float[] features = texture.featureVector == null
                        ? null : texture.featureVector.features;
                for (int i = 0; i < ObjectTextureFeatures.DEFAULT_FEATURE_DIM; i++) {
                    writer.print(",");
                    if (texture.featureVector != null && texture.featureVector.valid
                            && features != null && i < features.length) {
                        writer.print(features[i]);
                    }
                }
                writer.println("," + (texture.classLabel < 0 ? "" : texture.classLabel + 1)
                        + "," + CsvSupport.number(texture.classDistance));
            }
        } finally {
            close(pending);
        }
    }

    private static void writeRanges(
                                    File file, Map<String, QuantizationRange> ranges,
                                    OipParameters.CancellationToken cancellation)
            throws IOException {
        PendingCsv pending = writer(file);
        PrintWriter writer = pending.writer;
        try {
            writer.println("Partner,Minimum,Maximum");
            for (Map.Entry<String, QuantizationRange> entry
                    : ranges.entrySet()) {
                checkCancelled(cancellation);
                writer.println(CsvSupport.field(entry.getKey()) + ","
                        + CsvSupport.number(entry.getValue().min) + ","
                        + CsvSupport.number(entry.getValue().max));
            }
        } finally {
            close(pending);
        }
    }

    private static void writeAggregate(File file, OipResult result,
                                       OipParameters.CancellationToken cancellation)
            throws IOException {
        writeAggregate(file, aggregated(result, cancellation), cancellation);
    }

    private static void writeAggregate(File file, ProfileAggregator profiles,
                                       OipParameters.CancellationToken cancellation)
            throws IOException {
        PendingCsv pending = writer(file);
        PrintWriter writer = pending.writer;
        try {
            writer.println("Source,Partner,ProfileType,Group,Bin,AxisNorm,Mean,SEM,N");
            for (ProfileAggregator.AggregatedProfile aggregate : profiles.results(cancellation)) {
                checkCancelled(cancellation);
                for (int i = 0; i < aggregate.mean.length; i++) {
                    if ((i & 255) == 0) checkCancelled(cancellation);
                    writer.println(CsvSupport.field(aggregate.source) + ","
                            + CsvSupport.field(aggregate.partner) + ","
                            + CsvSupport.field(aggregate.profileType) + ","
                            + CsvSupport.field(aggregate.groupKey) + ","
                            + i + "," + CsvSupport.number(aggregate.x[i]) + ","
                            + CsvSupport.number(aggregate.mean[i]) + ","
                            + CsvSupport.number(aggregate.sem[i]) + ","
                            + aggregate.n[i]);
                }
            }
        } finally {
            close(pending);
        }
    }

    private static ProfileAggregator aggregated(
            OipResult result, OipParameters.CancellationToken cancellation) {
        ProfileAggregator aggregate = new ProfileAggregator();
        for (ObjectProfileResult profile : result.getProfiles()) {
            checkCancelled(cancellation);
            aggregate.addAll(profile, result.getParameters().getGroupKey(), cancellation);
        }
        return aggregate;
    }

    private static PendingCsv writer(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("Output directory does not exist: " + parent);
        }
        rejectLinkedPath(parent);
        java.nio.file.Path temporary = java.nio.file.Files.createTempFile(
                parent.toPath(), ".OIP_csv_", ".tmp");
        PrintWriter writer = new PrintWriter(java.nio.file.Files.newBufferedWriter(
                temporary, StandardCharsets.UTF_8));
        return new PendingCsv(file, temporary, writer);
    }

    private static File directory(File parent, String name) throws IOException {
        File directory = new File(parent, name);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create output directory: " + directory);
        }
        rejectLinkedPath(directory);
        if (!directory.getCanonicalFile().getParentFile().equals(parent.getCanonicalFile())) {
            throw new IOException("Output directory is redirected outside its parent: "
                    + directory);
        }
        return directory;
    }

    static void clearTiffMaps(
            File directory, OipParameters.CancellationToken cancellation) throws IOException {
        checkCancelled(cancellation);
        rejectLinkedPath(directory);
        for (String name : ownedMapNames(directory)) {
            checkCancelled(cancellation);
            File file = new File(directory, name);
            if (file.exists()) {
                rejectLinkedPath(file);
                if (!file.isFile()) {
                    throw new IOException("Owned map path is not a regular file: " + file);
                }
                java.nio.file.Files.delete(file.toPath());
            }
        }
        writeMapManifest(directory, new ArrayList<String>());
    }

    private static void writeClassMaps(
            File directory, Map<String, ImagePlus> classMaps, boolean saveClassMaps,
            OipParameters.CancellationToken cancellation) throws IOException {
        clearTiffMaps(directory, cancellation);
        if (!saveClassMaps) return;
        FileNameSupport.requireUniqueEncoded(classMaps.keySet(), "Raw channel");
        List<String> written = new ArrayList<String>();
        for (Map.Entry<String, ImagePlus> entry : classMaps.entrySet()) {
            checkCancelled(cancellation);
            File target = new File(directory,
                    "OIP_" + FileNameSupport.encode(entry.getKey())
                            + "_Texture_Classes.tif");
            if (java.nio.file.Files.exists(target.toPath(),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Refusing to overwrite an unmanifested class map: " + target);
            }
            java.nio.file.Path temporary = java.nio.file.Files.createTempFile(
                    directory.toPath(), ".OIP_class_map_", ".tif");
            try {
                FileSaver saver = new FileSaver(entry.getValue());
                boolean saved = entry.getValue().getStackSize() > 1
                        ? saver.saveAsTiffStack(temporary.toFile().getAbsolutePath())
                        : saver.saveAsTiff(temporary.toFile().getAbsolutePath());
                if (!saved) throw new IOException("Could not save class map: " + target);
                FileNameSupport.publishNoReplace(temporary, target.toPath());
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    ij.IJ.log("Object Intensity Profiling could not remove temporary "
                            + "class map: " + temporary);
                }
            }
            written.add(target.getName());
            writeMapManifest(directory, written);
        }
    }

    private static List<String> ownedMapNames(File directory) throws IOException {
        List<String> names = new ArrayList<String>();
        File manifest = new File(directory, MAP_MANIFEST);
        if (!java.nio.file.Files.exists(manifest.toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) return names;
        rejectLinkedPath(manifest);
        if (!manifest.isFile()) {
            throw new IOException("Map manifest is not a regular file: " + manifest);
        }
        Map<String, Boolean> unique = new LinkedHashMap<String, Boolean>();
        for (String name : java.nio.file.Files.readAllLines(
                manifest.toPath(), StandardCharsets.UTF_8)) {
            if (!isCanonicalMapName(name)) {
                throw new IOException("Invalid map manifest entry: " + name);
            }
            if (unique.put(name.toLowerCase(java.util.Locale.ROOT), true) != null) {
                throw new IOException("Duplicate map manifest entry: " + name);
            }
            names.add(name);
        }
        return names;
    }

    private static boolean isCanonicalMapName(String name) {
        String prefix = "OIP_";
        String suffix = "_Texture_Classes.tif";
        return name != null && name.startsWith(prefix) && name.endsWith(suffix)
                && FileNameSupport.isCanonicalEncoding(
                        name.substring(prefix.length(), name.length() - suffix.length()));
    }

    private static void writeMapManifest(File directory, List<String> names)
            throws IOException {
        File manifest = new File(directory, MAP_MANIFEST);
        if (java.nio.file.Files.exists(manifest.toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            rejectLinkedPath(manifest);
        }
        java.nio.file.Path temporary = java.nio.file.Files.createTempFile(
                directory.toPath(), ".OIP_maps_manifest_", ".tmp");
        try {
            java.nio.file.Files.write(temporary, names, StandardCharsets.UTF_8);
            java.nio.file.Files.move(temporary, manifest.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            java.nio.file.Files.move(temporary, manifest.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            java.nio.file.Files.deleteIfExists(temporary);
        }
    }

    static void promoteStagedOutput(
            File output, File staging,
            OipParameters.CancellationToken cancellation,
            PromotionFault fault) throws IOException {
        try (OutputDirectoryLock ignored = OutputDirectoryLock.acquire(output)) {
            promoteStagedOutputLocked(output, staging, cancellation, fault);
        }
    }

    private static void promoteStagedOutputLocked(
            File output, File staging,
            OipParameters.CancellationToken cancellation,
            PromotionFault fault) throws IOException {
        File backup = new File(output, ".OIP_backup_" + UUID.randomUUID().toString());
        List<PathMove> restore = new ArrayList<PathMove>();
        List<File> installed = new ArrayList<File>();
        boolean committed = false;
        try {
            validateContainer(staging, output, "standalone staging");
            Map<String, File> stagedDirectories = outputDirectories(staging);
            Map<String, File> liveDirectories = outputDirectories(output);
            for (Map.Entry<String, File> entry : stagedDirectories.entrySet()) {
                validateContainer(entry.getValue(), staging,
                        "staged " + entry.getKey().toLowerCase(java.util.Locale.ROOT));
            }
            for (Map.Entry<String, File> entry : liveDirectories.entrySet()) {
                File live = directory(output, entry.getKey());
                entry.setValue(live);
            }

            File stagedFigures = stagedDirectories.get("Figures");
            File liveFigures = liveDirectories.get("Figures");
            File stagedMaps = stagedDirectories.get("Maps");
            File liveMaps = liveDirectories.get("Maps");
            List<String> previousFigures =
                    ObjectProfileFigureWriter.ownedFigureNames(liveFigures);
            List<String> currentFigures =
                    ObjectProfileFigureWriter.ownedFigureNames(stagedFigures);
            List<String> previousMaps = ownedMapNames(liveMaps);
            List<String> currentMaps = ownedMapNames(stagedMaps);

            validateNoUnownedTargets(
                    liveFigures, currentFigures, previousFigures, "figure");
            validateNoUnownedTargets(liveMaps, currentMaps, previousMaps, "class map");
            for (String relative : OWNED_CSV_FILES) {
                validateRegularIfPresent(resolve(output, relative), "result table");
                validateRegular(resolve(staging, relative), "staged result table");
            }
            validateOwnedFiles(liveFigures, previousFigures, "owned figure");
            validateOwnedFiles(stagedFigures, currentFigures, "staged figure");
            validateOwnedFiles(liveMaps, previousMaps, "owned class map");
            validateOwnedFiles(stagedMaps, currentMaps, "staged class map");
            File liveFigureManifest = ObjectProfileFigureWriter.figureManifest(liveFigures);
            File stagedFigureManifest =
                    ObjectProfileFigureWriter.figureManifest(stagedFigures);
            File liveMapManifest = new File(liveMaps, MAP_MANIFEST);
            File stagedMapManifest = new File(stagedMaps, MAP_MANIFEST);
            validateRegularIfPresent(liveFigureManifest, "figure manifest");
            validateRegular(stagedFigureManifest, "staged figure manifest");
            validateRegularIfPresent(liveMapManifest, "map manifest");
            validateRegular(stagedMapManifest, "staged map manifest");
            checkCancelled(cancellation);

            java.nio.file.Files.createDirectory(backup.toPath());
            for (String relative : OWNED_CSV_FILES) {
                backupIfPresent(resolve(output, relative),
                        resolve(backup, relative), restore);
            }
            for (String name : previousFigures) {
                backupIfPresent(new File(liveFigures, name),
                        new File(new File(backup, "Figures"), name), restore);
            }
            backupIfPresent(liveFigureManifest,
                    ObjectProfileFigureWriter.figureManifest(
                            new File(backup, "Figures")), restore);
            for (String name : previousMaps) {
                backupIfPresent(new File(liveMaps, name),
                        new File(new File(backup, "Maps"), name), restore);
            }
            backupIfPresent(liveMapManifest,
                    new File(new File(backup, "Maps"), MAP_MANIFEST), restore);
            checkCancelled(cancellation);

            for (String relative : OWNED_CSV_FILES) {
                install(resolve(staging, relative), resolve(output, relative),
                        installed, cancellation, fault);
            }
            for (String name : currentFigures) {
                install(new File(stagedFigures, name), new File(liveFigures, name),
                        installed, cancellation, fault);
            }
            install(stagedFigureManifest, liveFigureManifest,
                    installed, cancellation, fault);
            for (String name : currentMaps) {
                install(new File(stagedMaps, name), new File(liveMaps, name),
                        installed, cancellation, fault);
            }
            install(stagedMapManifest, liveMapManifest,
                    installed, cancellation, fault);
            committed = true;
        } catch (RuntimeException e) {
            rollbackPromotion(installed, restore, e, fault);
            throw e;
        } catch (IOException e) {
            rollbackPromotion(installed, restore, e, fault);
            throw e;
        } finally {
            if (committed || !hasRemainingBackup(restore)) {
                cleanupTree(backup);
            } else {
                ij.IJ.log("Object Intensity Profiling retained recovery backup: " + backup);
            }
        }
    }

    private static File prepareOutputRoot(File root) throws IOException {
        if (root == null) throw new IllegalArgumentException("Output directory must not be null.");
        File absolute = root.getAbsoluteFile();
        java.nio.file.Path path = absolute.toPath().normalize();
        if (java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            rejectLinkedPath(path.toFile());
            if (!java.nio.file.Files.isDirectory(
                    path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Output path is not a directory: " + root);
            }
        } else {
            java.nio.file.Files.createDirectories(path);
        }
        rejectLinkedPath(path.toFile());
        return path.toFile().getCanonicalFile();
    }

    private static Map<String, File> outputDirectories(File root) {
        Map<String, File> directories = new LinkedHashMap<String, File>();
        directories.put("Profiles", new File(root, "Profiles"));
        directories.put("Texture", new File(root, "Texture"));
        directories.put("Aggregate", new File(root, "Aggregate"));
        directories.put("Figures", new File(root, "Figures"));
        directories.put("Maps", new File(root, "Maps"));
        return directories;
    }

    private static File resolve(File root, String relative) {
        return new File(root, relative.replace('/', File.separatorChar));
    }

    private static void validateContainer(File path, File root, String role)
            throws IOException {
        java.nio.file.Path rootPath = root.getCanonicalFile().toPath();
        java.nio.file.Path lexical = path.getAbsoluteFile().toPath().normalize();
        if (!lexical.startsWith(rootPath)) {
            throw new IOException(role + " path escapes its root: " + path);
        }
        if (!java.nio.file.Files.isDirectory(
                path.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(role + " is not a directory: " + path);
        }
        rejectLinkedPath(path);
        if (!path.getCanonicalFile().toPath().startsWith(rootPath)) {
            throw new IOException(role + " resolves outside its root: " + path);
        }
    }

    private static void validateNoUnownedTargets(
            File directory, List<String> current, List<String> previous, String role)
            throws IOException {
        Set<String> owned = new LinkedHashSet<String>();
        for (String name : previous) owned.add(diskIdentity(name));
        for (String name : current) {
            File target = new File(directory, name);
            if (java.nio.file.Files.exists(
                    target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && !owned.contains(diskIdentity(name))) {
                throw new IOException("Refusing to overwrite an unmanifested "
                        + role + ": " + target);
            }
        }
    }

    private static String diskIdentity(String name) {
        return name.toLowerCase(java.util.Locale.ROOT);
    }

    private static void validateOwnedFiles(
            File directory, List<String> names, String role) throws IOException {
        for (String name : names) {
            validateRegularIfPresent(new File(directory, name), role);
        }
    }

    private static void validateRegularIfPresent(File file, String role)
            throws IOException {
        if (!java.nio.file.Files.exists(
                file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        validateRegular(file, role);
    }

    private static void validateRegular(File file, String role) throws IOException {
        rejectLinkedPath(file);
        if (!java.nio.file.Files.isRegularFile(
                file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(role + " is not a regular file: " + file);
        }
    }

    private static void backupIfPresent(
            File live, File backup, List<PathMove> restore) throws IOException {
        if (!java.nio.file.Files.exists(
                live.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        validateRegular(live, "live output");
        move(live, backup);
        restore.add(new PathMove(backup, live));
    }

    private static void install(
            File staged, File live, List<File> installed,
            OipParameters.CancellationToken cancellation, PromotionFault fault)
            throws IOException {
        checkCancelled(cancellation);
        validateRegular(staged, "staged output");
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
        if (parent == null) {
            throw new IOException("Output destination has no parent: " + destination);
        }
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create output directory: " + parent);
        }
        rejectLinkedPath(parent);
        if (!destination.getCanonicalFile().getParentFile()
                .equals(parent.getCanonicalFile())) {
            throw new IOException("Output path is redirected outside its parent: "
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
                java.nio.file.Files.deleteIfExists(installed.get(i).toPath());
            } catch (IOException e) {
                if (rollbackFailure == null) rollbackFailure = e;
                else rollbackFailure.addSuppressed(e);
            }
        }
        for (int i = restore.size() - 1; i >= 0; i--) {
            PathMove path = restore.get(i);
            try {
                if (java.nio.file.Files.exists(
                        path.source.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    if (fault != null) {
                        fault.beforeRestore(path.source, path.destination);
                    }
                    move(path.source, path.destination);
                }
            } catch (IOException e) {
                if (rollbackFailure == null) rollbackFailure = e;
                else rollbackFailure.addSuppressed(e);
            }
        }
        if (rollbackFailure != null) cause.addSuppressed(rollbackFailure);
    }

    private static boolean hasRemainingBackup(List<PathMove> restore) {
        for (PathMove path : restore) {
            if (java.nio.file.Files.exists(
                    path.source.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
        }
        return false;
    }

    private static void cleanupTree(File root) {
        if (root == null || !java.nio.file.Files.exists(
                root.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try {
            java.nio.file.Files.walkFileTree(root.toPath(),
                    new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                        @Override
                        public java.nio.file.FileVisitResult visitFile(
                                java.nio.file.Path file,
                                java.nio.file.attribute.BasicFileAttributes attributes)
                                throws IOException {
                            java.nio.file.Files.delete(file);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult postVisitDirectory(
                                java.nio.file.Path directory, IOException error)
                                throws IOException {
                            if (error != null) throw error;
                            java.nio.file.Files.delete(directory);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException cleanupFailure) {
            ij.IJ.log("Object Intensity Profiling could not remove temporary directory: "
                    + root);
        }
    }

    private static void rejectLinkedPath(File path) throws IOException {
        java.nio.file.attribute.BasicFileAttributes attributes =
                java.nio.file.Files.readAttributes(path.toPath(),
                        java.nio.file.attribute.BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()
                || java.nio.file.Files.isSymbolicLink(path.toPath())) {
            throw new IOException("Linked output paths are not allowed: " + path);
        }
    }

    private static void close(PendingCsv pending) throws IOException {
        IOException failure = null;
        pending.writer.close();
        if (pending.writer.checkError()) {
            failure = new IOException("Could not write output file: " + pending.target);
        }
        try {
            if (failure == null) {
                java.nio.file.Path target = pending.target.toPath();
                if (java.nio.file.Files.exists(
                        target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    rejectLinkedPath(pending.target);
                    if (!java.nio.file.Files.isRegularFile(
                            target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException(
                                "Output target is not a regular file: " + pending.target);
                    }
                }
                try {
                    java.nio.file.Files.move(pending.temporary, target,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    java.nio.file.Files.move(pending.temporary, target,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException publicationFailure) {
            if (failure == null) failure = publicationFailure;
            else failure.addSuppressed(publicationFailure);
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(pending.temporary);
            } catch (IOException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure != null) throw failure;
    }

    private static void checkCancelled(OipParameters.CancellationToken cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ObjectIntensityProfiling.AnalysisCancelledException();
        }
    }

    private static final class PendingCsv {
        final File target;
        final java.nio.file.Path temporary;
        final PrintWriter writer;

        PendingCsv(File target, java.nio.file.Path temporary, PrintWriter writer) {
            this.target = target;
            this.temporary = temporary;
            this.writer = writer;
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
