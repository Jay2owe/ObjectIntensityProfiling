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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Process- and thread-safe exclusive lock for publishing one OIP output tree.
 */
public final class OutputDirectoryLock implements Closeable {
    private static final String LOCK_FILE = ".OIP_output.lock";
    private static final ConcurrentHashMap<String, ReentrantLock> JVM_LOCKS =
            new ConcurrentHashMap<String, ReentrantLock>();

    private final ReentrantLock jvmLock;
    private final FileChannel channel;
    private final FileLock fileLock;

    private OutputDirectoryLock(
            ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {
        this.jvmLock = jvmLock;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    public static OutputDirectoryLock acquire(File outputDirectory) throws IOException {
        if (outputDirectory == null) throw new IOException("Output directory is required.");
        File absolute = outputDirectory.getAbsoluteFile();
        Path originalPath = absolute.toPath().normalize();
        if (java.nio.file.Files.exists(originalPath, LinkOption.NOFOLLOW_LINKS)) {
            rejectLinkOrReparsePoint(originalPath, "output directory");
        }
        File canonical = originalPath.toFile().getCanonicalFile();
        if (!canonical.isDirectory() && !canonical.mkdirs()) {
            throw new IOException("Could not create output directory: " + canonical);
        }
        rejectLinkOrReparsePoint(canonical.toPath(), "output directory");
        String key = canonical.getPath();
        if (File.separatorChar == '\\') key = key.toLowerCase(Locale.ROOT);
        ReentrantLock jvmLock = JVM_LOCKS.get(key);
        if (jvmLock == null) {
            ReentrantLock candidate = new ReentrantLock();
            ReentrantLock existing = JVM_LOCKS.putIfAbsent(key, candidate);
            jvmLock = existing == null ? candidate : existing;
        }
        jvmLock.lock();

        FileChannel channel = null;
        try {
            Path lockPath = new File(canonical, LOCK_FILE).toPath();
            if (java.nio.file.Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                rejectLinkOrReparsePoint(lockPath, "output lock");
                if (!java.nio.file.Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Output lock is not a regular file: " + lockPath);
                }
            }
            channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            rejectLinkOrReparsePoint(lockPath, "output lock");
            FileLock fileLock = channel.lock();
            return new OutputDirectoryLock(jvmLock, channel, fileLock);
        } catch (IOException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            jvmLock.unlock();
            throw e;
        } catch (RuntimeException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            jvmLock.unlock();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            fileLock.release();
        } catch (IOException e) {
            failure = e;
        }
        try {
            channel.close();
        } catch (IOException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        } finally {
            jvmLock.unlock();
        }
        if (failure != null) throw failure;
    }

    private static void rejectLinkOrReparsePoint(Path path, String role)
            throws IOException {
        BasicFileAttributes attributes = java.nio.file.Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()
                || java.nio.file.Files.isSymbolicLink(path)) {
            throw new IOException("Linked or redirected " + role
                    + " paths are not allowed: " + path);
        }
    }
}
