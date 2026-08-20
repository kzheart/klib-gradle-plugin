package me.kzheart.klib.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardProductModeTest {
    @TempDir
    Path projectDirectory;

    private Path repository;

    @BeforeEach
    void publishGuardCompileContract() throws Exception {
        repository = Files.createDirectories(projectDirectory.resolve("repository"));

        Path coreJar = publishClassModule(
                "klib-core",
                "0.2.0",
                "me.kzheart.klib.scope.Scope",
                "package me.kzheart.klib.scope; public interface Scope { }",
                Collections.<Path>emptyList(),
                "");
        publishClassModule(
                "klib-guard-api",
                "0.1.0",
                "me.kzheart.klib.guard.KlibCloudPlugin",
                "package me.kzheart.klib.guard; "
                        + "import me.kzheart.klib.scope.Scope; "
                        + "public abstract class KlibCloudPlugin { "
                        + "protected abstract void setup(Scope root); }",
                Collections.singletonList(coreJar),
                "<dependencies><dependency>"
                        + "<groupId>me.kzheart.klib</groupId>"
                        + "<artifactId>klib-core</artifactId>"
                        + "<version>0.2.0</version>"
                        + "</dependency></dependencies>");
    }

    @Test
    void buildsVerifiedGuardProductWithoutBukkitDescriptorOrParentClasses() throws Exception {
        writeGuardProject(false);

        BuildResult result = GradleFixture.build(projectDirectory, "guardProductJar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateGuardEntrypoint").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyGuardProductJar").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":guardProductJar").getOutcome());
        try (ZipFile jar = outputJar()) {
            assertNotNull(jar.getEntry("com/example/CloudExample.class"));
            assertNotNull(jar.getEntry("META-INF/klib-guard/entrypoint"));
            assertNull(jar.getEntry("plugin.yml"));
            assertFalse(jar.stream().anyMatch(entry ->
                    entry.getName().startsWith("me/kzheart/klib/")));
        }
    }

    @Test
    void collectorBoundaryRejectsUserSuppliedPluginYaml() throws Exception {
        writeGuardProject(true);

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "check");

        assertTrue(result.getOutput().contains(
                "Invalid Guard product JAR: forbidden Guard product entry: plugin.yml"),
                result.getOutput());
    }

    @Test
    void missingGuardEntrypointReportsTheGuardDsl() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "version = \"1.2.3\"\n"
                + repositoryBlock()
                + "klib { guardProduct { } }\n");

        BuildResult result = GradleFixture.buildAndFail(
                projectDirectory,
                "generateGuardEntrypoint");

        assertTrue(result.getOutput().contains("klib.guardProduct.entrypoint is not set"),
                result.getOutput());
        assertFalse(result.getOutput().contains("klib.main is not set"), result.getOutput());
    }

    private void writeGuardProject(boolean pluginYaml) throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "version = \"1.2.3\"\n"
                + repositoryBlock()
                + "klib {\n"
                + "    targetPackage(\"com.example\")\n"
                + "    modules { core() }\n"
                + "    guardProduct {\n"
                + "        entrypoint(\"com.example.CloudExample\")\n"
                + "    }\n"
                + "}\n");
        Path source = projectDirectory.resolve("src/main/java/com/example/CloudExample.java");
        Files.createDirectories(source.getParent());
        Files.write(source, ("package com.example;\n"
                + "import me.kzheart.klib.guard.KlibCloudPlugin;\n"
                + "import me.kzheart.klib.scope.Scope;\n"
                + "public final class CloudExample extends KlibCloudPlugin {\n"
                + "  protected void setup(Scope root) { }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        if (pluginYaml) {
            Path resource = projectDirectory.resolve("src/main/resources/plugin.yml");
            Files.createDirectories(resource.getParent());
            Files.write(resource, "name: forbidden\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private String repositoryBlock() {
        return "repositories { maven { url = uri(\"" + repository.toUri() + "\") } }\n";
    }

    private ZipFile outputJar() throws Exception {
        return new ZipFile(projectDirectory.resolve(
                "build/libs/fixture-1.2.3-guard.jar").toFile());
    }

    private Path publishClassModule(
            String artifact,
            String version,
            String className,
            String source,
            List<Path> classpath,
            String pomBody
    ) throws Exception {
        Path module = repository.resolve(
                "me/kzheart/klib/" + artifact + "/" + version);
        Files.createDirectories(module);
        String fileName = artifact + "-" + version;
        Files.write(module.resolve(fileName + ".pom"), (""
                + "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>me.kzheart.klib</groupId>"
                + "<artifactId>" + artifact + "</artifactId>"
                + "<version>" + version + "</version>"
                + pomBody
                + "</project>").getBytes(StandardCharsets.UTF_8));

        Path sourceRoot = Files.createDirectories(
                projectDirectory.resolve("published-source/" + artifact));
        Path sourceFile = sourceRoot.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));
        Path classes = Files.createDirectories(
                projectDirectory.resolve("published-classes/" + artifact));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<String> arguments = new java.util.ArrayList<String>();
        Collections.addAll(arguments, "-source", "8", "-target", "8", "-d", classes.toString());
        if (!classpath.isEmpty()) {
            arguments.add("-classpath");
            arguments.add(joinClasspath(classpath));
        }
        arguments.add(sourceFile.toString());
        assertEquals(0, compiler.run(null, null, null, arguments.toArray(new String[0])));

        Path jarPath = module.resolve(fileName + ".jar");
        Path classFile = classes.resolve(className.replace('.', '/') + ".class");
        try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new ZipEntry(className.replace('.', '/') + ".class"));
            jar.write(Files.readAllBytes(classFile));
            jar.closeEntry();
        }
        return jarPath;
    }

    private static String joinClasspath(List<Path> values) {
        StringBuilder result = new StringBuilder();
        for (Path value : values) {
            if (result.length() > 0) {
                result.append(File.pathSeparatorChar);
            }
            result.append(value);
        }
        return result.toString();
    }
}
