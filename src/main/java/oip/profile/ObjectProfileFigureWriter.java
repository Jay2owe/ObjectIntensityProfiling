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
import ij.gui.Plot;
import ij.io.FileSaver;
import oip.io.FileNameSupport;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders aggregate Object Intensity Profiling figures (one per source channel × group × profile
 * type) by overlaying each partner channel's mean curve. Headless-safe: builds {@link Plot} images
 * offscreen and saves PNGs via {@link FileSaver} without ever showing a window.
 *
 * <p>Aggregate only — no per-object figures (avoids the figure explosion for thousands of objects).
 */
public final class ObjectProfileFigureWriter {

    private static final String FIGURE_MANIFEST = ".OIP_figures_manifest.txt";

    private static final Color[] FALLBACK = {
            new Color(0x1f77b4), new Color(0xd62728), new Color(0x2ca02c), new Color(0x9467bd),
            new Color(0xff7f0e), new Color(0x17becf), new Color(0x8c564b), new Color(0xbcbd22)
    };

    private ObjectProfileFigureWriter() {}

    /**
     * @param dir           output directory (created if needed)
     * @param agg           populated aggregator
     * @param channelColors partner channel → display colour (may be null/partial; a palette fills gaps)
     */
    public static void writeFigures(File dir, ProfileAggregator agg,
                                    Map<String, Color> channelColors) throws IOException {
        writeFigures(dir, agg, channelColors, null);
    }

