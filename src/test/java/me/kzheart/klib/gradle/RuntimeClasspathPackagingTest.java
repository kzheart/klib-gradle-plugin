package me.kzheart.klib.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
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

    @Test
    void hostProvidedCompileOnlyDependencyRemainsVisibleButIsNotBundled() throws Exception {
        Path repository = Files.createDirectories(projectDirectory.resolve("repository"));
        publishSpigotApi(repository);
        Path source = projectDirectory.resolve("src/main/java/com/example/FixturePlugin.java");
        Files.createDirectories(source.getParent());
        Files.write(source, ("package com.example;\n"
                + "import org.bukkit.Bukkit;\n"
                + "public final class FixturePlugin {\n"
                + "  public String marker() { return Bukkit.marker(); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "version = \"1.0.0\"\n"
                + "repositories { maven { url = uri(\"" + repository.toUri() + "\") } }\n"
                + "klib {\n"
                + "    main(\"com.example.FixturePlugin\")\n"
                + "    modules { none() }\n"
                + "}\n"
                + "dependencies {\n"
                + "    compileOnly(\"org.spigotmc:spigot-api:1.0.0\") {\n"
                + "        isTransitive = false\n"
                + "    }\n"
                + "}\n");

        BuildResult result = GradleFixture.build(projectDirectory, "shadowJar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        try (ZipFile jar = outputJar()) {
            assertNotNull(jar.getEntry("com/example/FixturePlugin.class"));
            assertNull(jar.getEntry("org/bukkit/Bukkit.class"));
            assertNull(jar.getEntry("fixture/spigot-api.marker"));
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

    private void publishSpigotApi(Path repository) throws Exception {
        Path module = repository.resolve("org/spigotmc/spigot-api/1.0.0");
        Files.createDirectories(module);
        Files.write(module.resolve("spigot-api-1.0.0.pom"), (""
                + "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>org.spigotmc</groupId>"
                + "<artifactId>spigot-api</artifactId>"
                + "<version>1.0.0</version>"
                + "</project>").getBytes(StandardCharsets.UTF_8));

        Path source = projectDirectory.resolve("spigot-source/org/bukkit/Bukkit.java");
        Files.createDirectories(source.getParent());
        Files.write(source, ("package org.bukkit; public final class Bukkit { "
                + "public static String marker() { return \"provided\"; } }")
                .getBytes(StandardCharsets.UTF_8));
        Path classes = Files.createDirectories(projectDirectory.resolve("spigot-classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(
                null, null, null,
                "-source", "8", "-target", "8",
                "-d", classes.toString(), source.toString()));

        Path output = module.resolve("spigot-api-1.0.0.jar");
        try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(output))) {
            jar.putNextEntry(new ZipEntry("org/bukkit/Bukkit.class"));
            jar.write(Files.readAllBytes(classes.resolve("org/bukkit/Bukkit.class")));
            jar.closeEntry();
            jar.putNextEntry(new ZipEntry("fixture/spigot-api.marker"));
            jar.write("provided".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
}
