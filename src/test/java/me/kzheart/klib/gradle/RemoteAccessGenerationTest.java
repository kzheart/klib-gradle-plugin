package me.kzheart.klib.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.work.DisableCachingByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteAccessGenerationTest {
    @TempDir
    Path projectDirectory;

    @Test
    void generatesAccessConstantsInTheMainClassPackage() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    main(\"com.example.plugin.FixturePlugin\")\n"
                + "    remote {\n"
                + "        endpoint(\"https://cloud.example.com\")\n"
                + "        publicKey(\"rpk_live_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG\")\n"
                + "        exceptions.set(true)\n"
                + "        logs.set(false)\n"
                + "        manualIncidents.set(true)\n"
                + "    }\n"
                + "}\n");

        BuildResult result = GradleFixture.build(projectDirectory, "generateRemoteAccess");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateRemoteAccess").getOutcome());
        String source = new String(
                Files.readAllBytes(generated("com/example/plugin/KlibRemoteAccess.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.startsWith("package com.example.plugin;\n"), source);
        assertTrue(source.contains(
                "public static final String ENDPOINT = \"https://cloud.example.com\";"), source);
        assertTrue(source.contains(
                "public static final String PUBLIC_KEY = \"rpk_live_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG\";"), source);
        assertTrue(source.contains(
                "public static final boolean EXCEPTIONS_ENABLED = true;"), source);
        assertTrue(source.contains(
                "public static final boolean LOGS_ENABLED = false;"), source);
        assertTrue(source.contains(
                "public static final boolean MANUAL_INCIDENTS_ENABLED = true;"), source);
        assertFalse(source.contains(" TOKEN "), source);
        assertTrue(source.contains("private KlibRemoteAccess() {"), source);
    }

    @Test
    void generatedConstantsCompileIntoTheConsumerJar() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "version = \"1.0.0\"\n"
                + "klib {\n"
                + "    main(\"com.example.plugin.FixturePlugin\")\n"
                + "    modules { none() }\n"
                + "    remote {\n"
                + "        endpoint(\"https://cloud.example.com\")\n"
                + "        publicKey(\"rpk_live_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG\")\n"
                + "    }\n"
                + "}\n");

        BuildResult result = GradleFixture.build(projectDirectory, "compileJava");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertTrue(Files.isRegularFile(projectDirectory.resolve(
                "build/classes/java/main/com/example/plugin/KlibRemoteAccess.class")));
    }

    @Test
    void capabilitiesDefaultToDisabled() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    main(\"com.example.plugin.FixturePlugin\")\n"
                + "    remote {\n"
                + "        endpoint(\"https://cloud.example.com\")\n"
                + "        publicKey(\"rpk_test_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG\")\n"
                + "    }\n"
                + "}\n");

        GradleFixture.build(projectDirectory, "generateRemoteAccess");

        String source = new String(
                Files.readAllBytes(generated("com/example/plugin/KlibRemoteAccess.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains(
                "public static final boolean EXCEPTIONS_ENABLED = false;"), source);
        assertTrue(source.contains(
                "public static final boolean LOGS_ENABLED = false;"), source);
        assertTrue(source.contains(
                "public static final boolean MANUAL_INCIDENTS_ENABLED = false;"), source);
    }

    @Test
    void rejectsUnsafePublicKeyCharacters() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    main(\"com.example.plugin.FixturePlugin\")\n"
                + "    remote {\n"
                + "        endpoint(\"https://cloud.example.com\")\n"
                + "        publicKey(\"rpk_test_0123456789abcde\\\"fghijk\")\n"
                + "    }\n"
                + "}\n");

        BuildResult result = GradleFixture.buildAndFail(
                projectDirectory, "generateRemoteAccess");
        assertTrue(result.getOutput().contains(
                "klib.remote.publicKey must be a public rpk_live_ or rpk_test_ Remote key"),
                result.getOutput());
    }

    @Test
    void generatesNothingWhenRemoteBlockIsAbsent() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib { main(\"com.example.plugin.FixturePlugin\") }\n");

        BuildResult result = GradleFixture.build(projectDirectory, "generateRemoteAccess");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateRemoteAccess").getOutcome());
        assertFalse(Files.exists(generated("com/example/plugin/KlibRemoteAccess.java")));
        try (java.util.stream.Stream<Path> tree =
                     Files.walk(projectDirectory.resolve("build/generated/klib-remote/java"))) {
            assertEquals(0L, tree.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void rejectsNonHttpsEndpointsWithoutEchoingThem() throws Exception {
        assertInsecureEndpointRejected("http://cloud.example.com/endpoint-http-secret");
        assertInsecureEndpointRejected("http://127.0.0.1:8080/endpoint-loopback-secret");
        assertInsecureEndpointRejected("ftp://cloud.example.com/endpoint-ftp-secret");
    }

    private void assertInsecureEndpointRejected(String endpoint) throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    main(\"com.example.plugin.FixturePlugin\")\n"
                + "    remote {\n"
                + "        endpoint(\"" + endpoint + "\")\n"
                + "        publicKey(\"rpk_live_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG\")\n"
                + "    }\n"
                + "}\n");

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "generateRemoteAccess");

        assertTrue(result.getOutput().contains(
                "klib.remote.endpoint must use https"),
                result.getOutput());
        assertFalse(result.getOutput().contains("endpoint-http-secret"), result.getOutput());
        assertFalse(result.getOutput().contains("endpoint-loopback-secret"), result.getOutput());
        assertFalse(result.getOutput().contains("endpoint-ftp-secret"), result.getOutput());
    }

    @Test
    void rejectsEndpointsWithoutHost() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    main(\"com.example.plugin.FixturePlugin\")\n"
                + "    remote {\n"
                + "        endpoint(\"https:///api\")\n"
                + "        publicKey(\"rpk_live_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG\")\n"
                + "    }\n"
                + "}\n");

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "generateRemoteAccess");

        assertTrue(result.getOutput().contains("klib.remote.endpoint must contain a host"),
                result.getOutput());
    }

    @Test
    void rejectsEndpointComponentsThatCouldEmbedSecretsWithoutEchoingThem() throws Exception {
        assertSecretEndpointRejected("https://user:endpoint-userinfo-secret@cloud.example.com");
        assertSecretEndpointRejected("https://cloud.example.com?token=endpoint-query-secret");
        assertSecretEndpointRejected("https://cloud.example.com#endpoint-fragment-secret");
    }

    @Test
    void rejectsBlankPublicKey() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    main(\"com.example.plugin.FixturePlugin\")\n"
                + "    remote {\n"
                + "        endpoint(\"https://cloud.example.com\")\n"
                + "        publicKey(\"   \")\n"
                + "    }\n"
                + "}\n");

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "generateRemoteAccess");

        assertTrue(result.getOutput().contains(
                "klib.remote.publicKey must not be blank"), result.getOutput());
    }

    @Test
    void rejectsSecretDeveloperApiKey() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    main(\"com.example.plugin.FixturePlugin\")\n"
                + "    remote {\n"
                + "        endpoint(\"https://cloud.example.com\")\n"
                + "        publicKey(\"sk_live_secret\")\n"
                + "    }\n"
                + "}\n");

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "generateRemoteAccess");

        assertTrue(result.getOutput().contains(
                "klib.remote.publicKey must be a public rpk_live_ or rpk_test_ Remote key"),
                result.getOutput());
        assertFalse(result.getOutput().contains("sk_live_secret"), result.getOutput());
    }

    @Test
    void rejectsUnknownPublicKeyType() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    main(\"com.example.plugin.FixturePlugin\")\n"
                + "    remote {\n"
                + "        endpoint(\"https://cloud.example.com\")\n"
                + "        publicKey(\"token_fixture\")\n"
                + "    }\n"
                + "}\n");

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "generateRemoteAccess");

        assertTrue(result.getOutput().contains(
                "klib.remote.publicKey must be a public rpk_live_ or rpk_test_ Remote key"),
                result.getOutput());
    }

    @Test
    void rejectsLegacyPluginTokenWithoutEchoingIt() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    main(\"com.example.plugin.FixturePlugin\")\n"
                + "    remote {\n"
                + "        endpoint(\"https://cloud.example.com\")\n"
                + "        publicKey(\"pk_live_legacyvalue\")\n"
                + "    }\n"
                + "}\n");

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "generateRemoteAccess");

        assertTrue(result.getOutput().contains(
                "klib.remote.publicKey must be a public rpk_live_ or rpk_test_ Remote key"),
                result.getOutput());
        assertFalse(result.getOutput().contains("pk_live_legacyvalue"), result.getOutput());
    }

    @Test
    void remoteAccessGenerationIsNotSharedBuildCacheable() {
        assertFalse(GenerateRemoteAccessTask.class.isAnnotationPresent(CacheableTask.class));
        assertTrue(GenerateRemoteAccessTask.class.isAnnotationPresent(DisableCachingByDefault.class));
    }

    @Test
    void removesGeneratedSourcesAfterTheRemoteBlockIsRemoved() throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    main(\"com.example.plugin.FixturePlugin\")\n"
                + "    remote {\n"
                + "        endpoint(\"https://cloud.example.com\")\n"
                + "        publicKey(\"rpk_live_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG\")\n"
                + "    }\n"
                + "}\n");
        GradleFixture.build(projectDirectory, "generateRemoteAccess");
        assertTrue(Files.exists(generated("com/example/plugin/KlibRemoteAccess.java")));

        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib { main(\"com.example.plugin.FixturePlugin\") }\n");
        GradleFixture.build(projectDirectory, "generateRemoteAccess");

        assertFalse(Files.exists(generated("com/example/plugin/KlibRemoteAccess.java")));
    }

    private Path generated(String relative) {
        return projectDirectory.resolve("build/generated/klib-remote/java").resolve(relative);
    }

    private void assertSecretEndpointRejected(String endpoint) throws Exception {
        GradleFixture.writeProject(projectDirectory, ""
                + "plugins { id(\"me.kzheart.klib\") }\n"
                + "klib {\n"
                + "    main(\"com.example.plugin.FixturePlugin\")\n"
                + "    remote {\n"
                + "        endpoint(\"" + endpoint + "\")\n"
                + "        publicKey(\"rpk_live_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG\")\n"
                + "    }\n"
                + "}\n");

        BuildResult result = GradleFixture.buildAndFail(projectDirectory, "generateRemoteAccess");

        assertTrue(result.getOutput().contains("klib.remote.endpoint contains forbidden components"),
                result.getOutput());
        assertFalse(result.getOutput().contains("endpoint-userinfo-secret"), result.getOutput());
        assertFalse(result.getOutput().contains("endpoint-query-secret"), result.getOutput());
        assertFalse(result.getOutput().contains("endpoint-fragment-secret"), result.getOutput());
    }
}
