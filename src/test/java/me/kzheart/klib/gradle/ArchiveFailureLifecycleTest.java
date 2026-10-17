package me.kzheart.klib.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "KLIB_TEST_JAVA25_HOME", matches = ".+")
class ArchiveFailureLifecycleTest {
    @TempDir Path directory;
    @Test void compileAndApiProducerFailuresRemoveOnlyOwnedFinalsAndIncrementalPublishRecovers() throws Exception {
        write("gradle.properties", "org.gradle.java.installations.paths=" + System.getenv("KLIB_TEST_JAVA25_HOME") + "," + System.getProperty("java.home") + "\n");
        sources(); project(false);
        Path unrelated=directory.resolve("build/libs/unrelated-user.jar"); Files.createDirectories(unrelated.getParent()); Files.write(unrelated, new byte[] {42});
        GradleFixture.build(directory, "shadowJar"); assertFinals(true);
        BuildResult incremental=GradleFixture.build(directory, "publish"); assertFinals(true);
        assertEquals(TaskOutcome.UP_TO_DATE, incremental.task(":compileJava").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, incremental.task(":compileKotlin").getOutcome());
        for (String failure : new String[] {"java", "kotlin", "api"}) {
            if (failure.equals("java")) write("src/main/java/example/Main.java", "package example; public class Main { syntax error }\n");
            else if (failure.equals("kotlin")) write("src/main/kotlin/example/KotlinPart.kt", "package example\ninterface KotlinPart { fun value(: String }\n");
            else project(true);
            BuildResult failed=GradleFixture.buildAndFail(directory, failure.equals("kotlin") ? "build" : "shadowJar");
            assertEquals(TaskOutcome.SUCCESS, failed.task(":prepareKlibArchive").getOutcome(), failed.getOutput());
            assertEquals(TaskOutcome.FAILED, failed.task(failure.equals("java") ? ":compileJava" : failure.equals("kotlin") ? ":compileKotlin" : ":apiJar").getOutcome(), failed.getOutput());
            assertFinals(false); assertArrayEquals(new byte[] {42}, Files.readAllBytes(unrelated));
            sources(); project(false); GradleFixture.build(directory, "publish"); assertFinals(true);
        }
        GradleFixture.build(directory, "build", "publish"); assertFinals(true);
        try (ZipFile jar=new ZipFile(directory.resolve("published/example/fixture/1.0.0/fixture-1.0.0.jar").toFile())) { assertNotNull(jar.getEntry("example/Main.class")); }
    }
    private void sources() throws Exception {
        write("src/main/java/example/Main.java", "package example; public class Main { public static String value() { return \"ok\"; } }\n");
        write("src/main/kotlin/example/KotlinPart.kt", "package example\ninterface KotlinPart { fun value(): String }\n");
        write("src/main/java/example/api/Api.java", "package example.api; public interface Api { String value(); }\n");
    }
    private void project(boolean failApi) throws Exception {
        GradleFixture.writeProject(directory, "plugins { id(\"me.kzheart.klib\"); `maven-publish` }\ngroup=\"example\"\nversion=\"1.0.0\"\n"
                + "java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }\n"
                + "val apiJar=tasks.register<Jar>(\"apiJar\") { from(sourceSets.main.get().output); include(\"example/api/**\"); archiveClassifier.set(\"api\"); "
                + (failApi ? "doFirst { throw GradleException(\"API producer failure\") }" : "") + " }\n"
                + "klib { main(\"example.Main\"); jvmTarget(25); modules { none() }; api { archive(apiJar.flatMap { it.archiveFile }); packages(\"example.api\") } }\n"
                + "publishing { publications { create<MavenPublication>(\"plugin\") { from(components[\"java\"]) } }; repositories { maven { url=uri(\"published\") } } }\n");
    }
    private void assertFinals(boolean present) { for(String suffix : new String[] {"all", "api"}) assertEquals(present, Files.exists(directory.resolve("build/libs/fixture-1.0.0-" + suffix + ".jar")), suffix); }
    private void write(String path, String text) throws Exception { Path file=directory.resolve(path); Files.createDirectories(file.getParent()); Files.write(file, text.getBytes(StandardCharsets.UTF_8)); }
}
