package me.kzheart.klib.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 验证候选插件包，成功后才能晋升正式包。 */
@org.gradle.work.DisableCachingByDefault(because = "Verification has no outputs and must run before promoting or publishing archives")
public abstract class VerifyKlibJarTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) public abstract RegularFileProperty getArchiveFile();
    @Input public abstract Property<Boolean> getPaper();
    @Input public abstract Property<Boolean> getGuardProduct();
    @Input public abstract ListProperty<String> getHostPackages();
    @Input public abstract Property<String> getBuildVersion();
    @TaskAction public void verify() {
        JarBoundary.packages(getHostPackages().get());
        Set<String> names = new HashSet<String>(); int descriptors = 0; long total = 0;
        try (ZipFile zip = new ZipFile(getArchiveFile().get().getAsFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.isDirectory()) continue;
                if (!names.add(entry.getName())) throw new GradleException("Duplicate JAR entry: " + entry.getName());
                byte[] bytes;
                try (InputStream input = zip.getInputStream(entry)) { bytes = KlibShadeJarTask.readBounded(input, KlibShadeJarTask.MAX_TOTAL_BYTES - total, entry.getName()); }
                total += bytes.length;
                JarBoundary.entry(entry.getName(), bytes, getHostPackages().get(), getPaper().get());
                if ("plugin.yml".equalsIgnoreCase(JarBoundary.logicalPath(entry.getName()))) {
                    if (!"plugin.yml".equals(entry.getName())) throw new GradleException("plugin.yml must occur once at the JAR root");
                    descriptors++; JarBoundary.pluginYaml(bytes, getBuildVersion().get());
                }
            }
            if (descriptors != (getGuardProduct().get() ? 0 : 1)) throw new GradleException("Expected exactly one plugin.yml for Bukkit/Paper, none for Guard");
        } catch (IOException failure) { throw new GradleException("Cannot verify Klib JAR", failure); }
    }
}
