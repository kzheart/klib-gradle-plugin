package me.kzheart.klib.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ModuleGraphResolutionTest {
    @TempDir
    Path projectDirectory;

    @Test
    void defaultsToCoreWhenModulesBlockIsAbsent() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n");

        GradleFixture.build(projectDirectory, "klibModuleGraph");

        String graph = new String(
                Files.readAllBytes(projectDirectory.resolve("build/klib/module-graph.txt")),
                StandardCharsets.UTF_8);
        assertEquals("core\n", graph);
    }

    @Test
    void noneClearsTheDefaultCoreSelection() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib { modules { none() } }\n");

        GradleFixture.build(projectDirectory, "klibModuleGraph");

        String graph = new String(
                Files.readAllBytes(projectDirectory.resolve("build/klib/module-graph.txt")),
                StandardCharsets.UTF_8);
        assertEquals("", graph);
    }

    @Test
    void resolvesSelectedModuleDependencyClosure() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib { modules { command() } }\n");

        BuildResult result = GradleFixture.build(projectDirectory, "klibModuleGraph");

        assertEquals(TaskOutcome.SUCCESS, result.task(":klibModuleGraph").getOutcome());
        String graph = new String(
                Files.readAllBytes(projectDirectory.resolve("build/klib/module-graph.txt")),
                StandardCharsets.UTF_8);
        assertEquals("core\nconfig\nlang\ncommand\n", graph);
    }

    @Test
    void repeatedModulesCallsReplaceRatherThanAccumulate() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    modules { command() }\n"
                + "    modules { data() }\n"
                + "}\n");

        BuildResult result = GradleFixture.build(projectDirectory, "klibModuleGraph");

        assertEquals(TaskOutcome.SUCCESS, result.task(":klibModuleGraph").getOutcome());
        String graph = new String(
                Files.readAllBytes(projectDirectory.resolve("build/klib/module-graph.txt")),
                StandardCharsets.UTF_8);
        assertEquals("core\ndata\n", graph);
    }

    @Test
    void resolvesExplicitDataCapabilities() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib { modules { data { json(); sqlite(); mysql() } } }\n");

        GradleFixture.build(projectDirectory, "klibModuleGraph");

        String graph = new String(
                Files.readAllBytes(projectDirectory.resolve("build/klib/module-graph.txt")),
                StandardCharsets.UTF_8);
        assertEquals("core\ndata\ndata-json\ndata-jdbc\ndata-sqlite\ndata-mysql\n", graph);
    }

    @Test
    void itemAndCompatDoNotDragInUnrelatedModules() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib { modules { item(); compat() } }\n");

        BuildResult result = GradleFixture.build(projectDirectory, "klibModuleGraph");

        assertEquals(TaskOutcome.SUCCESS, result.task(":klibModuleGraph").getOutcome());
        String graph = new String(
                Files.readAllBytes(projectDirectory.resolve("build/klib/module-graph.txt")),
                StandardCharsets.UTF_8);
        assertEquals("item\ncompat\n", graph);
    }

    @Test
    void unknownModulesFailKotlinDslCompilation() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib { modules { notAModule() } }\n");

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "klibModuleGraph");

        assertTrue(result.getOutput().contains("notAModule"));
    }

    @Test
    void selectedModulesAreTheOnlyKlibArtifactsIncludedInShadowJar() throws Exception {
        Path repository = Files.createDirectories(projectDirectory.resolve("repository"));
        publishMarkerModule(repository, "core");
        publishMarkerModule(repository, "config");
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "version = \"1.0.0\"\n"
                + "repositories { maven { url = uri(\"" + repository.toUri() + "\") } }\n"
                + "klib {\n"
                + "    main(\"com.example.FixturePlugin\")\n"
                + "    targetPackage(\"com.example.fixture\")\n"
                + "    modules { core() }\n"
                + "}\n");

        GradleFixture.build(projectDirectory, "shadowJar");

        try (ZipFile jar = new ZipFile(projectDirectory.resolve(
                "build/libs/fixture-1.0.0-all.jar").toFile())) {
            assertNotNull(jar.getEntry(
                    "com/example/fixture/libs/klib/core/core.marker"));
            assertNull(jar.getEntry(
                    "com/example/fixture/libs/klib/config/config.marker"));
        }
    }

    @Test
    void packagesArtifactsSelectedByNestedDataDsl() throws Exception {
        Path repository = Files.createDirectories(projectDirectory.resolve("repository"));
        for (String module : Arrays.asList(
                "core", "data", "data-json", "data-jdbc", "data-sqlite", "data-mysql")) {
            publishMarkerModule(repository, module);
        }
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "version = \"1.0.0\"\n"
                + "repositories { maven { url = uri(\"" + repository.toUri() + "\") } }\n"
                + "klib {\n"
                + "    main(\"com.example.FixturePlugin\")\n"
                + "    targetPackage(\"com.example.fixture\")\n"
                + "    modules { data { json(); sqlite(); mysql() } }\n"
                + "}\n");

        GradleFixture.build(projectDirectory, "shadowJar");

        try (ZipFile jar = new ZipFile(projectDirectory.resolve(
                "build/libs/fixture-1.0.0-all.jar").toFile())) {
            for (String module : Arrays.asList(
                    "data", "data-json", "data-jdbc", "data-sqlite", "data-mysql")) {
                assertNotNull(jar.getEntry("com/example/fixture/libs/klib/" + module
                        + "/" + module + ".marker"), module);
            }
        }
    }

    @Test
    void missingSelectedModuleFailsDuringShadowResolution() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    main(\"com.example.FixturePlugin\")\n"
                + "    modules { core() }\n"
                + "}\n");

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "shadowJar");

        assertTrue(result.getOutput().contains(
                "klib-core:" + GradleFixture.KLIB_VERSION));
        assertTrue(result.getOutput().contains("Could not resolve")
                || result.getOutput().contains("Cannot resolve external dependency"));
    }

    @Test
    void explicitLibraryVersionOverridesTheBundledDefault() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    main(\"com.example.FixturePlugin\")\n"
                + "    libraryVersion(\"9.8.7\")\n"
                + "    modules { core() }\n"
                + "}\n");

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "shadowJar");

        assertTrue(result.getOutput().contains("klib-core:9.8.7"));
    }

    @Test
    void rejectsConsumerClassesInsideRelocatedNamespace() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "version = \"1.0.0\"\n"
                + "klib {\n"
                + "    main(\"me.kzheart.klib.consumer.FixturePlugin\")\n"
                + "    targetPackage(\"com.example.fixture\")\n"
                + "    modules { none() }\n"
                + "}\n");
        Path source = projectDirectory.resolve(
                "src/main/java/me/kzheart/klib/consumer/FixturePlugin.java");
        Files.createDirectories(source.getParent());
        Files.write(source, ("package me.kzheart.klib.consumer;\n"
                + "public final class FixturePlugin { }\n").getBytes(StandardCharsets.UTF_8));

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "shadowJar");

        assertTrue(result.getOutput().contains("conflicts with relocated namespace 'me.kzheart.klib'"));
    }

    private static void publishMarkerModule(Path repository, String module) throws Exception {
        Path version = repository.resolve(
                "me/kzheart/klib/klib-" + module + "/" + GradleFixture.KLIB_VERSION);
        Files.createDirectories(version);
        String artifact = "klib-" + module + "-" + GradleFixture.KLIB_VERSION;
        Files.write(version.resolve(artifact + ".pom"), (""
                + "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>me.kzheart.klib</groupId>"
                + "<artifactId>klib-" + module + "</artifactId>"
                + "<version>" + GradleFixture.KLIB_VERSION + "</version></project>")
                .getBytes(StandardCharsets.UTF_8));
        try (ZipOutputStream jar = new ZipOutputStream(
                Files.newOutputStream(version.resolve(artifact + ".jar")))) {
            jar.putNextEntry(new ZipEntry(
                    "me/kzheart/klib/" + module + "/" + module + ".marker"));
            jar.write(module.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
}
