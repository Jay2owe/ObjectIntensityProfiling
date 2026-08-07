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
import ij.io.FileSaver;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.jar.JarFile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PackagingIT {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void packagedJarContainsOnlyThePrivatelyRelocatedCore()
            throws Exception {
        File project = new File(requiredProperty("oip.project.basedir"));
        File jarPath = new File(requiredProperty("oip.project.jar"));
        assertTrue(jarPath.isFile());

        JarFile jar = new JarFile(jarPath);
        try {
            assertNotNull(jar.getJarEntry(
                    "oip/internal/core/io/RegexGroupDiscovery.class"));
            assertTrue(jar.getJarEntry(
                    "sc/fiji/oc3d/core/io/RegexGroupDiscovery.class") == null);
            assertTrue(jar.getJarEntry("ij/IJ.class") == null);
            assertNotNull(jar.getJarEntry("plugins.config"));
            assertNotNull(jar.getJarEntry("oip/Object_Intensity_Profiling.class"));
            assertArrayEquals(
                    Files.readAllBytes(new File(project, "LICENSE").toPath()),
                    read(jar, "META-INF/LICENSE"));
        } finally {
            jar.close();
        }
    }

    @Test
    public void packagedJarRunsDiscoveryWithoutAnExternalCoreJar()
            throws Exception {
        File jarPath = new File(requiredProperty("oip.project.jar"));
        File imageJ = new File(ij.IJ.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        File labels = temporary.newFolder("labels");
        File raw = temporary.newFolder("raw");
        File output = temporary.newFolder("output");
        saveLabel(new File(labels, "A_labels.tif"));
        saveRaw(new File(raw, "A_raw.tif"));

        URLClassLoader loader = new URLClassLoader(
                new URL[]{jarPath.toURI().toURL(), imageJ.toURI().toURL()},
                null);
        try {
            Class<?> parametersClass = loader.loadClass(
                    "oip.OipBatchParameters");
            Object builder = parametersClass.getMethod(
                            "builder", File.class, String.class, File.class)
                    .invoke(null, labels, "(.*)_labels\\.tif", output);
            builder.getClass().getMethod(
                            "addRawChannel", String.class, File.class, String.class)
                    .invoke(builder, "Signal", raw, "(.*)_raw\\.tif");
            builder.getClass().getMethod("referenceChannel", String.class)
                    .invoke(builder, "Signal");
            Object parameters = builder.getClass().getMethod("build")
                    .invoke(builder);
            Class<?> runnerClass = loader.loadClass("oip.OipBatchRunner");
            String preview = (String) runnerClass.getMethod(
                            "preview", parametersClass)
                    .invoke(null, parameters);

            assertTrue(preview.contains("A: A_labels.tif"));
            assertTrue(preview.contains("Signal=A_raw.tif"));
            assertNotNull(loader.loadClass(
                    "oip.internal.core.io.RegexGroupDiscovery"));
        } finally {
            loader.close();
        }
    }

    private static byte[] read(JarFile jar, String name) throws Exception {
        java.io.InputStream input = jar.getInputStream(jar.getJarEntry(name));
        try {
            java.io.ByteArrayOutputStream output =
                    new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static void saveLabel(File file) {
        ByteProcessor pixels = new ByteProcessor(4, 4);
        pixels.set(1, 1, 1);
        assertTrue(new FileSaver(new ImagePlus("labels", pixels))
                .saveAsTiff(file.getAbsolutePath()));
    }

    private static void saveRaw(File file) {
        ByteProcessor pixels = new ByteProcessor(4, 4);
        pixels.setValue(10.0);
        pixels.fill();
        assertTrue(new FileSaver(new ImagePlus("raw", pixels))
                .saveAsTiff(file.getAbsolutePath()));
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Required test property is missing: " + name);
        }
        return value;
    }
}
