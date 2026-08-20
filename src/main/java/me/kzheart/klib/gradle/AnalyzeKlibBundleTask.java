package me.kzheart.klib.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 输出实际参与 Klib 打包的依赖体积与条目统计。 */
@CacheableTask
public abstract class AnalyzeKlibBundleTask extends DefaultTask {
    @Classpath
    public abstract ConfigurableFileCollection getLibraries();

    @Input
    public abstract ListProperty<String> getHostProvidedDependencies();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void analyze() {
        List<BundleEntry> entries = new ArrayList<BundleEntry>();
        for (File library : getLibraries().getFiles()) {
            entries.add(inspect(library));
        }
        entries.sort(Comparator.comparingLong(BundleEntry::archiveBytes).reversed()
                .thenComparing(BundleEntry::name));

        long archiveBytes = 0L;
        long expandedBytes = 0L;
        long entryCount = 0L;
        StringBuilder report = new StringBuilder();
        for (String dependency : getHostProvidedDependencies().get()) {
            report.append("# host-provided: ").append(dependency).append('\n');
        }
        report.append("dependency\tarchive-bytes\tentries\texpanded-bytes\n");
        for (BundleEntry entry : entries) {
            report.append(entry.name()).append('\t')
                    .append(entry.archiveBytes()).append('\t')
                    .append(entry.entries()).append('\t')
                    .append(entry.expandedBytes()).append('\n');
            archiveBytes += entry.archiveBytes();
            expandedBytes += entry.expandedBytes();
            entryCount += entry.entries();
        }
        report.append("TOTAL\t").append(archiveBytes).append('\t')
                .append(entryCount).append('\t').append(expandedBytes).append('\n');

        Path output = getReportFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Files.write(output, report.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new GradleException("Cannot write Klib bundle report " + output, failure);
        }
        getLogger().lifecycle(
                "Klib bundle: {} dependencies, {} entries, {} archive bytes; report: {}",
                entries.size(), entryCount, archiveBytes, output);
    }

    private static BundleEntry inspect(File library) {
        if (library.isDirectory()) {
            return inspectDirectory(library);
        }
        if (!library.getName().endsWith(".jar")) {
            return new BundleEntry(library.getName(), library.length(), 1L, library.length());
        }
        try (ZipFile archive = new ZipFile(library)) {
            long entries = 0L;
            long expandedBytes = 0L;
            for (ZipEntry entry : java.util.Collections.list(archive.entries())) {
                if (!entry.isDirectory()) {
                    entries++;
                    if (entry.getSize() > 0L) {
                        expandedBytes = Math.addExact(expandedBytes, entry.getSize());
                    }
                }
            }
            return new BundleEntry(
                    library.getName(), library.length(), entries, expandedBytes);
        } catch (IOException | ArithmeticException failure) {
            throw new GradleException("Cannot inspect Klib bundle dependency " + library, failure);
        }
    }

    private static BundleEntry inspectDirectory(File directory) {
        long entries = 0L;
        long expandedBytes = 0L;
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                entries++;
                expandedBytes = Math.addExact(expandedBytes, Files.size(path));
            }
            return new BundleEntry(directory.getName(), expandedBytes, entries, expandedBytes);
        } catch (IOException | ArithmeticException failure) {
            throw new GradleException("Cannot inspect Klib bundle directory " + directory, failure);
        }
    }

    private static final class BundleEntry {
        private final String name;
        private final long archiveBytes;
        private final long entries;
        private final long expandedBytes;

        private BundleEntry(String name, long archiveBytes, long entries, long expandedBytes) {
            this.name = name;
            this.archiveBytes = archiveBytes;
            this.entries = entries;
            this.expandedBytes = expandedBytes;
        }

        private String name() {
            return name;
        }

        private long archiveBytes() {
            return archiveBytes;
        }

        private long entries() {
            return entries;
        }

        private long expandedBytes() {
            return expandedBytes;
        }
    }
}
