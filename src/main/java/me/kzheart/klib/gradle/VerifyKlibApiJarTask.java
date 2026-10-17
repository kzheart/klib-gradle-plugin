package me.kzheart.klib.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 声明的原始 API JAR 在发布与 check 前独立检查。 */
@org.gradle.work.DisableCachingByDefault(because = "Verification has no outputs and must run before promoting or publishing archives")
public abstract class VerifyKlibApiJarTask extends DefaultTask {
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) public abstract ConfigurableFileCollection getArchives();
    @Input public abstract ListProperty<String> getPackages();
    @Input public abstract ListProperty<String> getHostPackages();
    @TaskAction public void verify() {
        if (getArchives().isEmpty()) {
            if (!getPackages().get().isEmpty()) throw new GradleException("klib.api.archive is required when API packages are declared");
            return;
        }
        ApiBoundaryVerifier verifier = new ApiBoundaryVerifier(getPackages().get(), getHostPackages().get());
        for (File archive : getArchives()) verifyArchive(archive, verifier);
    }
    private static void verifyArchive(File archive, ApiBoundaryVerifier verifier) {
        Set<String> entries = new HashSet<String>(); long total = 0;
        try (ZipFile zip = new ZipFile(archive)) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.isDirectory()) continue;
                if (!entries.add(entry.getName())) throw new GradleException("Duplicate API entry: " + entry.getName());
                if (entries.size() > KlibShadeJarTask.MAX_ENTRIES) throw new GradleException("API JAR contains too many entries");
                byte[] bytes;
                try (InputStream input = zip.getInputStream(entry)) { bytes = KlibShadeJarTask.readBounded(input, KlibShadeJarTask.MAX_TOTAL_BYTES - total, entry.getName()); }
                total += bytes.length;
                JarBoundary.entry(entry.getName(), bytes, Collections.emptyList(), false);
                String path = JarBoundary.logicalPath(entry.getName());
                if (path.endsWith(".class")) verifier.verify(bytes, path);
                else if (!(path.equals("META-INF/MANIFEST.MF") || path.startsWith("META-INF/LICENSE") || path.startsWith("META-INF/NOTICE")
                        || path.endsWith(".kotlin_module"))) throw new GradleException("Unexpected API resource: " + path);
                if (path.endsWith(".kotlin_module")) KotlinMetadataRelocator.verifyApiModule(bytes, verifier::checkMetadata);
            }
        } catch (IOException failure) { throw new GradleException("Cannot verify API JAR " + archive, failure); }
    }
}
