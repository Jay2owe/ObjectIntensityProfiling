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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StackSlicesTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void byteStackMatchesDirectAccessAndSharesPixels() {
        ImagePlus image = stack(8, 5);
        StackSlices slices = StackSlices.of(image);
        assertMatches(image, slices);
        assertSame(image.getStack().getPixels(3), slices.slice(2).getPixels());
        assertFalse(slices.isVirtual());
        assertEquals(17L * 13 * 5, slices.bytes());
    }

    @Test
    public void shortStackMatchesDirectAccess() {
        ImagePlus image = stack(16, 4);
        StackSlices slices = StackSlices.of(image);
        assertMatches(image, slices);
        assertEquals(17L * 13 * 4 * 2, slices.bytes());
    }

    @Test
    public void floatStackMatchesDirectAccessIncludingNaN() {
        ImagePlus image = stack(32, 3);
        ((float[]) image.getStack().getPixels(2))[5] = Float.NaN;
        StackSlices slices = StackSlices.of(image);
        assertMatches(image, slices);
        assertTrue(Float.isNaN(slices.slice(1).getf(5, 0)));
        assertEquals(17L * 13 * 3 * 4, slices.bytes());
    }

    @Test
    public void virtualStackIsReadOnceAndMatchesTheFile() throws Exception {
        ImagePlus image = stack(16, 6);
        File file = new File(temp.getRoot(), "virtual.tif");
        assertTrue(IJ.saveAsTiff(image, file.getPath()));
        ImagePlus virtual = IJ.openVirtual(file.getPath());
        assertTrue(virtual.getStack().isVirtual());
        StackSlices slices = StackSlices.of(virtual);
        assertTrue(slices.isVirtual());
        assertEquals(6, slices.size());
        assertMatches(image, slices);
        // The cache keeps the processors it read; asking again does not go back to disk.
        assertSame(slices.slice(4), slices.slice(4));
    }

    @Test
    public void singleSliceImageHasOneSlice() {
        ImagePlus image = stack(32, 1);
        StackSlices slices = StackSlices.of(image);
        assertEquals(1, slices.size());
        assertMatches(image, slices);
    }

    @Test
    public void ofAllKeepsChannelOrder() {
        Map<String, ImagePlus> images = new LinkedHashMap<String, ImagePlus>();
        images.put("zeta", stack(32, 2));
        images.put("alpha", stack(16, 2));
        Map<String, StackSlices> all = StackSlices.ofAll(images);
        assertEquals("[zeta, alpha]", all.keySet().toString());
        assertSame(images.get("alpha"), all.get("alpha").image());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullImageIsRejected() {
        StackSlices.of(null);
    }

    private static void assertMatches(ImagePlus image, StackSlices slices) {
        ImageStack stack = image.getStack();
        assertEquals(stack.getSize(), slices.size());
        assertEquals(stack.getWidth(), slices.getWidth());
        assertEquals(stack.getHeight(), slices.getHeight());
        for (int z = 0; z < stack.getSize(); z++) {
            ImageProcessor direct = stack.getProcessor(z + 1);
            ImageProcessor cached = slices.slice(z);
            for (int y = 0; y < stack.getHeight(); y++) {
                for (int x = 0; x < stack.getWidth(); x++) {
                    assertEquals(Float.floatToRawIntBits(direct.getf(x, y)),
                            Float.floatToRawIntBits(cached.getf(x, y)));
                }
            }
        }
    }

    private static ImagePlus stack(int bitDepth, int slices) {
        int w = 17, h = 13;
        Random random = new Random(bitDepth * 31L + slices);
        ImageStack stack = new ImageStack(w, h);
        for (int z = 0; z < slices; z++) {
            ImageProcessor ip;
            if (bitDepth == 8) ip = new ByteProcessor(w, h);
            else if (bitDepth == 16) ip = new ShortProcessor(w, h);
            else ip = new FloatProcessor(w, h);
            for (int i = 0; i < w * h; i++) {
                double value = bitDepth == 32 ? random.nextGaussian() * 1000 : random.nextInt(
                        bitDepth == 8 ? 256 : 65536);
                ip.setf(i, (float) value);
            }
            stack.addSlice(ip);
        }
        return new ImagePlus("stack-" + bitDepth, stack);
    }
}
