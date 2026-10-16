package me.kzheart.klib.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 Kotlin 编译器、stdlib、reflect 和协程验证成品，而非伪造 kotlin 包。 */
class NativeKotlinPackagingTest {
    @TempDir Path directory;

    @Test void mixedSourcesUseDefaultsAndRunIsolatedReflectionAndCoroutines() throws Exception {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"\ninclude(\":library\")\n");
        write("library/build.gradle.kts", "plugins { `java-library`; id(\"org.jetbrains.kotlin.jvm\") }\n"
                + "repositories { mavenCentral() }\n"
                + "kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8); moduleName.set(\"fixture_library\") } }\n"
                + "java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }\n");
        write("library/src/main/kotlin/vendor/sample/Model.kt", "package vendor.sample\n"
                + "class Box<T>(val item: T)\n"
                + "fun combine(value: String = \"generic\", action: (String) -> String = { it.uppercase() }) = action(value)\n"
                + "data class Model(val value: String = \"default\", val items: List<String> = listOf(\"one\"))\n"
                + "fun Model.describe(prefix: String = \"native\") = \"$prefix:$value:${items.single()}\"\n");
        write("src/main/java/example/JavaPart.java", "package example; public final class JavaPart {"
                + " public static String decorate(String value) { return value + \":java\"; }"
                + " public static String invokeKotlin() { return KotlinPart.run(); }"
                + " public static void main(String[] args) { String result=invokeKotlin();"
                + " if (!\"native:default:one:java\".equals(result)) throw new AssertionError(result);"
                + " System.out.println(result); } }\n");
        write("src/main/kotlin/example/KotlinPart.kt", "package example\n"
                + "import vendor.sample.Model\nimport vendor.sample.describe\nimport vendor.sample.Box\nimport vendor.sample.combine\n"
                + "import kotlin.reflect.full.primaryConstructor\nimport kotlinx.coroutines.*\n"
                + "object KotlinPart { @JvmStatic fun run(): String {\n"
                + " val model = Model::class.primaryConstructor!!.callBy(emptyMap())\n"
                + " check(Box::class.typeParameters.single().name == \"T\")\n"
                + " check(Box::class.primaryConstructor!!.call(\"boxed\").item == \"boxed\")\n"
                + " check(Model::class.primaryConstructor!!.parameters.last().type.arguments.single().type!!.classifier == String::class)\n"
                + " check(::combine.callBy(emptyMap()) == \"GENERIC\")\n"
                + " check(::combine.parameters.last().type.arguments.size == 2)\n"
                + " check(java.util.ArrayList::class.supertypes.isNotEmpty())\n"
                + " return runBlocking { async { delay(1); JavaPart.decorate(model.describe()) }.await() }\n"
                + "} }\n");
        String build = "plugins { id(\"me.kzheart.klib\") }\nversion=\"1.0.0\"\n"
                + "repositories { mavenCentral() }\n"
                + "klib { main(\"example.KotlinPart\"); targetPackage(\"example.first\"); modules { none() }; "
                + "relocate(\"vendor.sample\", \"sample\") }\n"
                + "dependencies { klibEmbedded(project(\":library\")); "
                + "klibEmbedded(\"org.jetbrains.kotlin:kotlin-reflect:" + KlibPlugin.KOTLIN_VERSION + "\"); "
                + "klibEmbedded(\"org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2\") }\n";
        write("build.gradle.kts", build);
        BuildResult result = GradleFixture.build(directory, "shadowJar");
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        Path first = directory.resolve("first.jar");
        Files.copy(output(), first);
        try (ZipFile jar = new ZipFile(first.toFile())) {
            assertNotNull(jar.getEntry("example/first/libs/kotlin/jvm/internal/Intrinsics.class"));
            assertNotNull(jar.getEntry("example/first/libs/kotlinx/coroutines/Job.class"));
            assertNotNull(jar.getEntry("example/first/libs/sample/Model.class"));
            assertNull(jar.getEntry("kotlin/jvm/internal/Intrinsics.class"));
            assertNull(jar.getEntry("kotlinx/coroutines/Job.class"));
            assertNotNull(jar.getEntry("META-INF/fixture_library.kotlin_module"));
            assertNotNull(jar.getEntry("example/first/libs/kotlin/kotlin.kotlin_builtins"));
            assertNull(jar.getEntry("example/first/libs/kotlin/example.first.libs.kotlin.kotlin_builtins"));
            for (ZipEntry entry : Collections.list(jar.entries())) {
                if (entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/versions/")) {
                    byte[] bytes = read(jar, entry);
                    assertTrue(((bytes[6] & 255) << 8 | bytes[7] & 255) <= 52, entry.getName());
                }
            }
        }
        Path evidence = Files.createDirectories(Paths.get("build/native-kotlin-fixtures"));
        Files.copy(first, evidence.resolve("first-all.jar"), StandardCopyOption.REPLACE_EXISTING);
        String java8Home = System.getenv("KLIB_TEST_JAVA8_HOME");
        if (java8Home != null) {
            Process java8 = new ProcessBuilder(Paths.get(java8Home, "bin", "java").toString(),
                    "-cp", first.toString(), "example.JavaPart").redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            try (InputStream input = java8.getInputStream()) {
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            Files.write(evidence.resolve("java8-runtime.log"), output.toByteArray());
            assertEquals(0, java8.waitFor(), output.toString("UTF-8"));
            assertTrue(output.toString("UTF-8").contains("native:default:one:java"));
        }
        BuildResult cached = GradleFixture.build(directory, "shadowJar");
        assertTrue(cached.getOutput().contains("Reusing configuration cache"), cached.getOutput());
        write("build.gradle.kts", build.replace("example.first", "example.second"));
        GradleFixture.build(directory, "shadowJar");
        Files.copy(output(), evidence.resolve("second-all.jar"), StandardCopyOption.REPLACE_EXISTING);
        try (URLClassLoader firstLoader = new URLClassLoader(new URL[] {first.toUri().toURL()}, null);
             URLClassLoader secondLoader = new URLClassLoader(new URL[] {output().toUri().toURL()}, null)) {
            assertEquals("native:default:one:java", firstLoader.loadClass("example.JavaPart")
                    .getMethod("invokeKotlin").invoke(null));
            assertEquals("native:default:one:java", secondLoader.loadClass("example.JavaPart")
                    .getMethod("invokeKotlin").invoke(null));
            assertNotSame(firstLoader.loadClass("example.first.libs.kotlin.Unit"),
                    secondLoader.loadClass("example.second.libs.kotlin.Unit"));
            assertThrows(ClassNotFoundException.class, () -> firstLoader.loadClass("kotlin.Unit"));
        }
    }

