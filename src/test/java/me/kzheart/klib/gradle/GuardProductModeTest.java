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
                GradleFixture.KLIB_VERSION,
                "me.kzheart.klib.scope.Scope",
                "package me.kzheart.klib.scope; public interface Scope { }",
                Collections.<Path>emptyList(),
                "");
        publishClassModule(
                "klib-guard-api",
                "0.2.0",
                "me.kzheart.klib.guard.KlibCloudPlugin",
                "package me.kzheart.klib.guard; "
                        + "import me.kzheart.klib.scope.Scope; "
                        + "public abstract class KlibCloudPlugin { "
                        + "protected abstract void setup(Scope root); }",
                Collections.singletonList(coreJar),
                "<dependencies><dependency>"
                        + "<groupId>me.kzheart.klib</groupId>"
                        + "<artifactId>klib-core</artifactId>"
                        + "<version>" + GradleFixture.KLIB_VERSION + "</version>"
                        + "</dependency></dependencies>");
        publishClassModule(
                "klib-script",
                GradleFixture.KLIB_VERSION,
                "me.kzheart.klib.script.KetherScriptEngine",
                "package me.kzheart.klib.script; public final class KetherScriptEngine { }",
                Collections.singletonList(coreJar),
                "<dependencies><dependency>"
                        + "<groupId>me.kzheart.klib</groupId>"
                        + "<artifactId>klib-core</artifactId>"
                        + "<version>" + GradleFixture.KLIB_VERSION + "</version>"
                        + "</dependency>"
                        + dependency("org.spigotmc", "spigot-api", "1.0.0")
                        + dependency("com.google.code.gson", "gson", "1.0.0")
                        + dependency("org.xerial", "sqlite-jdbc", "1.0.0")
                        + "</dependencies>");
    }

    @Test
    void buildsVerifiedGuardProductWithoutBukkitDescriptorOrParentClasses() throws Exception {
        writeGuardProject(false, false);

        BuildResult result = GradleFixture.build(projectDirectory, "guardProductJar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateGuardEntrypoint").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyGuardProductJar").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":guardProductJar").getOutcome());
        try (ZipFile jar = outputJar()) {
            assertNotNull(jar.getEntry("com/example/CloudExample.class"));
            assertNotNull(jar.getEntry("META-INF/klib-guard/entrypoint"));
            assertNull(jar.getEntry("META-INF/klib-guard/kether-interop.properties"));
            assertNull(jar.getEntry("plugin.yml"));
            assertFalse(jar.stream().anyMatch(entry ->
                    entry.getName().startsWith("me/kzheart/klib/")));
        }
    }

    @Test
    void collectorBoundaryRejectsUserSuppliedPluginYaml() throws Exception {
        writeGuardProject(true, false);
        Path stale = projectDirectory.resolve("build/libs/fixture-1.2.3-guard.jar");
        Files.createDirectories(stale.getParent());
        Files.write(stale, new byte[]{1, 2, 3});

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "check");

        assertTrue(result.getOutput().contains(
                "Bukkit descriptor is forbidden (1): plugin.yml"),
                result.getOutput());
        assertFalse(Files.exists(stale));
    }

    @Test
    void reportsMultipleGuardBoundaryViolationsAndPublishesNothing() throws Exception {
        writeGuardProject(true, false);
        Path library = projectDirectory.resolve("libs/native.jar");
        Files.createDirectories(library.getParent());
        try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(library))) {
            jar.putNextEntry(new ZipEntry("native/linux/libfixture.so"));
            jar.write(new byte[]{1, 2, 3});
            jar.closeEntry();
        }
        Files.write(projectDirectory.resolve("build.gradle.kts"), ("\n"
                + "dependencies { klibEmbedded(files(\"libs/native.jar\")) }\n")
                .getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "guardProductJar");

        assertTrue(result.getOutput().contains("Bukkit descriptor is forbidden"),
                result.getOutput());
        assertTrue(result.getOutput().contains("native library is forbidden"),
                result.getOutput());
        assertFalse(Files.exists(projectDirectory.resolve(
                "build/libs/fixture-1.2.3-guard.jar")));
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

    @Test
    void buildsGuardKetherInteropAsAProductCapability() throws Exception {
        writeGuardProject(false, true);

        BuildResult result = GradleFixture.build(projectDirectory, "guardProductJar");

        assertEquals(TaskOutcome.SUCCESS,
                result.task(":generateGuardKetherInterop").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyGuardProductJar").getOutcome());
        try (ZipFile jar = outputJar()) {
            ZipEntry descriptor = jar.getEntry(
                    "META-INF/klib-guard/kether-interop.properties");
            assertNotNull(descriptor);
            assertEquals(GenerateGuardKetherInteropTask.FORMAT,
                    new String(read(jar, descriptor), StandardCharsets.UTF_8));
            assertNotNull(jar.getEntry(
                    "com/example/libs/klib/script/KetherScriptEngine.class"));
            assertNull(jar.getEntry("plugin.yml"));
            assertFalse(jar.stream().anyMatch(entry ->
                    entry.getName().endsWith("/taboolib/platform/BukkitPlugin.class")));
        }
        String graph = new String(Files.readAllBytes(
                projectDirectory.resolve("build/klib/module-graph.txt")),
                StandardCharsets.UTF_8);
        assertEquals("core\nscript\n", graph);
    }

    @Test
    void rejectsGuardKetherInteropWhenTheGeneratedMarkerIsExcluded() throws Exception {
        writeGuardProject(false, true);
        Files.write(projectDirectory.resolve("build.gradle.kts"), ("\n"
                + "tasks.processResources {\n"
                + "    exclude(\"META-INF/klib-guard/kether-interop.properties\")\n"
                + "}\n").getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "guardProductJar");

        assertTrue(result.getOutput().contains(
                "missing META-INF/klib-guard/kether-interop.properties"), result.getOutput());
    }

    private void writeGuardProject(boolean pluginYaml, boolean ketherInterop) throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "version = \"1.2.3\"\n"
                + repositoryBlock()
                + "klib {\n"
                + "    targetPackage(\"com.example\")\n"
                + "    modules { core() }\n"
                + (ketherInterop ? "    ketherInterop(true)\n" : "")
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

    private static String dependency(String group, String artifact, String version) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact
                + "</artifactId><version>" + version + "</version></dependency>";
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

    private static byte[] read(ZipFile jar, ZipEntry entry) throws Exception {
        try (java.io.InputStream input = jar.getInputStream(entry)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
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
