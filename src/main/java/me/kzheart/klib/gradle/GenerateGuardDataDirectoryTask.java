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

/** 生成 Guard 商品在 {@code plugins/} 下的数据目录名描述文件。 */
@CacheableTask
public abstract class GenerateGuardDataDirectoryTask extends DefaultTask {
    @Input
    public abstract Property<Boolean> getGuardProduct();

    @Input
    public abstract Property<String> getDataDirectory();

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
            String directory = GuardDataDirectorySpec.require(getDataDirectory().getOrElse(""));
            Files.createDirectories(output.getParent());
            Files.write(output, (directory + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new GradleException("Cannot write Guard data-directory descriptor", failure);
        }
    }
}
