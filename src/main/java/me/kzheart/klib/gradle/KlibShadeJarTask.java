package me.kzheart.klib.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.jar.Manifest;
import java.util.jar.Attributes;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 构建包含重定位后 klib 模块及依赖的确定性插件 JAR。 */
@CacheableTask
public abstract class KlibShadeJarTask extends DefaultTask {
    static final long MAX_ENTRY_BYTES = 64L * 1024L * 1024L;
    static final long MAX_TOTAL_BYTES = 512L * 1024L * 1024L;
    static final int MAX_ENTRIES = 50000;

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getBaseJar();

    @Classpath
    public abstract ConfigurableFileCollection getLibraries();

    @Classpath
    public abstract ConfigurableFileCollection getGuardBoundaryClasspath();

    private GuardKotlinBoundaryVerifier.Hierarchy guardBoundaryHierarchy;

    @Input
    public abstract MapProperty<String, String> getRelocations();

    @Input
    public abstract ListProperty<String> getProtectedRelocationPrefixes();

    @Input
    public abstract Property<Boolean> getGuardProduct();

    @Input public abstract Property<Boolean> getPaper();
    @Input public abstract ListProperty<String> getHostPackages();

    @OutputFile
    public abstract RegularFileProperty getArchiveFile();

    @TaskAction
    public void shade() {
        JarBoundary.packages(getHostPackages().getOrElse(Collections.emptyList()));
        if (getPaper().getOrElse(false)) for (Map.Entry<String, String> rule : getRelocations().get().entrySet())
            JarBoundary.relocation(rule.getKey(), rule.getValue(), getHostPackages().get());
        Map<String, Resource> entries = new LinkedHashMap<String, Resource>();
        ReadBudget budget = new ReadBudget();
        File base = getBaseJar().get().getAsFile();
        rejectConsumerNamespaceCollisions(base, getRelocations().get());
        List<File> libraries = new ArrayList<File>(getLibraries().getFiles());
        Collections.sort(libraries, Comparator.comparing(File::getAbsolutePath));
        if (getGuardProduct().getOrElse(false)) {
            guardBoundaryHierarchy = GuardKotlinBoundaryVerifier.Hierarchy.fromClasspath(
                    base, libraries, getGuardBoundaryClasspath().getFiles());
        }
        readArchive(base, false, true, entries, budget);
        for (File library : libraries) {
            if (library.equals(base)) {
                continue;
            }
            if (library.isDirectory()) {
                readDirectory(library, entries, budget);
            } else if (library.getName().endsWith(".jar")) {
                readArchive(library, true, true, entries, budget);
            }
        }
        preserveMultiReleaseManifest(entries);
        writeArchive(entries);
    }

