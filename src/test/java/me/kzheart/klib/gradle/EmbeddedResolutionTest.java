package me.kzheart.klib.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddedResolutionTest {
    @TempDir Path directory;

    @Test void alignsEmbeddedTransitiveVersionWithCompileAndRuntimeWithoutAddingRuntimeRoots() throws Exception {
        publish("child", "1.0", "package fixture.dep; public class Child { public static String old() { return \"old\"; } }", "Child", "");
        publish("child", "2.0", "package fixture.dep; public class Child { public static String latest() { return \"new\"; } }", "Child", "");
        publish("root", "1.0", "package fixture.dep; public class Root {}", "Root",
                "<dependencies><dependency><groupId>fixture</groupId><artifactId>child</artifactId><version>1.0</version></dependency></dependencies>");
        publish("unrelated", "1.0", "package fixture.dep; public class Unrelated {}", "Unrelated", "");
        Path source = directory.resolve("src/main/java/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.write(source, ("package example; public class Main { public static String run() { return fixture.dep.Child.latest(); } }")
                .getBytes(StandardCharsets.UTF_8));
        GradleFixture.writeProject(directory, "plugins { id(\"me.kzheart.klib\") }\nversion=\"1.0.0\"\n"
                + "repositories { maven { url=uri(\"repo\") } }\n"
                + "klib { main(\"example.Main\"); targetPackage(\"example\"); modules { none() }; relocate(\"fixture.dep\",\"dep\") }\n"
                + "dependencies { klibEmbedded(\"fixture:root:1.0\"); runtimeOnly(\"fixture:child:2.0\"); runtimeOnly(\"fixture:unrelated:1.0\") }\n");
        GradleFixture.build(directory, "shadowJar");
        Path output = directory.resolve("build/libs/fixture-1.0.0-all.jar");
        try (ZipFile jar = new ZipFile(output.toFile())) {
            assertNotNull(jar.getEntry("example/libs/dep/Root.class"));
            assertNotNull(jar.getEntry("example/libs/dep/Child.class"));
            assertNull(jar.getEntry("example/libs/dep/Unrelated.class"));
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, null)) {
            assertEquals("new", loader.loadClass("example.Main").getMethod("run").invoke(null));
        }
    }

    @Test void preservesRelocatedMultiReleaseClassAndBinaryResources() throws Exception {
        Path classes8 = compile("base", "public static String value() { return \"base\"; }", "8");
        Path classes9 = compile("overlay", "public static String value() { return \"overlay\"; }", "9");
        Path library = directory.resolve("multi.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(library))) {
            add(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            add(zip, "fixture/dep/Versioned.class", Files.readAllBytes(classes8));
            add(zip, "META-INF/versions/9/fixture/dep/Versioned.class", Files.readAllBytes(classes9));
            add(zip, "fixture/dep/data.bin", new byte[] {0, 1, (byte) 255});
        }
        GradleFixture.writeProject(directory, "plugins { id(\"me.kzheart.klib\") }\nversion=\"1.0.0\"\n"
                + "klib { main(\"example.Main\"); targetPackage(\"example\"); modules { none() }; relocate(\"fixture.dep\",\"dep\") }\n"
                + "dependencies { klibEmbedded(files(\"multi.jar\")) }\n");
        GradleFixture.build(directory, "shadowJar");
        Path output = directory.resolve("build/libs/fixture-1.0.0-all.jar");
        try (ZipFile jar = new ZipFile(output.toFile())) {
            assertNotNull(jar.getEntry("META-INF/versions/9/example/libs/dep/Versioned.class"));
            assertNotNull(jar.getEntry("example/libs/dep/data.bin"));
            Manifest manifest = new Manifest(jar.getInputStream(jar.getEntry("META-INF/MANIFEST.MF")));
            assertEquals("true", manifest.getMainAttributes().getValue("Multi-Release"));
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, null)) {
            assertEquals("overlay", loader.loadClass("example.libs.dep.Versioned").getMethod("value").invoke(null));
        }
    }

    private Path compile(String name, String body, String release) throws Exception {
        Path source = directory.resolve(name + "/src/fixture/dep/Versioned.java");
        Files.createDirectories(source.getParent());
        Files.write(source, ("package fixture.dep; public class Versioned { " + body + " }").getBytes(StandardCharsets.UTF_8));
        Path classes = Files.createDirectories(directory.resolve(name + "/classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", release, "-d", classes.toString(), source.toString()));
        return classes.resolve("fixture/dep/Versioned.class");
    }

    private void publish(String artifact, String version, String content, String type, String dependencies) throws Exception {
        Path module = Files.createDirectories(directory.resolve("repo/fixture/" + artifact + "/" + version));
        Files.write(module.resolve(artifact + "-" + version + ".pom"), ("<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>fixture</groupId><artifactId>" + artifact + "</artifactId><version>" + version + "</version>"
                + dependencies + "</project>").getBytes(StandardCharsets.UTF_8));
        Path source = directory.resolve("source-" + artifact + version + "/fixture/dep/" + type + ".java");
        Files.createDirectories(source.getParent());
        Files.write(source, content.getBytes(StandardCharsets.UTF_8));
        Path classes = Files.createDirectories(directory.resolve("classes-" + artifact + version));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "8", "-d", classes.toString(), source.toString()));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(module.resolve(artifact + "-" + version + ".jar")))) {
            add(zip, "fixture/dep/" + type + ".class", Files.readAllBytes(classes.resolve("fixture/dep/" + type + ".class")));
        }
    }
    private static void add(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
}
