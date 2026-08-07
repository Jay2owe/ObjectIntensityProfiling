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
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import oip.profile.LabelObjects;
import oip.texture.ObjectTextureResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.awt.image.IndexColorModel;

/**
 * Creates one class-coloured label map per raw channel.
 */
final class TextureClassMapRenderer {

    private TextureClassMapRenderer() {
    }

    static Map<String, ImagePlus> render(ImagePlus labels, List<ObjectTextureResult> results) {
        return render(labels, results, null);
    }

    static Map<String, ImagePlus> render(
            ImagePlus labels, List<ObjectTextureResult> results,
            OipParameters.CancellationToken cancellation) {
        Map<String, Map<Integer, Integer>> classes =
                new LinkedHashMap<String, Map<Integer, Integer>>();
        for (ObjectTextureResult result : results) {
            checkCancelled(cancellation);
            if (result.classLabel < 0) continue;
            Map<Integer, Integer> partner = classes.get(result.partnerChannel);
            if (partner == null) {
                partner = new LinkedHashMap<Integer, Integer>();
                classes.put(result.partnerChannel, partner);
            }
            partner.put(result.label, result.classLabel + 1);
        }

        Map<String, ImagePlus> maps = new LinkedHashMap<String, ImagePlus>();
        IndexColorModel colors = colors();
        try {
            for (Map.Entry<String, Map<Integer, Integer>> entry : classes.entrySet()) {
                ImageStack out = new ImageStack(labels.getWidth(), labels.getHeight());
                for (int z = 1; z <= labels.getStackSize(); z++) {
                    checkCancelled(cancellation);
                    ImageProcessor source = labels.getStack().getProcessor(z);
                    ByteProcessor target = new ByteProcessor(
                            labels.getWidth(), labels.getHeight());
                    for (int y = 0; y < labels.getHeight(); y++) {
                        if ((y & 31) == 0) checkCancelled(cancellation);
                        for (int x = 0; x < labels.getWidth(); x++) {
                            Integer value = entry.getValue().get(
                                    LabelObjects.label(source.getf(x, y)));
                            if (value != null) target.set(x, y, value);
                        }
                    }
                    target.setColorModel(colors);
                    out.addSlice(target);
                }
                ImagePlus image = new ImagePlus(
                        entry.getKey() + "_Texture_Classes", out);
                try {
                    image.setCalibration(labels.getCalibration().copy());
                    maps.put(entry.getKey(), image);
                } catch (RuntimeException error) {
                    close(image);
                    throw error;
                } catch (Error error) {
                    close(image);
                    throw error;
                }
            }
            return maps;
        } catch (RuntimeException error) {
            closeAll(maps);
            throw error;
        } catch (Error error) {
            closeAll(maps);
            throw error;
        }
    }

    private static void closeAll(Map<String, ImagePlus> maps) {
        for (ImagePlus image : maps.values()) close(image);
        maps.clear();
    }

    private static void close(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    private static void checkCancelled(OipParameters.CancellationToken cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ObjectIntensityProfiling.AnalysisCancelledException();
        }
    }

    static IndexColorModel colors() {
        byte[] red = new byte[256];
        byte[] green = new byte[256];
        byte[] blue = new byte[256];
        for (int i = 1; i < 256; i++) {
            // Golden-ratio stepping separates adjacent class numbers while supplying a distinct,
            // non-black indexed colour for every supported class.
            float hue = (float) ((i * 0.6180339887498949) % 1.0);
            int color = java.awt.Color.HSBtoRGB(hue, 0.72f, 0.95f);
            red[i] = (byte) ((color >> 16) & 0xff);
            green[i] = (byte) ((color >> 8) & 0xff);
            blue[i] = (byte) (color & 0xff);
        }
        return new IndexColorModel(8, 256, red, green, blue);
    }
}
