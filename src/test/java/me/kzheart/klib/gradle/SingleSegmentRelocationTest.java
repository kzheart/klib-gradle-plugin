package me.kzheart.klib.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SingleSegmentRelocationTest {
    @TempDir Path directory;
    private final Map<String, String> rules = Collections.singletonMap("kotlin", "example.libs.kotlin");

    @Test void distinguishesJvmPathsFromDottedNamesWithoutRelocatingTheResultAgain() {
        assertEquals("example/libs/kotlin/jvm/internal/Intrinsics.class",
                ClassRelocator.replace("kotlin/jvm/internal/Intrinsics.class", rules));
        assertEquals("Lexample/libs/kotlin/Function;", ClassRelocator.replace("Lkotlin/Function;", rules));
        assertEquals("example.libs.kotlin.Metadata", ClassRelocator.replace("kotlin.Metadata", rules));
        assertEquals("example.libs.kotlin", ClassRelocator.replace("kotlin", rules));
        assertEquals("META-INF/kotlin-stdlib.kotlin_module",
                ClassRelocator.replace("META-INF/kotlin-stdlib.kotlin_module", rules));
        assertEquals("kotlinx/coroutines/Job", ClassRelocator.replace("kotlinx/coroutines/Job", rules));
    }

    @Test void preservesProtectedSubpackagesInBothNameForms() {
        assertEquals("Lkotlin/shared/Api;Lexample/libs/kotlin/privateapi/Impl;",
                ClassRelocator.replace("Lkotlin/shared/Api;Lkotlin/privateapi/Impl;", rules,
                        Collections.singletonList("kotlin.shared")));
        assertEquals("kotlin.shared.Api", ClassRelocator.replace("kotlin.shared.Api", rules,
                Collections.singletonList("kotlin.shared")));
    }

    @Test void packagedClassesLoadAndInvokeTheRelocatedDependency() throws Exception {
        Path source = directory.resolve("dependency/kotlin/jvm/internal/Intrinsics.java");
        Files.createDirectories(source.getParent());
        Files.write(source, ("package kotlin.jvm.internal; public final class Intrinsics { "
                + "public static String value() { return \"OK\"; } }").getBytes(StandardCharsets.UTF_8));
        Path classes = Files.createDirectories(directory.resolve("dependency-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "8", "-d", classes.toString(), source.toString()));
        Path libraries = Files.createDirectories(directory.resolve("libs"));
        try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(libraries.resolve("runtime.jar")))) {
            jar.putNextEntry(new ZipEntry("kotlin/jvm/internal/Intrinsics.class"));
            jar.write(Files.readAllBytes(classes.resolve("kotlin/jvm/internal/Intrinsics.class")));
            jar.closeEntry();
        }
        Path application = directory.resolve("src/main/java/example/FixturePlugin.java");
        Files.createDirectories(application.getParent());
        Files.write(application, ("package example; public class FixturePlugin { "
                + "public static String run() { return kotlin.jvm.internal.Intrinsics.value(); } }")
                .getBytes(StandardCharsets.UTF_8));
        GradleFixture.writeProject(directory, "plugins { id(\"me.kzheart.klib\") }\n"
                + "version = \"1.0.0\"\n"
                + "klib { main(\"example.FixturePlugin\"); targetPackage(\"example\"); "
                + "modules { none() }; relocate(\"kotlin\", \"kotlin\") }\n"
                + "dependencies { klibEmbedded(files(\"libs/runtime.jar\")) }\n");
        GradleFixture.build(directory, "shadowJar");
        Path output = directory.resolve("build/libs/fixture-1.0.0-all.jar");
        try (ZipFile jar = new ZipFile(output.toFile())) {
            assertNotNull(jar.getEntry("example/libs/kotlin/jvm/internal/Intrinsics.class"));
            assertNull(jar.getEntry("kotlin/jvm/internal/Intrinsics.class"));
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, null)) {
            assertEquals("OK", loader.loadClass("example.FixturePlugin").getMethod("run").invoke(null));
        }
    }
}
