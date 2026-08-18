package me.kzheart.klib.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginYamlGenerationTest {
    @TempDir
    Path projectDirectory;

    @Test
    void generatesDeterministicBukkitDescriptorFromKotlinDsl() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "version = \"ignored\"\n"
                + "klib {\n"
                + "    name(\"Fixture's Plugin\")\n"
                + "    main(\"com.example.FixturePlugin\")\n"
                + "    version(\"1.2.3\")\n"
                + "    apiVersion(\"1.13\")\n"
                + "    depend(\"Vault\", \"Vault\")\n"
                + "    softdepend(\"Vault\", \"PlaceholderAPI\")\n"
                + "    targetPackage(\"com.example.fixture\")\n"
                + "    modules { core() }\n"
                + "}\n");

        BuildResult result = GradleFixture.build(projectDirectory, "generatePluginYaml");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generatePluginYaml").getOutcome());
        String yaml = new String(
                Files.readAllBytes(projectDirectory.resolve("build/generated/klib/plugin.yml")),
                StandardCharsets.UTF_8);
        assertEquals(""
                + "name: 'Fixture''s Plugin'\n"
                + "main: 'com.example.FixturePlugin'\n"
                + "version: '1.2.3'\n"
                + "api-version: '1.13'\n"
                + "depend:\n"
                + "  - 'Vault'\n"
                + "softdepend:\n"
                + "  - 'PlaceholderAPI'\n", yaml);
    }

    @Test
    void noApiVersionOmitsTheApiVersionKey() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    name(\"Fixture\")\n"
                + "    main(\"com.example.FixturePlugin\")\n"
                + "    version(\"1.0.0\")\n"
                + "    noApiVersion()\n"
                + "    targetPackage(\"com.example.fixture\")\n"
                + "    modules { core() }\n"
                + "}\n");

        BuildResult result = GradleFixture.build(projectDirectory, "generatePluginYaml");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generatePluginYaml").getOutcome());
        String yaml = new String(
                Files.readAllBytes(projectDirectory.resolve("build/generated/klib/plugin.yml")),
                StandardCharsets.UTF_8);
        assertEquals(""
                + "name: 'Fixture'\n"
                + "main: 'com.example.FixturePlugin'\n"
                + "version: '1.0.0'\n", yaml);
        assertFalse(yaml.contains("api-version"), yaml);
    }

    @Test
    void reportsMissingMainAgainstTheDsl() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    name(\"Fixture\")\n"
                + "    version(\"1.0.0\")\n"
                + "}\n");

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "generatePluginYaml");

        assertTrue(
                result.getOutput().contains("klib.main is not set")
                        && result.getOutput().contains("main(\"com.example.MyPlugin\")"),
                result.getOutput());
        assertFalse(result.getOutput().contains("property 'mainClass'"), result.getOutput());
    }
}
