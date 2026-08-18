package me.kzheart.klib.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.CacheableTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 写出依赖完整的模块选择结果，供诊断与 CI 断言使用。 */
@CacheableTask
public abstract class ResolveKlibModulesTask extends DefaultTask {
    @Input
    public abstract ListProperty<KlibModule> getModules();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void resolveModules() throws IOException {
        List<KlibModule> resolved = KlibModuleGraph.resolve(getModules().get());
        StringBuilder content = new StringBuilder();
        for (KlibModule module : resolved) {
            content.append(module.artifactSuffix()).append('\n');
        }
        Path output = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(output.getParent());
        Files.write(output, content.toString().getBytes(StandardCharsets.UTF_8));
        getLogger().lifecycle("klib modules: {}", resolved);
    }
}
