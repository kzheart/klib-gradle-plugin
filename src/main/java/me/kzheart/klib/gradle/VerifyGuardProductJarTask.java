package me.kzheart.klib.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 在上传前验证 Guard 商品 JAR 的类加载与 Collector 结构边界。 */
@DisableCachingByDefault(because = "Verification task has no outputs")
public abstract class VerifyGuardProductJarTask extends DefaultTask {
    private static final String ENTRYPOINT = "META-INF/klib-guard/entrypoint";
    private static final long MAX_ARCHIVE_BYTES = 128L << 20;
    private static final long MAX_ENTRY_BYTES = 16L << 20;
    private static final long MAX_EXPANDED_BYTES = 256L << 20;
    private static final int MAX_ENTRIES = 4096;

    @Input
    public abstract Property<Boolean> getGuardProduct();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getArchiveFile();

    @TaskAction
    public void verify() {
        if (!getGuardProduct().get()) {
            return;
        }
        File archiveFile = getArchiveFile().get().getAsFile();
        if (!archiveFile.isFile() || archiveFile.length() < 22L
                || archiveFile.length() > MAX_ARCHIVE_BYTES) {
            throw invalid("archive size is outside the Guard release boundary");
        }

        try (ZipFile archive = new ZipFile(archiveFile)) {
            if (archive.size() == 0 || archive.size() > MAX_ENTRIES) {
                throw invalid("archive entry count is outside the Guard release boundary");
            }
            Set<String> entries = new HashSet<String>();
            long expanded = 0L;
            for (ZipEntry entry : Collections.list(archive.entries())) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!canonical(name) || !entries.add(name)) {
                    throw invalid("non-canonical or duplicate entry: " + name);
                }
                long size = entry.getSize();
                long compressedSize = entry.getCompressedSize();
                if (size <= 0L || size > MAX_ENTRY_BYTES) {
                    throw invalid("empty or oversized entry: " + name);
                }
                if (compressedSize > 0L && size > compressedSize * 200L) {
                    throw invalid("suspicious compression ratio: " + name);
                }
                expanded = Math.addExact(expanded, size);
                if (expanded > MAX_EXPANDED_BYTES || forbidden(name)) {
                    throw invalid("forbidden Guard product entry: " + name);
                }
                if (name.endsWith(".class")) {
                    verifyJava8Class(archive, entry);
                }
            }

            ZipEntry descriptor = archive.getEntry(ENTRYPOINT);
            if (descriptor == null || descriptor.getSize() > 512L) {
                throw invalid("missing or oversized Guard entrypoint descriptor");
            }
            String entrypoint = new String(readBounded(archive, descriptor, 512),
                    StandardCharsets.UTF_8).trim();
            entrypoint = GuardEntrypointSpec.require(entrypoint);
            String classEntry = entrypoint.replace('.', '/') + ".class";
            if (!entries.contains(classEntry)) {
                throw invalid("Guard entrypoint class is missing: " + classEntry);
            }
            getLogger().lifecycle("Guard product verification passed: {}", archiveFile);
        } catch (IOException failure) {
            throw new GradleException("Cannot verify Guard product JAR " + archiveFile, failure);
        } catch (ArithmeticException failure) {
            throw invalid("expanded archive size overflowed");
        }
    }

    private static void verifyJava8Class(ZipFile archive, ZipEntry entry) throws IOException {
        byte[] header = readBounded(archive, entry, 8);
        if (header.length < 8
                || (header[0] & 0xff) != 0xca
                || (header[1] & 0xff) != 0xfe
                || (header[2] & 0xff) != 0xba
                || (header[3] & 0xff) != 0xbe) {
            throw invalid("invalid class file: " + entry.getName());
        }
        int minor = (header[4] & 0xff) << 8 | (header[5] & 0xff);
        int major = (header[6] & 0xff) << 8 | (header[7] & 0xff);
        if (major < 45 || major > 52 || major == 45 && minor > 3 || major > 45 && minor != 0) {
            throw invalid("class is not Java 8 compatible: " + entry.getName());
        }
    }

    private static byte[] readBounded(ZipFile archive, ZipEntry entry, int limit)
            throws IOException {
        try (InputStream input = archive.getInputStream(entry)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 1024));
            byte[] buffer = new byte[Math.min(limit, 1024)];
            int total = 0;
            while (total < limit) {
                int count = input.read(buffer, 0, Math.min(buffer.length, limit - total));
                if (count < 0) {
                    break;
                }
                output.write(buffer, 0, count);
                total += count;
            }
            return output.toByteArray();
        }
    }

    private static boolean canonical(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0) {
            return false;
        }
        for (String component : name.split("/", -1)) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)) {
                return false;
            }
        }
        return true;
    }

    private static boolean forbidden(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return "plugin.yml".equals(name)
                || lower.startsWith("meta-inf/versions/")
                || name.startsWith("META-INF/klib-guard/native/")
                || name.startsWith("me/kzheart/klib/")
                || name.startsWith("org/bukkit/")
                || lower.endsWith(".jar")
                || lower.endsWith(".zip")
                || lower.endsWith(".so")
                || lower.endsWith(".dll")
                || lower.endsWith(".dylib");
    }

    private static GradleException invalid(String detail) {
        return new GradleException("Invalid Guard product JAR: " + detail);
    }
}
