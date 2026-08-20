package me.kzheart.klib.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeClasspathPackagingTest {
    @TempDir
    Path projectDirectory;

    @Test
    void packagesOnlyExplicitEmbeddedDependencies() throws Exception {
        Path libraries = Files.createDirectories(projectDirectory.resolve("libs"));
        markerJar(libraries.resolve("implementation.jar"), "fixture/implementation.marker");
        markerJar(libraries.resolve("runtime.jar"), "fixture/runtime.marker");
        markerJar(libraries.resolve("compile-only.jar"), "fixture/compile-only.marker");
        markerJar(libraries.resolve("embedded.jar"), "fixture/embedded.marker");
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "version = \"1.0.0\"\n"
                + "klib {\n"
                + "    main(\"com.example.FixturePlugin\")\n"
                + "    modules { none() }\n"
                + "}\n"
                + "dependencies {\n"
                + "    implementation(files(\"libs/implementation.jar\"))\n"
                + "    runtimeOnly(files(\"libs/runtime.jar\"))\n"
                + "    compileOnly(files(\"libs/compile-only.jar\"))\n"
                + "    klibEmbedded(files(\"libs/embedded.jar\"))\n"
                + "}\n");

        BuildResult result = GradleFixture.build(projectDirectory, "shadowJar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":shadowJar").getOutcome());
        try (ZipFile jar = outputJar()) {
            assertNull(jar.getEntry("fixture/implementation.marker"));
            assertNull(jar.getEntry("fixture/runtime.marker"));
            assertNull(jar.getEntry("fixture/compile-only.marker"));
            assertNotNull(jar.getEntry("fixture/embedded.marker"));
        }
        String report = new String(Files.readAllBytes(projectDirectory.resolve(
                "build/reports/klib/bundle-report.txt")), StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(report.contains("embedded.jar"), report);
        org.junit.jupiter.api.Assertions.assertFalse(report.contains("implementation.jar"), report);
    }

    @Test
    void appliesCustomRelocationBelowConsumerLibrariesNamespace() throws Exception {
        Path libraries = Files.createDirectories(projectDirectory.resolve("libs"));
        markerJar(libraries.resolve("external.jar"), "com/acme/library/defaults.conf");
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "version = \"1.0.0\"\n"
                + "klib {\n"
                + "    main(\"com.example.FixturePlugin\")\n"
                + "    targetPackage(\"com.example.fixture\")\n"
                + "    modules { none() }\n"
                + "    relocate(\"com.acme.library\", \"vendor.acme\")\n"
                + "}\n"
                + "dependencies { klibEmbedded(files(\"libs/external.jar\")) }\n");

        BuildResult result = GradleFixture.build(projectDirectory, "shadowJar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":shadowJar").getOutcome());
        try (ZipFile jar = outputJar()) {
            assertNotNull(jar.getEntry(
                    "com/example/fixture/libs/vendor/acme/defaults.conf"));
            assertNull(jar.getEntry("com/acme/library/defaults.conf"));
        }
    }

    private ZipFile outputJar() throws IOException {
        return new ZipFile(projectDirectory.resolve(
                "build/libs/fixture-1.0.0-all.jar").toFile());
    }

    private static void markerJar(Path output, String entry) throws IOException {
        try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(output))) {
            jar.putNextEntry(new ZipEntry(entry));
            jar.write(entry.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
}