    private static void rejectConsumerNamespaceCollisions(
            File base,
            Map<String, String> relocations
    ) {
        try (ZipFile zip = new ZipFile(base)) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                for (String source : relocations.keySet()) {
                    String prefix = source.replace('.', '/') + "/";
                    if (entry.getName().startsWith(prefix)) {
                        throw new GradleException(
                                "Consumer class '" + entry.getName()
                                        + "' conflicts with relocated namespace '" + source
                                        + "'. Move plugin code outside that package.");
                    }
                }
            }
        } catch (IOException failure) {
            throw new GradleException("Cannot validate consumer jar " + base, failure);
        }
    }

    private void readArchive(
            File archive,
            boolean relocatePath,
            boolean relocateContent,
            Map<String, Resource> entries,
            ReadBudget budget
    ) {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry manifestEntry = zip.getEntry("META-INF/MANIFEST.MF");
            boolean multiRelease = false;
            if (manifestEntry != null) {
                try (InputStream input = zip.getInputStream(manifestEntry)) {
                    Manifest manifest = new Manifest(new ByteArrayInputStream(
                            readBounded(input, budget.remaining(), "META-INF/MANIFEST.MF")));
                    multiRelease = "true".equalsIgnoreCase(
                            manifest.getMainAttributes().getValue("Multi-Release"));
                }
            }
            List<? extends ZipEntry> archiveEntries = Collections.list(zip.entries());
            Collections.sort(archiveEntries, Comparator.comparing(ZipEntry::getName));
            for (ZipEntry entry : archiveEntries) {
                if (!entry.isDirectory() && getPaper().getOrElse(false) && relocatePath && "plugin.yml".equalsIgnoreCase(JarBoundary.logicalPath(entry.getName())))
                    throw new GradleException("Embedded library must not contain plugin.yml: " + archive);
                if (entry.isDirectory() || excluded(entry.getName(), relocatePath)) {
                    continue;
                }
                if (entry.getName().startsWith("META-INF/versions/") && !multiRelease) {
                    throw new GradleException("Versioned shade entry without Multi-Release: true: "
                            + archive + "!/" + entry.getName());
                }
                budget.entry(entry.getName());
                try (InputStream input = zip.getInputStream(entry)) {
                    byte[] content = readBounded(input, budget.remaining(), entry.getName());
                    budget.bytes(content.length, entry.getName());
                    add(entry.getName(), content, relocatePath, relocateContent, entries);
                }
            }
        } catch (IOException failure) {
            throw new GradleException("Cannot read shade input " + archive, failure);
        }
    }

    private void readDirectory(File directory, Map<String, Resource> entries, ReadBudget budget) {
        Path root = directory.toPath();
        final boolean multiRelease;
        Path manifestPath = root.resolve("META-INF/MANIFEST.MF");
        try {
            if (Files.isRegularFile(manifestPath)) {
                try (InputStream input = Files.newInputStream(manifestPath)) {
                    Manifest manifest = new Manifest(new ByteArrayInputStream(
                            readBounded(input, budget.remaining(), "META-INF/MANIFEST.MF")));
                    multiRelease = "true".equalsIgnoreCase(manifest.getMainAttributes().getValue("Multi-Release"));
                }
            } else {
                multiRelease = false;
            }
        } catch (IOException failure) {
            throw new GradleException("Cannot read shade directory manifest " + directory, failure);
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> {
                        String name = root.relativize(path).toString().replace(File.separatorChar, '/');
                        if (getPaper().getOrElse(false) && "plugin.yml".equalsIgnoreCase(JarBoundary.logicalPath(name)))
                            throw new GradleException("Embedded directory must not contain plugin.yml: " + path);
                        if (!excluded(name, true)) {
                            if (name.startsWith("META-INF/versions/") && !multiRelease) {
                                throw new GradleException("Versioned shade entry without Multi-Release: true: " + path);
                            }
                            try {
                                budget.entry(name);
                                byte[] content;
                                try (InputStream input = Files.newInputStream(path)) {
                                    content = readBounded(input, budget.remaining(), name);
                                }
                                budget.bytes(content.length, name);
                                add(name, content, true, true, entries);
                            } catch (IOException failure) {
                                throw new UncheckedShadeException(failure);
                            }
                        }
                    });
        } catch (UncheckedShadeException failure) {
            throw new GradleException("Cannot read shade directory " + directory, failure.getCause());
        } catch (IOException failure) {
            throw new GradleException("Cannot read shade directory " + directory, failure);
        }
    }

    private void add(
            String originalName,
            byte[] originalContent,
            boolean relocatePath,
            boolean relocateContent,
            Map<String, Resource> entries
    ) {
        JarBoundary.entry(originalName, originalContent, getHostPackages().getOrElse(Collections.emptyList()), getPaper().getOrElse(false));
        if ("plugin.yml".equalsIgnoreCase(JarBoundary.logicalPath(originalName))) JarBoundary.strictUtf8(originalContent);
        Map<String, String> relocations = getRelocations().get();
        List<String> protectedPrefixes = getProtectedRelocationPrefixes().get();
        String name = relocatePath
                ? relocatePath(originalName, relocations, protectedPrefixes)
                : originalName;
        byte[] content = originalContent;
        try {
            if (relocateContent && originalName.endsWith(".class")) {
                if (getGuardProduct().getOrElse(false)) {
                    GuardKotlinBoundaryVerifier.verify(
                            originalContent, relocations, protectedPrefixes, guardBoundaryHierarchy);
                }
                content = ClassRelocator.relocate(
                        originalContent,
                        relocations,
                        protectedPrefixes);
            } else if (relocateContent && originalName.endsWith(".kotlin_module")) {
                content = KotlinMetadataRelocator.relocateModule(
                        originalContent, relocations, protectedPrefixes);
            } else if (relocateContent && originalName.endsWith(".kotlin_builtins")) {
                content = KotlinMetadataRelocator.relocateBuiltIns(
                        originalContent, relocations, protectedPrefixes);
            } else if (relocateContent && isTextMetadata(originalName)) {
                String text = new String(originalContent, StandardCharsets.UTF_8);
                content = ClassRelocator.replace(
                        text,
                        relocations,
                        protectedPrefixes).getBytes(StandardCharsets.UTF_8);
            }

        } catch (RuntimeException failure) {
            throw new GradleException("Cannot relocate shade entry " + originalName + ": " + failure.getMessage(), failure);
        }

        if ("plugin.yml".equalsIgnoreCase(name) && entries.containsKey(name))
            throw new GradleException("Duplicate plugin.yml in shade inputs");
        Resource previous = entries.get(name);
        if (previous == null) {
            entries.put(name, new Resource(content));
        } else if (name.endsWith(".kotlin_module")) {
            entries.put(name, new Resource(KotlinMetadataRelocator.mergeModules(
                    previous.content(), content)));
        } else if (name.endsWith(".class") && !java.util.Arrays.equals(previous.content(), content)) {
            throw new GradleException("Conflicting classes in embedded closure: " + name);
        } else if (isMergeable(name)) {
            previous.merge(content);
        }
    }

    private static void preserveMultiReleaseManifest(Map<String, Resource> entries) {
        boolean multiRelease = entries.keySet().stream()
                .anyMatch(name -> name.startsWith("META-INF/versions/"));
        if (!multiRelease) {
            return;
        }
        try {
            Resource original = entries.get("META-INF/MANIFEST.MF");
            Manifest manifest = original == null ? new Manifest()
                    : new Manifest(new ByteArrayInputStream(original.content()));
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().putValue("Multi-Release", "true");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            manifest.write(bytes);
            entries.put("META-INF/MANIFEST.MF", new Resource(bytes.toByteArray()));
        } catch (IOException failure) {
            throw new GradleException("Cannot preserve multi-release manifest", failure);
        }
    }

    private void writeArchive(Map<String, Resource> entries) {
        Path output = getArchiveFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Path temporary = Files.createTempFile(output.getParent(), output.getFileName().toString(), ".tmp");
            try {
                try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                    for (Map.Entry<String, Resource> entry : entries.entrySet()) {
                        ZipEntry zipEntry = new ZipEntry(entry.getKey());
                        zipEntry.setTime(0L);
                        zip.putNextEntry(zipEntry);
                        zip.write(entry.getValue().content());
                        zip.closeEntry();
                    }
                }
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new GradleException("Cannot write shaded plugin jar " + output, failure);
        }
    }

    private static String relocatePath(
            String path,
            Map<String, String> relocations,
            List<String> protectedPrefixes
    ) {
        String services = "META-INF/services/";
        if (path.startsWith(services)) {
            String service = path.substring(services.length()).replace('.', '/');
            return services + ClassRelocator.relocateInternalName(service, relocations, protectedPrefixes)
                    .replace('/', '.');
        }
        String versions = "META-INF/versions/";
        if (path.startsWith(versions)) {
            int separator = path.indexOf('/', versions.length());
            if (separator < 0) throw new GradleException("Invalid multi-release entry: " + path);
            return path.substring(0, separator + 1)
                    + relocatePath(path.substring(separator + 1), relocations, protectedPrefixes);
        }
        // Resource basenames are not package names: kotlin/kotlin.kotlin_builtins must
        // become <private>/kotlin/kotlin.kotlin_builtins, never rename the second kotlin.
        return ClassRelocator.relocateInternalName(path, relocations, protectedPrefixes);
    }

    private static boolean excluded(String name, boolean library) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        if (upper.endsWith("/MODULE-INFO.CLASS")
                || "MODULE-INFO.CLASS".equals(upper)) {
            return true;
        }
        if (upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC") || upper.equals("META-INF/INDEX.LIST"))) {
            return true;
        }
        return library && ("META-INF/MANIFEST.MF".equals(upper) || "PLUGIN.YML".equals(upper));
    }

    private static boolean isTextMetadata(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("meta-inf/services/")
                || lower.startsWith("meta-inf/spring")
                || lower.startsWith("meta-inf/native-image/")
                || lower.endsWith(".properties")
                || lower.endsWith(".conf")
                || lower.endsWith(".json")
                || lower.endsWith(".xml")
                || lower.endsWith(".yml")
                || lower.endsWith(".yaml");
    }

    private static boolean isMergeable(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("meta-inf/services/")
                || lower.equals("meta-inf/spring.factories")
                || lower.equals("meta-inf/spring.handlers")
                || lower.equals("meta-inf/spring.schemas")
                || lower.equals("reference.conf");
    }

    static byte[] readBounded(InputStream input, long totalRemaining, String name) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long limit = Math.min(MAX_ENTRY_BYTES, totalRemaining);
        long total = 0L;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > limit) {
                throw new IOException("Shade input exceeds the expansion limit: " + name);
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static final class ReadBudget {
        private int entries;
        private long bytes;

        private void entry(String name) {
            entries++;
            if (entries > MAX_ENTRIES) {
                throw new GradleException("Shade input contains too many entries at " + name);
            }
        }

        private long remaining() {
            return MAX_TOTAL_BYTES - bytes;
        }

        private void bytes(long amount, String name) {
            bytes += amount;
            if (bytes > MAX_TOTAL_BYTES) {
                throw new GradleException("Shade inputs exceed the total expansion limit at " + name);
            }
        }
    }

    private static final class Resource {
        private final Set<String> mergedLines = new LinkedHashSet<String>();
        private byte[] content;

        private Resource(byte[] content) {
            this.content = content;
            addLines(content);
        }

        private void merge(byte[] addition) {
            addLines(addition);
            StringBuilder merged = new StringBuilder();
            for (String line : mergedLines) {
                merged.append(line).append('\n');
            }
            content = merged.toString().getBytes(StandardCharsets.UTF_8);
        }

        private void addLines(byte[] value) {
            String text = new String(value, StandardCharsets.UTF_8);
            String[] lines = text.split("\\r?\\n");
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    mergedLines.add(line);
                }
            }
        }

        private byte[] content() {
            return content;
        }
    }

    private static final class UncheckedShadeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private UncheckedShadeException(IOException cause) {
            super(cause);
        }
    }
}