    public static void writeFigures(
            File dir, ProfileAggregator agg, Map<String, Color> channelColors,
            oip.OipParameters.CancellationToken cancellation) throws IOException {
        if (dir != null) dir.mkdirs();
        // Clear stale figures first — even when there is nothing to draw (null/empty aggregate, e.g.
        // a scalar-only run) — so PNGs from a previous run with different groups/profile types never
        // linger. The full set is always regenerated from the current aggregate below.
        clearFigures(dir, cancellation);
        List<String> written = new ArrayList<String>();
        List<RenderedFigure> figures = renderFigures(agg, channelColors, cancellation);
        int closed = 0;
        try {
            for (RenderedFigure rendered : figures) {
                try {
                    checkCancelled(cancellation);
                    File target = new File(dir, rendered.fileName);
                    if (java.nio.file.Files.exists(target.toPath(),
                            java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException(
                                "Refusing to overwrite an unmanifested figure: " + target);
                    }
                    java.nio.file.Path temporary = java.nio.file.Files.createTempFile(
                            dir.toPath(), ".OIP_figure_", ".png");
                    try {
                        if (!new FileSaver(rendered.image).saveAsPng(
                                temporary.toFile().getAbsolutePath())) {
                            throw new IOException("Could not save figure: " + target);
                        }
                        FileNameSupport.publishNoReplace(temporary, target.toPath());
                    } finally {
                        try {
                            java.nio.file.Files.deleteIfExists(temporary);
                        } catch (IOException cleanupFailure) {
                            ij.IJ.log("Object Intensity Profiling could not remove temporary "
                                    + "figure: " + temporary);
                        }
                    }
                    written.add(rendered.fileName);
                    writeManifest(dir, written);
                } finally {
                    close(rendered);
                    closed++;
                }
            }
        } finally {
            for (int i = closed; i < figures.size(); i++) close(figures.get(i));
        }
    }

    /** Build aggregate plots for display without writing temporary files. */
    public static List<ImagePlus> createFigures(
            ProfileAggregator agg, Map<String, Color> channelColors,
            oip.OipParameters.CancellationToken cancellation) {
        List<ImagePlus> images = new ArrayList<ImagePlus>();
        for (RenderedFigure rendered : renderFigures(agg, channelColors, cancellation)) {
            images.add(rendered.image);
        }
        return images;
    }

    private static List<RenderedFigure> renderFigures(
            ProfileAggregator agg, Map<String, Color> channelColors,
            oip.OipParameters.CancellationToken cancellation) {
        List<RenderedFigure> rendered = new ArrayList<RenderedFigure>();
        try {
            if (agg == null) return rendered;
            List<ProfileAggregator.AggregatedProfile> all = agg.results(cancellation);
            if (all.isEmpty()) return rendered;

        // Group partners under each (source, group, profileType).
        Map<PanelKey, List<ProfileAggregator.AggregatedProfile>> byPanel =
                new LinkedHashMap<PanelKey, List<ProfileAggregator.AggregatedProfile>>();
        for (ProfileAggregator.AggregatedProfile a : all) {
            checkCancelled(cancellation);
            PanelKey key = new PanelKey(a.source, a.groupKey, a.profileType);
            List<ProfileAggregator.AggregatedProfile> list = byPanel.get(key);
            if (list == null) { list = new ArrayList<ProfileAggregator.AggregatedProfile>(); byPanel.put(key, list); }
            list.add(a);
        }

        Map<String, Color> resolved = new LinkedHashMap<String, Color>();
        Map<String, PanelKey> outputNames = new LinkedHashMap<String, PanelKey>();
        for (Map.Entry<PanelKey, List<ProfileAggregator.AggregatedProfile>> e
                : byPanel.entrySet()) {
            checkCancelled(cancellation);
            List<ProfileAggregator.AggregatedProfile> partners = e.getValue();
            if (partners.isEmpty()) continue;
            ProfileAggregator.AggregatedProfile first = partners.get(0);
            String type = first.profileType;
            String xLabel = ProfileAggregator.RADIAL.equals(type)
                    || ProfileAggregator.SHELL_INTENSITY.equals(type)
                    || ProfileAggregator.SHELL_OVERLAP.equals(type)
                    ? "normalised radius (0=centre)"
                    : ProfileAggregator.ANGULAR.equals(type) ? "angle (deg)"
                    : "normalised position (centre=0)";
            String yLabel = ProfileAggregator.SHELL_OVERLAP.equals(type)
                    ? "mean overlap coefficient" : "mean intensity (normalised)";
            Plot plot = new Plot("OIP " + type, xLabel, yLabel);
            double[] lim = limits(partners);
            plot.setLimits(lim[0], lim[1], lim[2], lim[3]);

            for (ProfileAggregator.AggregatedProfile a : partners) {
                checkCancelled(cancellation);
                Color c = colorFor(a.partner, channelColors, resolved);
                plot.setColor(c);
                // Pass means as-is: ij.gui.Plot leaves gaps for NaN bins rather than drawing a
                // straight segment through missing data (limits are set manually above, so NaN does
                // not affect auto-ranging).
                plot.addPoints(a.x, a.mean, Plot.LINE);
                plot.addErrorBars(a.sem);
                plot.setColor(c);
                plot.addLabel(0.02, 0.05 * (partners.indexOf(a) + 1), a.partner);
            }
            plot.setColor(Color.BLACK);

            ImagePlus imp = plot.getImagePlus();
            try {
                imp.setTitle("OIP " + type + " - " + first.source + " - " + first.groupKey);
                String fname = "OIP_"
                        + FileNameSupport.encode(first.source) + "__"
                        + FileNameSupport.encode(first.groupKey) + "__"
                        + FileNameSupport.encode(type) + ".png";
                PanelKey previous = outputNames.put(
                        fname.toLowerCase(java.util.Locale.ROOT), e.getKey());
                if (previous != null && !previous.equals(e.getKey())) {
                    throw new IllegalArgumentException(
                            "Figure panel names collide on disk: "
                                    + previous + " and " + e.getKey());
                }
                rendered.add(new RenderedFigure(imp, fname));
            } catch (RuntimeException error) {
                close(imp);
                throw error;
            } catch (Error error) {
                close(imp);
                throw error;
            }
        }
            return rendered;
        } catch (RuntimeException e) {
            closeAll(rendered);
            throw e;
        } catch (Error e) {
            closeAll(rendered);
            throw e;
        }
    }

    private static void closeAll(List<RenderedFigure> figures) {
        for (RenderedFigure figure : figures) close(figure);
    }

    private static void close(RenderedFigure rendered) {
        if (rendered != null) close(rendered.image);
    }

    private static void close(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    private static final class RenderedFigure {
        final ImagePlus image;
        final String fileName;

        RenderedFigure(ImagePlus image, String fileName) {
            this.image = image;
            this.fileName = fileName;
        }
    }

    private static final class PanelKey {
        final String source;
        final String groupKey;
        final String profileType;

        PanelKey(String source, String groupKey, String profileType) {
            this.source = source;
            this.groupKey = groupKey;
            this.profileType = profileType;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof PanelKey)) return false;
            PanelKey key = (PanelKey) other;
            return java.util.Objects.equals(source, key.source)
                    && java.util.Objects.equals(groupKey, key.groupKey)
                    && java.util.Objects.equals(profileType, key.profileType);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(source, groupKey, profileType);
        }

        @Override
        public String toString() {
            return source + " / " + groupKey + " / " + profileType;
        }
    }

    private static Color colorFor(String partner, Map<String, Color> provided, Map<String, Color> resolved) {
        if (provided != null && provided.get(partner) != null) return provided.get(partner);
        Color c = resolved.get(partner);
        if (c == null) { c = FALLBACK[resolved.size() % FALLBACK.length]; resolved.put(partner, c); }
        return c;
    }

