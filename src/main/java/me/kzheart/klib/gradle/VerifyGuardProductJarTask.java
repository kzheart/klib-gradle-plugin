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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 在上传前验证 Guard 商品 JAR 的类加载与 Collector 结构边界。 */
@DisableCachingByDefault(because = "Verification task has no outputs")
public abstract class VerifyGuardProductJarTask extends DefaultTask {
    private static final String ENTRYPOINT = "META-INF/klib-guard/entrypoint";
    private static final String KETHER_INTEROP =
            "META-INF/klib-guard/kether-interop.properties";
    private static final long MAX_ARCHIVE_BYTES = 128L << 20;
    private static final long MAX_ENTRY_BYTES = 16L << 20;
    private static final long MAX_EXPANDED_BYTES = 256L << 20;
    private static final int MAX_ENTRIES = 4096;

    @Input
    public abstract Property<Boolean> getGuardProduct();

    @Input
    public abstract Property<Boolean> getInteropEnabled();

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
            Violations violations = new Violations();
            if (archive.size() == 0 || archive.size() > MAX_ENTRIES) {
                violations.add("archive entry count",
                        archive.size() + " entries; allowed range is 1.." + MAX_ENTRIES);
            }
            Set<String> entries = new HashSet<String>();
            long expanded = 0L;
            for (ZipEntry entry : Collections.list(archive.entries())) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!canonical(name)) {
                    violations.add("non-canonical entry", name);
                    continue;
                }
                if (!entries.add(name)) {
                    violations.add("duplicate entry", name);
                    continue;
                }
                long size = entry.getSize();
                long compressedSize = entry.getCompressedSize();
                if (size <= 0L || size > MAX_ENTRY_BYTES) {
                    violations.add("empty or oversized entry", name + " (" + size + " bytes)");
                }
                if (compressedSize > 0L && size > compressedSize * 200L) {
                    violations.add("suspicious compression ratio", name);
                }
                if (size > 0L && expanded <= MAX_EXPANDED_BYTES) {
                    if (size > MAX_EXPANDED_BYTES - expanded) {
                        expanded = MAX_EXPANDED_BYTES + 1L;
                    } else {
                        expanded += size;
                    }
                }
                String forbidden = forbiddenReason(name);
                if (forbidden != null) {
                    violations.add(forbidden, name);
                }
                if (name.endsWith(".class")) {
                    String classViolation = verifyJava8Class(archive, entry);
                    if (classViolation != null) {
                        violations.add(classViolation, name);
                    }
                }
            }
            if (expanded > MAX_EXPANDED_BYTES) {
                violations.add("expanded archive size",
                        "exceeds " + MAX_EXPANDED_BYTES + " bytes");
            }

            ZipEntry descriptor = archive.getEntry(ENTRYPOINT);
            if (descriptor == null || descriptor.getSize() > 512L) {
                violations.add("Guard entrypoint descriptor",
                        descriptor == null ? "missing " + ENTRYPOINT : "descriptor exceeds 512 bytes");
            } else {
                String entrypoint = new String(readBounded(archive, descriptor, 512),
                        StandardCharsets.UTF_8).trim();
                try {
                    entrypoint = GuardEntrypointSpec.require(entrypoint);
                    String classEntry = entrypoint.replace('.', '/') + ".class";
                    if (!entries.contains(classEntry)) {
                        violations.add("Guard entrypoint class is missing", classEntry);
                    }
                } catch (GradleException failure) {
                    violations.add("invalid Guard entrypoint descriptor", failure.getMessage());
                }
            }
            ZipEntry ketherInterop = archive.getEntry(KETHER_INTEROP);
            if (getInteropEnabled().get() && ketherInterop == null) {
                violations.add("Guard Kether interoperability descriptor",
                        "missing " + KETHER_INTEROP);
            }
            if (!getInteropEnabled().get() && ketherInterop != null) {
                violations.add("Guard Kether interoperability descriptor",
                        "unexpected " + KETHER_INTEROP);
            }
            if (ketherInterop != null) {
                String ketherDescriptor = new String(readBounded(archive, ketherInterop, 128),
                        StandardCharsets.UTF_8);
                if (!GenerateGuardKetherInteropTask.FORMAT.equals(ketherDescriptor)) {
                    violations.add("Guard Kether interoperability descriptor",
                            "invalid content in " + KETHER_INTEROP);
                }
            }
            if (!violations.isEmpty()) {
                throw invalid(violations.format());
            }
            getLogger().lifecycle("Guard product verification passed: {}", archiveFile);
        } catch (IOException failure) {
            throw new GradleException("Cannot verify Guard product JAR " + archiveFile, failure);
        }
    }

    private static String verifyJava8Class(ZipFile archive, ZipEntry entry) throws IOException {
        byte[] header = readBounded(archive, entry, 8);
        if (header.length < 8
                || (header[0] & 0xff) != 0xca
                || (header[1] & 0xff) != 0xfe
                || (header[2] & 0xff) != 0xba
                || (header[3] & 0xff) != 0xbe) {
            return "invalid class file";
        }
        int minor = (header[4] & 0xff) << 8 | (header[5] & 0xff);
        int major = (header[6] & 0xff) << 8 | (header[7] & 0xff);
        if (major < 45 || major > 52 || major == 45 && minor > 3 || major > 45 && minor != 0) {
            return "class is not Java 8 compatible";
        }
        return null;
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

    private static String forbiddenReason(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if ("plugin.yml".equals(lower)) {
            return "Bukkit descriptor is forbidden";
        }
        if (lower.startsWith("meta-inf/versions/")) {
            return "multi-release entry is forbidden";
        }
        if (name.startsWith("META-INF/klib-guard/native/")
                || lower.endsWith(".so") || lower.endsWith(".dll")
                || lower.endsWith(".dylib")) {
            return "native library is forbidden";
        }
        if (name.startsWith("me/kzheart/klib/") || name.startsWith("org/bukkit/")) {
            return "parent-provided namespace is forbidden";
        }
        if (lower.endsWith(".jar") || lower.endsWith(".zip")) {
            return "nested archive is forbidden";
        }
        return null;
    }

    private static GradleException invalid(String detail) {
        return new GradleException("Invalid Guard product JAR: " + detail);
    }

    private static final class Violations {
        private static final int MAX_EXAMPLES = 3;
        private final Map<String, Bucket> buckets = new LinkedHashMap<String, Bucket>();

        private void add(String reason, String detail) {
            Bucket bucket = buckets.get(reason);
            if (bucket == null) {
                bucket = new Bucket();
                buckets.put(reason, bucket);
            }
            bucket.count++;
            if (bucket.examples.size() < MAX_EXAMPLES) {
                bucket.examples.add(detail);
            }
        }

        private boolean isEmpty() {
            return buckets.isEmpty();
        }

        private String format() {
            StringBuilder message = new StringBuilder("release boundary violations:");
            for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
                message.append("\n - ").append(entry.getKey())
                        .append(" (").append(entry.getValue().count).append("): ")
                        .append(String.join(", ", entry.getValue().examples));
            }
            return message.toString();
        }
    }

    private static final class Bucket {
        private int count;
        private final List<String> examples = new ArrayList<String>();
    }
}
