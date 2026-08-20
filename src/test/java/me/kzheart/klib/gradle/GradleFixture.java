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
                body.getBytes(StandardCharsets.UTF_8));
    }

    static BuildResult build(Path directory, String... arguments) {
        return runner(directory, arguments).build();
    }

    static BuildResult buildAndFail(Path directory, String... arguments) {
        return runner(directory, arguments).buildAndFail();
    }

    private static GradleRunner runner(Path directory, String... arguments) {
        String[] complete = new String[arguments.length + 3];
        System.arraycopy(arguments, 0, complete, 0, arguments.length);
        complete[arguments.length] = "--stacktrace";
        complete[arguments.length + 1] = "--configuration-cache";
        complete[arguments.length + 2] = "--configuration-cache-problems=fail";
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
