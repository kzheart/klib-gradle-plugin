package me.kzheart.klib.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ExternalLibraryPackagingTest {
    @TempDir Path directory;
    @Test void externalDriverIsDeclaredAndNeitherEmbeddedNorRelocated() throws Exception {
        Path artifact = Files.createDirectories(directory.resolve("repo/org/postgresql/postgresql/42.7.13"));
        Files.write(artifact.resolve("postgresql-42.7.13.pom"), ("<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><version>42.7.13</version></project>").getBytes(StandardCharsets.UTF_8));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(artifact.resolve("postgresql-42.7.13.jar")))) {
            zip.putNextEntry(new ZipEntry("org/postgresql/driver.marker")); zip.write(1); zip.closeEntry();
        }
        Path source = Files.createDirectories(directory.resolve("src/main/java/example"));
        Files.write(source.resolve("Main.java"), "package example; public class Main { public static final String DRIVER=\"org.postgresql.Driver\"; }".getBytes(StandardCharsets.UTF_8));
        GradleFixture.writeProject(directory, "plugins { id(\"me.kzheart.klib\") }\nversion=\"1.0.0\"\n"
                + "repositories { maven { url=uri(\"repo\") } }\n"
                + "klib { main(\"example.Main\"); targetPackage(\"example.libs\"); modules { none() }; "
                + "externalLibrary(\"org.postgresql:postgresql:42.7.13\", \"org.postgresql\") }\n"
                + "dependencies { klibEmbedded(\"org.postgresql:postgresql:42.7.13\") }\n");
        GradleFixture.build(directory, "shadowJar");
        GradleFixture.build(directory, "shadowJar");
        try (ZipFile zip = new ZipFile(directory.resolve("build/libs/fixture-1.0.0-all.jar").toFile())) {
            assertNull(zip.getEntry("org/postgresql/driver.marker"));
            assertNull(zip.getEntry("example/libs/libs/postgresql/driver.marker"));
            String yaml = new String(read(zip, "plugin.yml"), StandardCharsets.UTF_8);
            assertTrue(yaml.contains("libraries:\n  - 'org.postgresql:postgresql:42.7.13'"));
            String bytes = new String(read(zip, "example/Main.class"), StandardCharsets.ISO_8859_1);
            assertTrue(bytes.contains("org.postgresql.Driver"));
            assertFalse(bytes.contains("example.libs.libs.postgresql.Driver"));
        }
    }
    private static byte[] read(ZipFile zip, String name) throws Exception {
        try (InputStream in = zip.getInputStream(zip.getEntry(name)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }
}
