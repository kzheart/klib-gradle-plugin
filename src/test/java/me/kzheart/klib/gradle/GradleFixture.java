package me.kzheart.klib.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class GradleFixture {
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
}