    @Test void externalKotlinRootUsesHostRuntimeWithoutBundlingOrRelocatingIt() throws Exception {
        GradleFixture.writeProject(directory, "plugins { id(\"me.kzheart.klib\") }\nversion=\"1.0.0\"\n"
                + "klib { main(\"example.MainKt\"); targetPackage(\"example\"); modules { none() }; "
                + "externalLibrary(\"org.jetbrains.kotlin:kotlin-stdlib:" + KlibPlugin.KOTLIN_VERSION + "\", \"kotlin\") }\n");
        write("src/main/kotlin/example/Main.kt", "package example\nfun value() = listOf(\"host\").single()\n");
        GradleFixture.build(directory, "shadowJar");
        try (ZipFile jar = new ZipFile(output().toFile())) {
            assertNull(jar.getEntry("kotlin/Unit.class"));
            assertNull(jar.getEntry("example/libs/kotlin/Unit.class"));
            String yaml = new String(read(jar, jar.getEntry("plugin.yml")), StandardCharsets.UTF_8);
            assertTrue(yaml.contains("org.jetbrains.kotlin:kotlin-stdlib:" + KlibPlugin.KOTLIN_VERSION));
            String main = new String(read(jar, jar.getEntry("example/MainKt.class")), StandardCharsets.ISO_8859_1);
            assertTrue(main.contains("kotlin/collections/"));
            assertFalse(main.contains("example/libs/kotlin/"));
        }
    }

    private Path output() { return directory.resolve("build/libs/fixture-1.0.0-all.jar"); }
    private void write(String name, String content) throws Exception {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] read(ZipFile jar, ZipEntry entry) throws Exception {
        try (InputStream input = jar.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
