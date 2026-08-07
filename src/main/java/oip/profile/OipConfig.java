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

/**
 * Compute-time configuration for {@link ObjectIntensityProfiler}. A plain mutable value object so
 * the dialog, macro, batch, and Java layers can populate it without depending on UI types.
 *
 * <p>"Source" = the object-defining label image; "partner" = any raw channel sampled inside its
 * objects
 * (partner == source is valid and describes the object's own internal distribution).
 */
public final class OipConfig {

    /** Voxels profiled within each object's bounding box. */
    public enum Region {
        /** All voxels in the (optionally padded) box, including background/neighbours — reveals
         *  signal <em>around</em> an object (e.g. a marker ring around a nuclear core). */
        WHOLE_BOX,
        /** Only voxels whose label equals the source object's label — pure intra-object
         *  distribution (the macro's "Only Object"). */
        OBJECT_VOXELS
    }

    /** Per-object, per-partner intensity normalization applied before cross-object averaging. */
    public enum IntensityNorm {
        /** (v - min) / (max - min) over the partner's box intensities → [0,1]. */
        PER_OBJECT_MINMAX,
        /** v / mean. */
        DIVIDE_BY_MEAN,
        /** (v - mean) / sd. */
        ZSCORE
    }

    // Profile families (the six toggles; nothing runs if all are false).
    public boolean doRadial = true;
    public boolean doMarginal = true;
    public boolean doPrincipalAxis = true;
    public boolean doAngular = true;
    public boolean doShell = true;
    public boolean doWithinBox = true;

    public Region region = Region.OBJECT_VOXELS;
    public IntensityNorm intensityNorm = IntensityNorm.PER_OBJECT_MINMAX;

    /** Number of bins for the radial profile (distance 0..1). */
    public int radialBins = 20;
    /** Number of angular bins around the centroid (0..2π) for ring-completeness. */
    public int angularBins = 12;
    /** Number of concentric shells (inner..outer). */
    public int shells = 3;
    /** Fixed length of the marginal/principal-axis curves over the normalized [-1,1] axis. */
    public int resampleN = 50;
    /** Box expansion per side, percent of each axis extent, before profiling. */
    public double boxPadPct = 0.0;
    /** Fraction (percent) of the per-partner maximum used as the ring-completeness cutoff. */
    public double ringThresholdPct = 50.0;
    /** Reference-channel threshold used for Manders M2. */
    public double referenceThreshold = 0.0;
    /** Partner-channel threshold used for Manders M1. */
    public double partnerThreshold = 0.0;

    /** Slow texture families are opt-in. */
    public boolean doGlcm = false;
    public boolean doTextureClasses = false;
    public int glcmLevels = 32;
    public int glcmDistance = 1;
    public int textureClasses = 4;
    public int minimumTextureVoxels = 64;

    public boolean anyProfileEnabled() {
        return doRadial || doMarginal || doPrincipalAxis || doAngular || doShell || doWithinBox;
    }

    /** Defensive copy (the engine never mutates config, but callers may reuse and tweak one). */
    public OipConfig copy() {
        OipConfig c = new OipConfig();
        c.doRadial = doRadial;
        c.doMarginal = doMarginal;
        c.doPrincipalAxis = doPrincipalAxis;
        c.doAngular = doAngular;
        c.doShell = doShell;
        c.doWithinBox = doWithinBox;
        c.region = region;
        c.intensityNorm = intensityNorm;
        c.radialBins = radialBins;
        c.angularBins = angularBins;
        c.shells = shells;
        c.resampleN = resampleN;
        c.boxPadPct = boxPadPct;
        c.ringThresholdPct = ringThresholdPct;
        c.referenceThreshold = referenceThreshold;
        c.partnerThreshold = partnerThreshold;
        c.doGlcm = doGlcm;
        c.doTextureClasses = doTextureClasses;
        c.glcmLevels = glcmLevels;
        c.glcmDistance = glcmDistance;
        c.textureClasses = textureClasses;
        c.minimumTextureVoxels = minimumTextureVoxels;
        return c;
    }
}
