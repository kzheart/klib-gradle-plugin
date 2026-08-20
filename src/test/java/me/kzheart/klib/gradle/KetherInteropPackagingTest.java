package me.kzheart.klib.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class KetherInteropPackagingTest {
    @TempDir
    Path projectDirectory;

    @Test
    void preparesInteropJarWithoutChangingOrdinaryJar() throws Exception {
        compileMainIntoResources();
        createEmptyKlibLibraries();
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "version = \"1.0.0\"\n"
                + "repositories { flatDir { dirs(\"libs\") } }\n"
                + "klib {\n"
                + "    name(\"Fixture\")\n"
                + "    main(\"com.example.FinalPlugin\")\n"
                + "    targetPackage(\"com.example.fixture\")\n"
                + "    modules { none() }\n"
                + "    ketherInterop(true)\n"
                + "}\n");

        BuildResult result = GradleFixture.build(
                projectDirectory, "shadowJar", "klibModuleGraph");

        assertEquals(TaskOutcome.SUCCESS,
                result.task(":prepareKetherInteropJar").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":shadowJar").getOutcome());
        String generatedMain = "com.example.fixture.libs.klib.script"
                + ".taboolib.platform.BukkitPlugin";
        String generatedPath = generatedMain.replace('.', '/') + ".class";
        String openApiPath = "com/example/fixture/libs/klib/script/taboolib/common/"
                + "OpenAPI.class";
        Path ordinary = projectDirectory.resolve("build/intermediates/klib/base/base.jar");
        try (ZipFile jar = new ZipFile(ordinary.toFile())) {
            assertNull(jar.getEntry(generatedPath));
            assertEquals("com.example.FinalPlugin", yamlMain(jar));
        }
        Path prepared = projectDirectory.resolve(
                "build/intermediates/klib/kether-interop.jar");
        try (ZipFile jar = new ZipFile(prepared.toFile())) {
            assertNotNull(jar.getEntry(generatedPath));
            assertEquals(generatedMain, yamlMain(jar));
        }
        Path shaded = projectDirectory.resolve("build/libs/fixture-1.0.0-all.jar");
        try (ZipFile jar = new ZipFile(shaded.toFile())) {
            assertNotNull(jar.getEntry(generatedPath));
            assertNotNull(jar.getEntry(openApiPath));
            assertEquals(generatedMain, yamlMain(jar));
        }
        String graph = new String(Files.readAllBytes(
                projectDirectory.resolve("build/klib/module-graph.txt")),
                StandardCharsets.UTF_8);
        assertEquals("core\nscript\n", graph);
    }

    private void compileMainIntoResources() throws Exception {
        Path source = projectDirectory.resolve("FinalPlugin.java");
        Files.write(source, ("package com.example; public final class FinalPlugin { "
                + "public FinalPlugin() {} }").getBytes(StandardCharsets.UTF_8));
        Path output = Files.createDirectories(projectDirectory.resolve("src/main/resources"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int result = compiler.run(
                null,
                null,
                null,
                "-source", "8",
                "-target", "8",
                "-d", output.toString(),
                source.toString());
        assertEquals(0, result);
    }

    private void createEmptyKlibLibraries() throws Exception {
        Path libraries = Files.createDirectories(projectDirectory.resolve("libs"));
        emptyJar(libraries.resolve("klib-core-" + GradleFixture.KLIB_VERSION + ".jar"));
        Path source = projectDirectory.resolve("script-source/OpenAPI.java");
        Files.createDirectories(source.getParent());
        Files.write(source, ("package me.kzheart.klib.script.taboolib.common; "
                + "public final class OpenAPI { private OpenAPI() {} }")
                .getBytes(StandardCharsets.UTF_8));
        Path classes = Files.createDirectories(projectDirectory.resolve("script-classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int result = compiler.run(
                null,
                null,
                null,
                "-source", "8",
                "-target", "8",
                "-d", classes.toString(),
                source.toString());
        assertEquals(0, result);
        Path openApi = classes.resolve(
                "me/kzheart/klib/script/taboolib/common/OpenAPI.class");
        Path scriptJar = libraries.resolve(
                "klib-script-" + GradleFixture.KLIB_VERSION + ".jar");
        try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(scriptJar))) {
            jar.putNextEntry(new ZipEntry(
                    "me/kzheart/klib/script/taboolib/common/OpenAPI.class"));
            jar.write(Files.readAllBytes(openApi));
            jar.closeEntry();
        }
    }

    private static void emptyJar(Path output) throws Exception {
        try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(output))) {
            jar.finish();
        }
    }

    private static String yamlMain(ZipFile jar) throws Exception {
        ZipEntry entry = jar.getEntry("plugin.yml");
        assertNotNull(entry);
        String yaml;
        try (InputStream input = jar.getInputStream(entry)) {
            byte[] bytes = new byte[(int) entry.getSize()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }
            yaml = new String(bytes, 0, offset, StandardCharsets.UTF_8);
        }
        for (String line : yaml.split("\\n")) {
            if (line.startsWith("main:")) {
                return line.substring(line.indexOf(':') + 1).trim().replace("'", "");
            }
        }
        return null;
    }
}
