package me.kzheart.klib.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.CacheableTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 根据 klib 扩展生成 Bukkit plugin.yml 描述文件。 */
@CacheableTask
public abstract class GeneratePluginYamlTask extends DefaultTask {
    @Input
    public abstract Property<String> getPluginName();

    @Input
    public abstract Property<String> getMainClass();

    @Input
    public abstract Property<String> getPluginVersion();

    @Input
    @Optional
    public abstract Property<String> getApiVersion();

    @Input
    public abstract ListProperty<String> getDepend();

    @Input
    public abstract ListProperty<String> getSoftdepend();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        String name = require("name", getPluginName().get());
        String main = MainClassSpec.require(getMainClass().getOrElse(""));
        String version = require("version", getPluginVersion().get());
        Set<String> hardDependencies = normalized(getDepend().get());
        Set<String> softDependencies = normalized(getSoftdepend().get());
        softDependencies.removeAll(hardDependencies);

        StringBuilder yaml = new StringBuilder();
        scalar(yaml, "name", name);
        scalar(yaml, "main", main);
        scalar(yaml, "version", version);
        String apiVersion = getApiVersion().getOrElse("").trim();
        if (!apiVersion.isEmpty()) {
            scalar(yaml, "api-version", apiVersion);
        }
        sequence(yaml, "depend", hardDependencies);
        sequence(yaml, "softdepend", softDependencies);

        Path output = getOutputFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Files.write(output, yaml.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new GradleException("Cannot write generated plugin.yml", failure);
        }
    }

    private static String require(String field, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new GradleException("klib." + field + " must not be blank");
        }
        return value.trim();
    }

    private static Set<String> normalized(List<String> values) {
        Set<String> result = new LinkedHashSet<String>();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                throw new GradleException("plugin dependency names must not be blank");
            }
            result.add(value.trim());
        }
        return result;
    }

    private static void scalar(StringBuilder yaml, String key, String value) {
        yaml.append(key).append(": '").append(value.replace("'", "''")).append("'\n");
    }

    private static void sequence(StringBuilder yaml, String key, Set<String> values) {
        if (values.isEmpty()) {
            return;
        }
        yaml.append(key).append(":\n");
        for (String value : values) {
            yaml.append("  - '").append(value.replace("'", "''")).append("'\n");
        }
    }
}
