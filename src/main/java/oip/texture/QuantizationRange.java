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
package oip.texture;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import oip.ObjectIntensityProfiling;
import oip.OipParameters;

/**
 * Fixed intensity range used to quantise GLCM values comparably across a batch.
 */
public final class QuantizationRange {
    public final double min;
    public final double max;

    public QuantizationRange(double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || max < min) {
            throw new IllegalArgumentException("Quantisation range must be finite and ordered.");
        }
        this.min = min;
        this.max = max;
    }

    public static QuantizationRange scan(ImagePlus image) {
        return scan(image, null, null);
    }

    public static QuantizationRange scan(
            ImagePlus image,
            OipParameters.CancellationToken cancellation,
            OipParameters.ProgressListener progress) {
        if (image == null || image.getStack() == null) {
            throw new IllegalArgumentException("Raw image must contain pixels.");
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        ImageStack stack = image.getStack();
        for (int z = 1; z <= stack.getSize(); z++) {
            if (cancellation != null && cancellation.isCancelled()) {
                throw new ObjectIntensityProfiling.AnalysisCancelledException();
            }
            ImageProcessor ip = stack.getProcessor(z);
            for (int y = 0; y < image.getHeight(); y++) {
                if ((y & 31) == 0 && cancellation != null && cancellation.isCancelled()) {
                    throw new ObjectIntensityProfiling.AnalysisCancelledException();
                }
                for (int x = 0; x < image.getWidth(); x++) {
                    double value = ip.getf(x, y);
                    if (!Double.isFinite(value)) continue;
                    if (value < min) min = value;
                    if (value > max) max = value;
                }
            }
            if (progress != null) {
                progress.onProgress((double) z / stack.getSize(),
                        "Scanning range slice " + z + " of " + stack.getSize());
            }
        }
        if (!Double.isFinite(min)) {
            throw new IllegalArgumentException("Raw image has no finite pixels: " + image.getTitle());
        }
        return new QuantizationRange(min, max);
    }

    public QuantizationRange include(QuantizationRange other) {
        if (other == null) return this;
        return new QuantizationRange(Math.min(min, other.min), Math.max(max, other.max));
    }
}
