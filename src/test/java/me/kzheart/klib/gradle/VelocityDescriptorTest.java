package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class VelocityDescriptorTest {
    @TempDir Path directory;
    private static final String VALID = "{\"id\":\"fixture\",\"main\":\"example.Main\",\"version\":\"1.0.0\","
            + "\"dependencies\":[{\"id\":\"other\",\"optional\":true}],\"authors\":[\"Test\"]}";

    @Test void nativeTasksShadeVerifyAndReuseConfigurationCacheWithoutBukkitOrGuard() throws Exception {
        Files.createDirectories(directory.resolve("resources"));
        Files.createDirectories(directory.resolve("embedded"));
        Files.write(directory.resolve("resources/velocity-plugin.json"), bytes(VALID));
        GradleFixture.writeProject(directory, "plugins { id(\"me.kzheart.klib\") apply false }\n"
                + "version=\"1.0.0\"\n"
                + "val base = tasks.register<Jar>(\"base\") { from(\"resources\"); archiveFileName.set(\"base.jar\"); destinationDirectory.set(layout.buildDirectory.dir(\"base\")) }\n"
                + "val shade = tasks.register<me.kzheart.klib.gradle.KlibShadeJarTask>(\"nativeShade\") {\n"
                + "baseJar.set(base.flatMap { it.archiveFile }); libraries.from(files(\"embedded\")); relocations.set(emptyMap());\n"
                + "protectedRelocationPrefixes.set(emptyList()); guardProduct.set(false); paper.set(false); hostPackages.set(emptyList());\n"
                + "archiveFile.set(layout.buildDirectory.file(\"native/candidate.jar\")) }\n"
                + "tasks.register<me.kzheart.klib.gradle.VerifyKlibJarTask>(\"verifyNative\") {\n"
                + "archiveFile.set(shade.flatMap { it.archiveFile }); velocity.set(true); paper.set(false); guardProduct.set(false);\n"
                + "hostPackages.set(emptyList()); buildVersion.set(project.version.toString()) }\n");
        GradleFixture.build(directory, "verifyNative");
        assertTrue(GradleFixture.build(directory, "verifyNative").getOutput().contains("Reusing configuration cache"));
        Path embedded = directory.resolve("embedded/velocity-plugin.json");
        Files.write(embedded, bytes(VALID));
        assertTrue(GradleFixture.buildAndFail(directory, "verifyNative").getOutput().contains("Embedded library must not contain velocity-plugin.json"));
        Files.delete(embedded);
        Files.write(directory.resolve("resources/velocity-plugin.json"), new byte[] {(byte) 0xc3, 0x28});
        assertTrue(GradleFixture.buildAndFail(directory, "verifyNative").getOutput().contains("velocity-plugin.json must be strict UTF-8"));
        Files.write(directory.resolve("resources/velocity-plugin.json"), bytes(VALID.replace("1.0.0", "2.0.0")));
        assertTrue(GradleFixture.buildAndFail(directory, "verifyNative").getOutput().contains("version must equal project.version"));
    }

    @Test void shadeRejectsDuplicateBaseEntriesAndLibraryJarDescriptors() throws Exception {
        org.gradle.api.Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
        KlibShadeJarTask shade = project.getTasks().register("shade", KlibShadeJarTask.class).get();
        shade.getBaseJar().set(directory.resolve("candidate.jar").toFile());
        shade.getArchiveFile().set(directory.resolve("shaded.jar").toFile());
        shade.getRelocations().set(Collections.emptyMap());
        shade.getProtectedRelocationPrefixes().set(Collections.emptyList());
        shade.getHostPackages().set(Collections.emptyList());
        shade.getPaper().set(false); shade.getGuardProduct().set(false);
        archive("velocity-plugin.json", "velocity-plugin.jsox");
        byte[] duplicate = Files.readAllBytes(directory.resolve("candidate.jar"));
        byte[] alias = bytes("velocity-plugin.jsox");
        for (int i = 0; i <= duplicate.length - alias.length; i++) {
            boolean matches = true;
            for (int j = 0; j < alias.length; j++) if (duplicate[i + j] != alias[j]) { matches = false; break; }
            if (matches) duplicate[i + alias.length - 1] = 'n';
        }
        Files.write(directory.resolve("candidate.jar"), duplicate);
        assertTrue(assertThrows(GradleException.class, shade::shade).getMessage().contains("Duplicate velocity-plugin.json"));
        VerifyKlibJarTask verifier = project.getTasks().register("verify", VerifyKlibJarTask.class).get();
        verifier.getArchiveFile().set(directory.resolve("candidate.jar").toFile());
        verifier.getBuildVersion().set("1.0.0"); verifier.getHostPackages().set(Collections.emptyList());
        verifier.getPaper().set(false); verifier.getGuardProduct().set(false); verifier.getVelocity().set(true);
        assertTrue(assertThrows(GradleException.class, verifier::verify).getMessage().contains("Duplicate JAR entry"));
        archive("velocity-plugin.json");
        Files.copy(directory.resolve("candidate.jar"), directory.resolve("library.jar"));
        shade.getLibraries().from(directory.resolve("library.jar").toFile());
        assertTrue(assertThrows(GradleException.class, shade::shade).getMessage().contains("Embedded library must not contain velocity-plugin.json"));
    }

    @Test void strictJsonRejectsMalformedDuplicateAndUnexpandedOrWrongFields() {
        assertDoesNotThrow(() -> JarBoundary.velocityJson(bytes(VALID), "1.0.0"));
        for (String bad : new String[] {"[]", "null", "{}", VALID + "{}", VALID + " garbage",
                VALID.replace("\"id\":", "id:"), VALID.replace("\"fixture\"", "'fixture'"),
                VALID.replace("\"id\":", "/* comment */\"id\":"), VALID.replace("true", "NaN"),
                VALID.replace("\"version\":\"1.0.0\"", "\"version\":1"),
                VALID.replace("\"version\":\"1.0.0\"", "\"version\":null"),
                VALID.replace("\"main\":\"example.Main\"", "\"main\":\" \""),
                VALID.replace("\"version\":\"1.0.0\"", "\"version\":\"1.0.0\",\"version\":\"1.0.0\""),
                VALID.replace("\"optional\":true", "\"optional\":true,\"optional\":false"),
                VALID.replace("Test", "${name}"), VALID.replace("Test", "@name@"),
                VALID.replace("Test", "\\u0024{name}"), VALID.replace("Test", "\\u0000"),
                VALID.replace("1.0.0", "2.0.0"), VALID.replace("true}", "true,}")}) {
            assertThrows(GradleException.class, () -> JarBoundary.velocityJson(bytes(bad), "1.0.0"), bad);
        }
        assertThrows(GradleException.class, () -> JarBoundary.velocityJson(new byte[] {(byte) 0xc3, 0x28}, "1.0.0"));
    }

    @Test void descriptorsMustBeUniqueRootWithoutAliasesOrMixedPlatforms() throws Exception {
        VerifyKlibJarTask task = verifier(); task.getVelocity().set(true);
        archive(); assertThrows(GradleException.class, task::verify);
        for (String path : new String[] {"VELOCITY-PLUGIN.JSON", "nested/velocity-plugin.json",
                "META-INF/versions/21/velocity-plugin.json", "META-INF/VERSIONS/21/VELOCITY-PLUGIN.JSON"}) {
            archive(path); assertThrows(GradleException.class, task::verify, path);
            archive("velocity-plugin.json", path); assertThrows(GradleException.class, task::verify, path);
        }
        for (String path : new String[] {"plugin.yml", "nested/PLUGIN.YML", "META-INF/versions/21/plugin.yml", "hidden.jar"}) {
            archive("velocity-plugin.json", path); assertThrows(GradleException.class, task::verify, path);
        }
        archive("velocity-plugin.json"); assertDoesNotThrow(task::verify);
        task.getPaper().set(true); assertThrows(GradleException.class, task::verify);
        task.getPaper().set(false); task.getGuardProduct().set(true); assertThrows(GradleException.class, task::verify);
    }

    @Test void defaultBukkitAndGuardStillRequireTheirOwnDescriptorBoundary() throws Exception {
        VerifyKlibJarTask task = verifier(); assertFalse(task.getVelocity().get());
        archive(); assertThrows(GradleException.class, task::verify);
        archive("plugin.yml"); assertDoesNotThrow(task::verify);
        archive("velocity-plugin.json"); assertThrows(GradleException.class, task::verify);
        task.getGuardProduct().set(true);
        archive(); assertDoesNotThrow(task::verify);
        archive("plugin.yml"); assertThrows(GradleException.class, task::verify);
    }
    private VerifyKlibJarTask verifier() {
        VerifyKlibJarTask task = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
                .getTasks().register("verify", VerifyKlibJarTask.class).get();
        task.getArchiveFile().set(directory.resolve("candidate.jar").toFile());
        task.getBuildVersion().set("1.0.0"); task.getHostPackages().set(Collections.emptyList());
        task.getPaper().set(false); task.getGuardProduct().set(false); return task;
    }
    private void archive(String... paths) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(directory.resolve("candidate.jar")))) {
            for (String path : paths) {
                zip.putNextEntry(new ZipEntry(path));
                zip.write(bytes(path.endsWith("plugin.yml") ? "name: Test\nmain: example.Main\nversion: '1.0.0'\n" : VALID));
                zip.closeEntry();
            }
        }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
