package me.kzheart.klib.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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

class RelocatedServicesTest {
    @TempDir
    Path projectDirectory;

    @Test
    void relocatesAndMergesServiceDescriptorsAndResourcePaths() throws Exception {
        Path libraries = Files.createDirectories(projectDirectory.resolve("libs"));
        fixtureJar(libraries.resolve("one.jar"), "me.kzheart.klib.spi.One");
        fixtureJar(libraries.resolve("two.jar"), "me.kzheart.klib.spi.Two");
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "version = \"1.0.0\"\n"
                + "klib {\n"
                + "    name(\"Fixture\")\n"
                + "    main(\"com.example.FixturePlugin\")\n"
                + "    targetPackage(\"com.example.fixture\")\n"
                + "    modules { none() }\n"
                + "}\n"
                + "dependencies { klibEmbedded(files(\"libs/one.jar\", \"libs/two.jar\")) }\n");

        BuildResult result = GradleFixture.build(projectDirectory, "shadowJar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":shadowJar").getOutcome());
        Path output = projectDirectory.resolve("build/libs/fixture-1.0.0-all.jar");
        try (ZipFile jar = new ZipFile(output.toFile())) {
            String service = "META-INF/services/com.example.fixture.libs.klib.spi.Example";
            ZipEntry descriptor = jar.getEntry(service);
            assertNotNull(descriptor);
            assertEquals(""
                    + "com.example.fixture.libs.klib.spi.One\n"
                    + "com.example.fixture.libs.klib.spi.Two\n", read(jar, descriptor));
            assertNull(jar.getEntry("META-INF/services/me.kzheart.klib.spi.Example"));
            ZipEntry resource = jar.getEntry(
                    "com/example/fixture/libs/klib/config/default.conf");
            assertNotNull(resource);
            assertEquals(
                    "handler=com.example.fixture.libs.klib.spi.One\n",
                    read(jar, resource));
        }
    }

    private static void fixtureJar(Path output, String provider) throws IOException {
        try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(output))) {
            write(
                    jar,
                    "META-INF/services/me.kzheart.klib.spi.Example",
                    provider + "\n");
            write(
                    jar,
                    "me/kzheart/klib/config/default.conf",
                    "handler=" + provider + "\n");
        }
    }

    private static void write(ZipOutputStream jar, String name, String value) throws IOException {
        jar.putNextEntry(new ZipEntry(name));
        jar.write(value.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    private static String read(ZipFile jar, ZipEntry entry) throws IOException {
        try (InputStream input = jar.getInputStream(entry)) {
            byte[] content = new byte[(int) entry.getSize()];
            int offset = 0;
            while (offset < content.length) {
                int count = input.read(content, offset, content.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }
            return new String(content, 0, offset, StandardCharsets.UTF_8);
        }
    }
}
