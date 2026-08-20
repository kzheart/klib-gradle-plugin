package me.kzheart.klib.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 验证成功后把候选 JAR 原子发布到 {@code build/libs}。 */
@CacheableTask
public abstract class PromoteKlibJarTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getCandidateFile();

    @OutputFile
    public abstract RegularFileProperty getArchiveFile();

    @TaskAction
    public void promote() {
        Path candidate = getCandidateFile().get().getAsFile().toPath();
        Path output = getArchiveFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Path temporary = Files.createTempFile(
                    output.getParent(), output.getFileName().toString(), ".tmp");
            try {
                Files.copy(candidate, temporary, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temporary, output,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new GradleException("Cannot publish verified Klib JAR " + output, failure);
        }
        getLogger().lifecycle("Published Klib JAR: {}", output);
    }
}
