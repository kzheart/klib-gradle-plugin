package me.kzheart.klib.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class PackagingBoundaryTest {
    @TempDir Path directory;
    @Test void paperRejectsHostBeforeRelocationAndMultiReleaseAndParentRule() throws Exception {
        jar("input.jar", "net/kyori/Hidden.class", clazz("net/kyori/Hidden", null));
        project("packaging { paper() }; relocate(\"net.kyori\", \"hidden\")", "dependencies { klibEmbedded(files(\"input.jar\")) }\n");
        assertTrue(GradleFixture.buildAndFail(directory, "shadowJar").getOutput().contains("Paper host package must not be relocated"));
        project("packaging { paper() }", "dependencies { klibEmbedded(files(\"input.jar\")) }\n");
        assertTrue(GradleFixture.buildAndFail(directory, "check").getOutput().contains("Paper host package must not be embedded"));
        assertNoFinal();
        jar("input.jar", "META-INF/versions/21/org/bukkit/Hidden.class", clazz("org/bukkit/Hidden", null));
        assertTrue(GradleFixture.buildAndFail(directory, "shadowJar").getOutput().contains("Paper host package must not be embedded"));
        project("packaging { paper() }; relocate(\"org\", \"hidden\")", "");
        assertTrue(GradleFixture.buildAndFail(directory, "shadowJar").getOutput().contains("covered by a parent"));
    }
    @Test void genericModeRetainsExistingHostRelocationAndPaperNeverProducesInvalidFinal() throws Exception {
        jar("input.jar", "net/kyori/Hidden.class", clazz("net/kyori/Hidden", null));
        project("", "dependencies { klibEmbedded(files(\"input.jar\")) }\n");
        GradleFixture.build(directory, "shadowJar");
        try (ZipFile zip = new ZipFile(output().toFile())) { assertNotNull(zip.getEntry("example/libs/kyori/Hidden.class")); }
        project("packaging { paper() }; relocate(\"net.kyori\", \"hidden\")", "dependencies { klibEmbedded(files(\"input.jar\")) }\n");
        GradleFixture.buildAndFail(directory, "shadowJar"); assertNoFinal();
        project("packaging { paper() }", "dependencies { klibEmbedded(files(\"input.jar\")) }\n");
        jar("input.jar", "lib/hidden.jar", new byte[] {1});
        assertTrue(GradleFixture.buildAndFail(directory, "shadowJar").getOutput().contains("Nested archives")); assertNoFinal();
    }
    @Test void apiGenericsBodyAndMultiReleaseGatePublicationAndRemovePreviousFinals() throws Exception {
        jar("api.jar", "example/api/Api.class", clazz("example/api/Api", null));
        project("packaging { paper() }; api { archive(layout.projectDirectory.file(\"api.jar\")); packages(\"example.api\") }",
                "apply(plugin=\"maven-publish\")\npublishing { publications { create<MavenPublication>(\"plugin\") { from(components[\"java\"]) } }; repositories { maven { url=uri(\"published\") } } }\n");
        GradleFixture.build(directory, "publish");
        Path apiFinal = directory.resolve("build/libs/fixture-1.0.0-api.jar"); assertTrue(Files.exists(apiFinal));
        Path published = directory.resolve("published/example/fixture/1.0.0/fixture-1.0.0.jar");
        try (ZipFile zip = new ZipFile(published.toFile())) { assertNotNull(zip.getEntry("plugin.yml")); assertNotNull(zip.getEntry("example/libs/kotlin/Unit.class")); }
        jar("api.jar", "example/api/Api.class", clazz("example/api/Api", "generic"));
        assertTrue(GradleFixture.buildAndFail(directory, "publish").getOutput().contains("example/internal/Hidden"));
        assertNoFinal(); assertFalse(Files.exists(apiFinal));
        jar("api.jar", "example/api/Api.class", clazz("example/api/Api", "gson"));
        assertTrue(GradleFixture.buildAndFail(directory, "verifyKlibApiJar").getOutput().contains("com/google/gson/Gson"));
        jar("api.jar", "META-INF/versions/21/example/api/Api.class", clazz("example/api/Api", "body"));
        assertTrue(GradleFixture.buildAndFail(directory, "verifyKlibApiJar").getOutput().contains("example/internal/Hidden"));
    }
    @Test void nativeKotlinInterfaceMetadataAndJarProviderAreAcceptedButIntrinsicsAreNot() throws Exception {
        write("src/main/kotlin/example/api/Api.kt", "package example.api\ninterface Api { fun names(): List<String> }\n");
        project("packaging { paper() }; api { archive(apiJar.flatMap { it.archiveFile }); packages(\"example.api\") }",
                "", "val apiJar = tasks.register<Jar>(\"apiJar\") { from(sourceSets.main.get().output); include(\"example/api/**\", \"META-INF/*.kotlin_module\"); archiveClassifier.set(\"api\") }\n");
        GradleFixture.build(directory, "shadowJar");
        assertTrue(Files.exists(directory.resolve("build/libs/fixture-1.0.0-api.jar")));
        assertTrue(Files.exists(directory.resolve("build/intermediates/klib/api/apiJar/fixture-1.0.0-api.jar")));
        write("src/main/kotlin/example/api/Api.kt", "package example.api\nclass Api { fun echo(value: String): String = value }\n");
        assertTrue(GradleFixture.buildAndFail(directory, "shadowJar").getOutput().contains("kotlin/jvm/internal/Intrinsics"));
        assertNoFinal(); assertFalse(Files.exists(directory.resolve("build/libs/fixture-1.0.0-api.jar")));
    }
    @Test void pluginYamlMustHaveBuildVersionAndExpandedValues() throws Exception {
        project("version(\"2.0.0\")", "");
        assertTrue(GradleFixture.buildAndFail(directory, "shadowJar").getOutput().contains("version must equal project.version")); assertNoFinal();
        project("name(\"@pluginName@\")", "");
        assertTrue(GradleFixture.buildAndFail(directory, "shadowJar").getOutput().contains("unexpanded placeholder")); assertNoFinal();
    }
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "KLIB_TEST_JAVA25_HOME", matches = ".+")
    @Test void userProjectCanTargetJava25WithoutChangingEmbeddedJava8Libraries() throws Exception {
        write("src/main/java/example/Main.java", "package example; public record Main(String value) { }\n");
        write("src/main/kotlin/example/KotlinPart.kt", "package example\ninterface KotlinPart { fun value(): String }\n");
        write("gradle.properties", "org.gradle.java.installations.paths=" + System.getenv("KLIB_TEST_JAVA25_HOME") + "," + System.getProperty("java.home") + "\n");
        project("jvmTarget(25); packaging { paper() }", "java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }\n");
        GradleFixture.build(directory, "shadowJar");
        try (ZipFile zip = new ZipFile(output().toFile())) {
            assertEquals(69, major(zip, "example/Main.class"));
            assertEquals(69, major(zip, "example/KotlinPart.class"));
            assertEquals(52, major(zip, "example/libs/kotlin/Unit.class"));
        }
    }
    private static int major(ZipFile zip, String entry) throws Exception {
        try (java.io.DataInputStream in = new java.io.DataInputStream(zip.getInputStream(zip.getEntry(entry)))) {
            in.readInt(); in.readUnsignedShort(); return in.readUnsignedShort();
        }
    }
    private void project(String dsl, String extra) throws Exception { project(dsl, extra, ""); }
    private void project(String dsl, String extra, String beforeDsl) throws Exception {
        GradleFixture.writeProject(directory, "plugins { id(\"me.kzheart.klib\"); `maven-publish` }\ngroup=\"example\"\nversion=\"1.0.0\"\n" + beforeDsl
                + "klib { main(\"example.Main\"); targetPackage(\"example\"); modules { none() }; " + dsl + " }\n" + extra);
    }
    private Path output() { return directory.resolve("build/libs/fixture-1.0.0-all.jar"); }
    private void assertNoFinal() { assertFalse(Files.exists(output())); }
    private void write(String path, String content) throws Exception { Path file = directory.resolve(path); Files.createDirectories(file.getParent()); Files.write(file, content.getBytes(StandardCharsets.UTF_8)); }
    private void jar(String file, String path, byte[] bytes) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(directory.resolve(file)))) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF")); zip.write("Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry(path)); zip.write(bytes); zip.closeEntry();
        }
    }
    private static byte[] clazz(String name, String leak) {
        ClassWriter w = new ClassWriter(0); w.visit(52, 1, name, null, "java/lang/Object", null);
        if ("gson".equals(leak)) w.visitField(1, "value", "Lcom/google/gson/Gson;", null, null).visitEnd();
        if ("generic".equals(leak)) w.visitField(1, "values", "Ljava/util/List;", "Ljava/util/List<Lexample/internal/Hidden;>;", null).visitEnd();
        if ("body".equals(leak)) { MethodVisitor m = w.visitMethod(9, "call", "()V", null, null); m.visitCode(); m.visitMethodInsn(Opcodes.INVOKESTATIC, "example/internal/Hidden", "call", "()V", false); m.visitInsn(Opcodes.RETURN); m.visitMaxs(0, 0); m.visitEnd(); }
        w.visitEnd(); return w.toByteArray();
    }
}
