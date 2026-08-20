package me.kzheart.klib.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 生成 Guard 商品入口描述文件。 */
@CacheableTask
public abstract class GenerateGuardEntrypointTask extends DefaultTask {
    @Input
    public abstract Property<Boolean> getGuardProduct();

    @Input
    public abstract Property<String> getEntrypoint();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        Path output = getOutputFile().get().getAsFile().toPath();
        try {
            if (!getGuardProduct().get()) {
                Files.deleteIfExists(output);
                return;
            }
            String entrypoint = GuardEntrypointSpec.require(getEntrypoint().getOrElse(""));
            Files.createDirectories(output.getParent());
            Files.write(output, (entrypoint + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new GradleException("Cannot write Guard entrypoint descriptor", failure);
        }
    }
}
