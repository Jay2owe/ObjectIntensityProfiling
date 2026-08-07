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

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ObjectProfileFigureWriterTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void limitsIncludeSemAndCentreConstantCurves() {
        ProfileAggregator.AggregatedProfile withSem =
                new ProfileAggregator.AggregatedProfile(
                        "source", "partner", ProfileAggregator.RADIAL, "group",
                        new double[] {0.5}, new double[] {10}, new double[] {5},
                        new int[] {2});
        double[] semLimits = ObjectProfileFigureWriter.limits(
                Collections.singletonList(withSem));
        assertTrue(semLimits[2] < 5);
        assertTrue(semLimits[3] > 15);

        ProfileAggregator.AggregatedProfile constant =
                new ProfileAggregator.AggregatedProfile(
                        "source", "partner", ProfileAggregator.RADIAL, "group",
                        new double[] {0.5}, new double[] {10}, new double[] {Double.NaN},
                        new int[] {1});
        double[] constantLimits = ObjectProfileFigureWriter.limits(
                Collections.singletonList(constant));
        assertTrue(constantLimits[2] < 10);
        assertTrue(constantLimits[3] > 10);
        assertTrue(constantLimits[2] > 1);
    }

    @Test
    public void aggregateSemIsStableAtLargeOffsets() {
        ProfileAggregator aggregate = new ProfileAggregator();
        aggregate.add("source", "partner", ProfileAggregator.RADIAL, "group",
                new double[] {100000000});
        aggregate.add("source", "partner", ProfileAggregator.RADIAL, "group",
                new double[] {100000008});

        ProfileAggregator.AggregatedProfile result = aggregate.results().get(0);

        assertEquals(100000004, result.mean[0], 0.0);
        assertEquals(4.0, result.sem[0], 0.0);
    }

    @Test
    public void unmanifestedExactFigureTargetIsNotOverwritten() throws Exception {
        File directory = temporary.newFolder("figure-collision");
        File target = new File(directory, "OIP_source__group__Radial.png");
        Files.write(target.toPath(), "unrelated".getBytes(StandardCharsets.UTF_8));
        ProfileAggregator aggregate = new ProfileAggregator();
        aggregate.add("source", "partner", ProfileAggregator.RADIAL, "group",
                new double[] {1, 2});

        boolean rejected = false;
        try {
            ObjectProfileFigureWriter.writeFigures(
                    directory, aggregate, null);
        } catch (java.io.IOException expected) {
            rejected = true;
        }

        assertTrue(rejected);
        assertEquals("unrelated", new String(
                Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void panelGroupsCannotCollideThroughDelimiterText() {
        ProfileAggregator aggregate = new ProfileAggregator();
        aggregate.add("a", "partner", "d", "b|c", new double[] {1});
        aggregate.add("a|b", "partner", "d", "c", new double[] {2});

        java.util.List<ij.ImagePlus> figures =
                ObjectProfileFigureWriter.createFigures(aggregate, null, null);

        assertEquals(2, figures.size());
        for (ij.ImagePlus figure : figures) {
            figure.close();
            figure.flush();
        }
    }
}
