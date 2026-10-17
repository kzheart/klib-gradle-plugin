package me.kzheart.klib.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "KLIB_TEST_JAVA25_HOME", matches = ".+")
class KotlinApiMappingsTest {
    @TempDir Path directory;
    @Test void currentCompilerAcceptsJdkBuiltinsAndStdlibAliasesButRejectsRealRuntimes() throws Exception {
        Files.write(directory.resolve("gradle.properties"), ("org.gradle.java.installations.paths=" + System.getenv("KLIB_TEST_JAVA25_HOME") + "," + System.getProperty("java.home") + "\n").getBytes(StandardCharsets.UTF_8));
        GradleFixture.writeProject(directory, "plugins { id(\"me.kzheart.klib\") }\nversion=\"1.0.0\"\n"
                + "java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }\n"
                + "val apiJar=tasks.register<Jar>(\"apiJar\") { from(sourceSets.main.get().output); include(\"example/api/**\", \"META-INF/*.kotlin_module\"); archiveClassifier.set(\"api\") }\n"
                + "klib { main(\"example.Main\"); jvmTarget(25); modules { none() }; api { archive(apiJar.flatMap { it.archiveFile }); packages(\"example.api\") } }\n"
                + "dependencies { compileOnly(\"org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2\") }\n");
        source("fun cloneValue(): Cloneable; fun compareValue(): Comparator<String>; fun appendValue(): Appendable; fun randomValue(): kotlin.collections.RandomAccess; fun arrayValue(): Array<String>");
        GradleFixture.build(directory, "verifyKlibApiJar");
        for (String type : new String[] {"Pair<String, String>", "Result<String>", "() -> String", "kotlinx.coroutines.Job"}) {
            source("fun value(): " + type);
            String output=GradleFixture.buildAndFail(directory, "verifyKlibApiJar").getOutput();
            assertTrue(output.contains("API references forbidden"), output);
        }
    }
    private void source(String body) throws Exception {
        Path source = directory.resolve("src/main/kotlin/example/api/Api.kt"); Files.createDirectories(source.getParent());
        Files.write(source, ("package example.api\ninterface Api { " + body + " }\n").getBytes(StandardCharsets.UTF_8));
    }
}