    /** {xMin,xMax,yMin,yMax}, including finite SEM bars and padding. */
    static double[] limits(List<ProfileAggregator.AggregatedProfile> partners) {
        double xmin = Double.POSITIVE_INFINITY, xmax = Double.NEGATIVE_INFINITY;
        double ymin = Double.POSITIVE_INFINITY, ymax = Double.NEGATIVE_INFINITY;
        for (ProfileAggregator.AggregatedProfile a : partners) {
            for (double v : a.x) {
                if (Double.isFinite(v)) {
                    xmin = Math.min(xmin, v);
                    xmax = Math.max(xmax, v);
                }
            }
            for (int i = 0; i < a.mean.length; i++) {
                double value = a.mean[i];
                if (!Double.isFinite(value)) continue;
                double error = i < a.sem.length && Double.isFinite(a.sem[i])
                        ? Math.abs(a.sem[i]) : 0.0;
                ymin = Math.min(ymin, value - error);
                ymax = Math.max(ymax, value + error);
            }
        }
        if (!Double.isFinite(xmin)) {
            xmin = 0;
            xmax = 1;
        } else if (!(xmax > xmin)) {
            double span = Math.max(1.0e-6, Math.max(1.0, Math.abs(xmin)) * 0.05);
            xmin -= span;
            xmax += span;
        }
        if (!Double.isFinite(ymin)) {
            ymin = 0;
            ymax = 1;
        } else if (!(ymax > ymin)) {
            double span = Math.max(1.0e-6, Math.max(1.0, Math.abs(ymin)) * 0.05);
            ymin -= span;
            ymax += span;
        }
        double pad = 0.05 * (ymax - ymin);
        return new double[] {xmin, xmax, ymin - pad, ymax + pad};
    }

    /** Exact filenames generated by the previous successful/partial write. */
    public static List<String> ownedFigureNames(File dir) throws IOException {
        List<String> names = new ArrayList<String>();
        File manifest = figureManifest(dir);
        if (!java.nio.file.Files.exists(manifest.toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) return names;
        rejectLinkedPath(manifest);
        if (!manifest.isFile()) {
            throw new IOException("Figure manifest is not a regular file: " + manifest);
        }
        if (java.nio.file.Files.isSymbolicLink(manifest.toPath())) {
            throw new IOException("Figure manifest must not be a symbolic link: " + manifest);
        }
        Map<String, Boolean> unique = new LinkedHashMap<String, Boolean>();
        for (String name : java.nio.file.Files.readAllLines(
                manifest.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            if (!isCanonicalFigureName(name)) {
                throw new IOException("Invalid figure manifest entry: " + name);
            }
            if (unique.put(name.toLowerCase(java.util.Locale.ROOT), true) != null) {
                throw new IOException("Duplicate figure manifest entry: " + name);
            }
            names.add(name);
        }
        return names;
    }

    public static File figureManifest(File dir) {
        return new File(dir, FIGURE_MANIFEST);
    }

    /** Delete only precisely manifested OIP figures so unrelated similarly named files survive. */
    public static void clearFigures(File dir) throws IOException {
        clearFigures(dir, null);
    }

    public static void clearFigures(
            File dir, oip.OipParameters.CancellationToken cancellation) throws IOException {
        checkCancelled(cancellation);
        if (dir == null || !dir.isDirectory()) return;
        rejectLinkedPath(dir);
        for (String name : ownedFigureNames(dir)) {
            checkCancelled(cancellation);
            File figure = new File(dir, name);
            if (figure.exists()) {
                if (java.nio.file.Files.isSymbolicLink(figure.toPath())
                        || !figure.isFile()) {
                    throw new IOException("Owned figure path is not a regular file: " + figure);
                }
                java.nio.file.Files.delete(figure.toPath());
            }
        }
        writeManifest(dir, new ArrayList<String>());
    }

    private static void writeManifest(File dir, List<String> names) throws IOException {
        if (dir == null) throw new IOException("Figure directory is required.");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create figure directory: " + dir);
        }
        rejectLinkedPath(dir);
        File manifest = figureManifest(dir);
        if (java.nio.file.Files.exists(manifest.toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            rejectLinkedPath(manifest);
        }
        java.nio.file.Path temporary = java.nio.file.Files.createTempFile(
                dir.toPath(), ".OIP_figures_manifest_", ".tmp");
        try {
            java.nio.file.Files.write(temporary, names,
                    java.nio.charset.StandardCharsets.UTF_8);
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

    private static boolean isCanonicalFigureName(String name) {
        if (name == null || !name.startsWith("OIP_")) return false;
        if (!name.endsWith(".png")) return false;
        String extension = ".png";
        String body = name.substring(4, name.length() - extension.length());
        String[] parts = body.split("__", -1);
        return parts.length == 3
                && FileNameSupport.isCanonicalEncoding(parts[0])
                && FileNameSupport.isCanonicalEncoding(parts[1])
                && FileNameSupport.isCanonicalEncoding(parts[2]);
    }

    private static void rejectLinkedPath(File path) throws IOException {
        java.nio.file.attribute.BasicFileAttributes attributes =
                java.nio.file.Files.readAttributes(path.toPath(),
                        java.nio.file.attribute.BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()
                || java.nio.file.Files.isSymbolicLink(path.toPath())) {
            throw new IOException("Linked figure paths are not allowed: " + path);
        }
    }

    private static void checkCancelled(oip.OipParameters.CancellationToken cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new oip.ObjectIntensityProfiling.AnalysisCancelledException();
        }
    }

}
