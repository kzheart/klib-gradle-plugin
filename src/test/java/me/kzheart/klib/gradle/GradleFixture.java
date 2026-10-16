package me.kzheart.klib.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

final class GradleFixture {
    static final String KLIB_VERSION = projectProperty("klibVersion");

    private GradleFixture() {
    }

    static void writeProject(Path directory, String body) throws IOException {
        Files.write(
                directory.resolve("settings.gradle.kts"),
                "rootProject.name = \"fixture\"\n".getBytes(StandardCharsets.UTF_8));
        Files.write(
                directory.resolve("build.gradle.kts"),
                (body + "\nrepositories { mavenCentral { content { excludeGroup(\"me.kzheart.klib\") } } }\n").getBytes(StandardCharsets.UTF_8));
    }

    static BuildResult build(Path directory, String... arguments) {
        return runner(directory, arguments).build();
    }

    static BuildResult buildAndFail(Path directory, String... arguments) {
        return runner(directory, arguments).buildAndFail();
    }

    private static GradleRunner runner(Path directory, String... arguments) {
        java.util.List<String> complete = new java.util.ArrayList<String>(java.util.Arrays.asList(arguments));
        complete.add("--stacktrace");
        complete.add("--configuration-cache");
        complete.add("--configuration-cache-problems=fail");
        complete.add("--max-workers=2");
        complete.add("-Pkotlin.compiler.execution.strategy=in-process");
        for (String key : java.util.Arrays.asList("http.proxyHost", "http.proxyPort", "https.proxyHost",
                "https.proxyPort", "javax.net.ssl.trustStore")) {
            String value = System.getProperty(key);
            if (value != null) complete.add("-D" + key + "=" + value);
        }
        return GradleRunner.create()
                .withProjectDir(directory.toFile())
                .withPluginClasspath()
                .withArguments(complete);
    }

    private static String projectProperty(String name) {
        Properties properties = new Properties();
        Path file = Paths.get("gradle.properties").toAbsolutePath();
        try (java.io.InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read " + file, failure);
        }
        String value = properties.getProperty(name, "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException(name + " is not set in " + file);
        }
        return value;
    }
}
