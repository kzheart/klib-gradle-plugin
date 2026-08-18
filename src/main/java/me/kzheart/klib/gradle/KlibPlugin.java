package me.kzheart.klib.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Properties;

/** 用于生成 Bukkit 元数据并构建自包含 klib 发行包的 Gradle 插件入口。 */
public final class KlibPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "me.kzheart.klib";
    private static final String VERSION_RESOURCE = "/META-INF/klib-gradle-plugin.properties";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        project.getTasks().withType(JavaCompile.class).configureEach(
                task -> task.getOptions().getRelease().set(8));
        KlibExtension extension = project.getExtensions().create(
                "klib", KlibExtension.class, project.getObjects());
        configureDefaults(project, extension);
        // klib.main 没有合理默认值；保持惰性并统一填空串，让缺失时由任务抛出指向 DSL 的错误，
        // 而不是 Gradle 报 "no value has been specified for property 'mainClass'"。
        Provider<String> mainClass = extension.getMain().orElse("");

        Configuration runtimeClasspath = project.getConfigurations().getByName(
                JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);

        TaskProvider<GeneratePluginYamlTask> pluginYaml = project.getTasks().register(
                "generatePluginYaml",
                GeneratePluginYamlTask.class,
                task -> {
                    task.setGroup("klib");
                    task.setDescription("Generates Bukkit plugin.yml from the klib DSL.");
                    task.getPluginName().set(extension.getName());
                    task.getMainClass().set(mainClass);
                    task.getPluginVersion().set(extension.getVersion());
                    task.getApiVersion().set(extension.getApiVersion());
                    task.getDepend().set(extension.getDepend());
                    task.getSoftdepend().set(extension.getSoftdepend());
                    task.getOutputFile().set(project.getLayout().getBuildDirectory().file(
                            "generated/klib/plugin.yml"));
                });

        TaskProvider<GenerateRemoteAccessTask> remoteAccess = project.getTasks().register(
                "generateRemoteAccess",
                GenerateRemoteAccessTask.class,
                task -> {
                    task.setGroup("klib");
                    task.setDescription(
                            "Generates the klib-remote endpoint, public key, and capability constants.");
                    task.getRemoteConfigured().set(extension.getRemoteConfigured());
                    task.getEndpoint().set(extension.getRemote().getEndpoint());
                    task.getPublicKey().set(extension.getRemote().getPublicKey());
                    task.getExceptionsEnabled().set(extension.getRemote().getExceptions());
                    task.getLogsEnabled().set(extension.getRemote().getLogs());
                    task.getManualIncidentsEnabled().set(
                            extension.getRemote().getManualIncidents());
                    task.getMainClass().set(mainClass);
                    task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir(
                            "generated/klib-remote/java"));
                });

        project.getExtensions().getByType(SourceSetContainer.class)
                .named("main", sourceSet -> {
                    sourceSet.getResources().srcDir(
                            project.getLayout().getBuildDirectory().dir("generated/klib"));
                    sourceSet.getJava().srcDir(remoteAccess.flatMap(
                            GenerateRemoteAccessTask::getOutputDirectory));
                });
        project.getTasks().named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME)
                .configure(task -> task.dependsOn(pluginYaml));

        TaskProvider<ResolveKlibModulesTask> moduleGraph = project.getTasks().register(
                "klibModuleGraph",
                ResolveKlibModulesTask.class,
                task -> {
                    task.setGroup("klib");
                    task.setDescription("Writes the dependency-complete klib module graph.");
                    task.getModules().set(extension.getModules());
                    task.getOutputFile().set(project.getLayout().getBuildDirectory().file(
                            "klib/module-graph.txt"));
                });

        TaskProvider<Jar> jar = project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class);
        TaskProvider<PrepareKetherInteropJarTask> interopJar = project.getTasks().register(
                "prepareKetherInteropJar",
                PrepareKetherInteropJarTask.class,
                task -> {
                    task.setGroup("build");
                    task.setDescription("Prepares the Bukkit main class for TabooLib Kether "
                            + "OpenContainer interoperability.");
                    task.dependsOn(jar);
                    task.getBaseJar().set(jar.flatMap(Jar::getArchiveFile));
                    task.getInteropEnabled().set(extension.getKetherInterop());
                    task.getMainClass().set(mainClass);
                    task.getTargetPackage().set(extension.getTargetPackage());
                    task.getArchiveFile().set(project.getLayout().getBuildDirectory().file(
                            "intermediates/klib/kether-interop.jar"));
                });
        TaskProvider<KlibShadeJarTask> shadowJar = project.getTasks().register(
                "shadowJar",
                KlibShadeJarTask.class,
                task -> {
                    task.setGroup("build");
                    task.setDescription("Assembles a relocated, self-contained Bukkit plugin jar.");
                    task.dependsOn(interopJar, moduleGraph);
                    task.getBaseJar().set(interopJar.flatMap(
                            PrepareKetherInteropJarTask::getArchiveFile));
                    task.getLibraries().from(runtimeClasspath);
                    task.getArchiveFile().set(project.getLayout().getBuildDirectory().file(
                            project.provider(() -> "libs/" + project.getName() + "-"
                                    + project.getVersion() + "-all.jar")));
                });
        project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME)
                .configure(task -> task.dependsOn(shadowJar));

        project.afterEvaluate(ignored -> {
            includeScriptForKetherInterop(extension);
            configureSelection(project, extension, shadowJar);
        });
    }

    private static void configureDefaults(Project project, KlibExtension extension) {
        extension.getName().convention(project.provider(project::getName));
        extension.getVersion().convention(project.provider(() -> String.valueOf(project.getVersion())));
        extension.getApiVersion().convention("1.13");
        extension.getDepend().convention(Collections.<String>emptyList());
        extension.getSoftdepend().convention(Collections.<String>emptyList());
        extension.getModules().convention(Collections.singletonList(KlibModule.CORE));
        extension.getRelocations().convention(Collections.<String, String>emptyMap());
        extension.getLibraryVersion().convention(bundledLibraryVersion());
        extension.getTargetPackage().convention(project.provider(() -> defaultTargetPackage(project)));
        extension.getKetherInterop().convention(false);
    }

    private static void includeScriptForKetherInterop(KlibExtension extension) {
        if (!extension.getKetherInterop().get()) {
            return;
        }
        for (KlibModule module : extension.getModules().get()) {
            if (module == KlibModule.SCRIPT) {
                return;
            }
        }
        extension.getModules().add(KlibModule.SCRIPT);
    }

    private static void configureSelection(
            Project project,
            KlibExtension extension,
            TaskProvider<KlibShadeJarTask> shadowJar
    ) {
        ModuleSelection modules = ModuleSelection.resolve(extension.getModules().get());
        RelocationPlan plan = RelocationPlan.resolve(
                modules,
                extension.getTargetPackage().get(),
                extension.getRelocations().get());
        String klibVersion = extension.getLibraryVersion().get().trim();
        if (klibVersion.isEmpty()) {
            throw new org.gradle.api.GradleException("klib.libraryVersion must not be blank");
        }
        for (KlibModule module : modules.resolved()) {
            Object dependency = moduleDependency(module.artifactSuffix(), klibVersion);
            project.getDependencies().add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, dependency);
        }
        shadowJar.configure(task -> task.getRelocations().set(plan.relocations()));
    }

    private static Object moduleDependency(String module, String version) {
        return "me.kzheart.klib:klib-" + module + ":" + version;
    }

    private static String bundledLibraryVersion() {
        Properties properties = new Properties();
        try (InputStream input = KlibPlugin.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (input == null) {
                throw new org.gradle.api.GradleException(
                        "Klib Gradle plugin is missing its bundled library version");
            }
            properties.load(input);
        } catch (IOException failure) {
            throw new org.gradle.api.GradleException(
                    "Cannot read the bundled Klib library version", failure);
        }
        String version = properties.getProperty("libraryVersion", "").trim();
        if (version.isEmpty()) {
            throw new org.gradle.api.GradleException(
                    "Klib Gradle plugin has an empty bundled library version");
        }
        return version;
    }

    private static String defaultTargetPackage(Project project) {
        String group = String.valueOf(project.getGroup());
        String suffix = sanitizePackagePart(project.getName());
        if ("unspecified".equals(group) || group.trim().isEmpty()) {
            return "generated." + suffix;
        }
        return group + "." + suffix;
    }

    private static String sanitizePackagePart(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9_$]", "_");
        if (sanitized.isEmpty()) {
            return "plugin";
        }
        if (!Character.isJavaIdentifierStart(sanitized.charAt(0))) {
            return "_" + sanitized;
        }
        return sanitized;
    }
}
