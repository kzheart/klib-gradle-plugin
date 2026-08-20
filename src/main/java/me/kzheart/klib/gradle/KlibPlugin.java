package me.kzheart.klib.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/** 用于生成 Bukkit 元数据并构建自包含 klib 发行包的 Gradle 插件入口。 */
public final class KlibPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "me.kzheart.klib";
    private static final String VERSION_RESOURCE = "/META-INF/klib-gradle-plugin.properties";
    private static final Pattern SEMANTIC_VERSION = Pattern.compile(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)");

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
        Provider<String> effectiveMainClass = project.provider(() ->
                extension.getGuardProductConfigured().get()
                        ? extension.getGuardProduct().getEntrypoint().getOrElse("")
                        : mainClass.get());

        Configuration runtimeClasspath = project.getConfigurations().getByName(
                JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);

        TaskProvider<GeneratePluginYamlTask> pluginYaml = project.getTasks().register(
                "generatePluginYaml",
                GeneratePluginYamlTask.class,
                task -> {
                    task.setGroup("klib");
                    task.setDescription("Generates Bukkit plugin.yml from the klib DSL.");
                    task.getBukkitPlugin().set(
                            extension.getGuardProductConfigured().map(value -> !value));
                    task.getPluginName().set(extension.getName());
                    task.getMainClass().set(mainClass);
                    task.getPluginVersion().set(extension.getVersion());
                    task.getApiVersion().set(extension.getApiVersion());
                    task.getDepend().set(extension.getDepend());
                    task.getSoftdepend().set(extension.getSoftdepend());
                    task.getOutputFile().set(project.getLayout().getBuildDirectory().file(
                            "generated/klib/plugin.yml"));
                });

        TaskProvider<GenerateGuardEntrypointTask> guardEntrypoint = project.getTasks().register(
                "generateGuardEntrypoint",
                GenerateGuardEntrypointTask.class,
                task -> {
                    task.setGroup("klib");
                    task.setDescription("Generates the KlibGuard cloud product entrypoint.");
                    task.getGuardProduct().set(extension.getGuardProductConfigured());
                    task.getEntrypoint().set(
                            extension.getGuardProduct().getEntrypoint().orElse(""));
                    task.getOutputFile().set(project.getLayout().getBuildDirectory().file(
                            "generated/klib-guard/META-INF/klib-guard/entrypoint"));
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
                    task.getMainClass().set(effectiveMainClass);
                    task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir(
                            "generated/klib-remote/java"));
                });

        project.getExtensions().getByType(SourceSetContainer.class)
                .named("main", sourceSet -> {
                    sourceSet.getResources().srcDir(
                            project.getLayout().getBuildDirectory().dir("generated/klib"));
                    sourceSet.getResources().srcDir(
                            project.getLayout().getBuildDirectory().dir("generated/klib-guard"));
                    sourceSet.getJava().srcDir(remoteAccess.flatMap(
                            GenerateRemoteAccessTask::getOutputDirectory));
                });
        project.getTasks().named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME)
                .configure(task -> task.dependsOn(pluginYaml, guardEntrypoint));

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
                    task.getInteropEnabled().set(project.provider(() ->
                            extension.getKetherInterop().get()
                                    && !extension.getGuardProductConfigured().get()));
                    task.getMainClass().set(effectiveMainClass);
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
                    task.getProtectedRelocationPrefixes().convention(
                            Collections.<String>emptyList());
                    task.getArchiveFile().set(project.getLayout().getBuildDirectory().file(
                            project.provider(() -> "libs/" + project.getName() + "-"
                                    + project.getVersion()
                                    + (extension.getGuardProductConfigured().get()
                                    ? "-guard.jar" : "-all.jar"))));
                });

        TaskProvider<VerifyGuardProductJarTask> verifyGuardProduct =
                project.getTasks().register(
                        "verifyGuardProductJar",
                        VerifyGuardProductJarTask.class,
                        task -> {
                            task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
                            task.setDescription(
                                    "Verifies the KlibGuard cloud product release boundary.");
                            task.dependsOn(shadowJar);
                            task.getGuardProduct().set(extension.getGuardProductConfigured());
                            task.getArchiveFile().set(
                                    shadowJar.flatMap(KlibShadeJarTask::getArchiveFile));
                        });

        project.getTasks().register("guardProductJar", task -> {
            task.setGroup("build");
            task.setDescription("Assembles and verifies a KlibGuard cloud product JAR.");
            task.dependsOn(verifyGuardProduct);
            task.onlyIf(ignored -> extension.getGuardProductConfigured().get());
        });
        project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME)
                .configure(task -> task.dependsOn(shadowJar));

        project.afterEvaluate(ignored -> {
            includeScriptForKetherInterop(extension);
            configureSelection(project, extension, shadowJar);
            if (extension.getGuardProductConfigured().get()) {
                project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME)
                        .configure(task -> task.dependsOn(verifyGuardProduct));
                project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME)
                        .configure(task -> task.dependsOn(verifyGuardProduct));
            }
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
        extension.getGuardProduct().getGuardApiVersion().convention(bundledGuardApiVersion());
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
        String klibVersion = extension.getLibraryVersion().get().trim();
        requireSemanticVersion("klib.libraryVersion", klibVersion);
        boolean guardProduct = extension.getGuardProductConfigured().get();
        RelocationPlan plan = guardProduct
                ? RelocationPlan.resolveGuard(
                        modules,
                        extension.getTargetPackage().get(),
                        extension.getRelocations().get())
                : RelocationPlan.resolve(
                        modules,
                        extension.getTargetPackage().get(),
                        extension.getRelocations().get());

        if (guardProduct) {
            if (extension.getKetherInterop().get()) {
                throw new org.gradle.api.GradleException(
                        "klib.ketherInterop is not supported for Guard products");
            }
            String guardApiVersion = extension.getGuardProduct()
                    .getGuardApiVersion().get().trim();
            requireSemanticVersion("klib.guardProduct.guardApiVersion", guardApiVersion);
            project.getDependencies().add(
                    JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                    "me.kzheart.klib:klib-guard-api:" + guardApiVersion);
        }
        for (KlibModule module : modules.resolved()) {
            if (guardProduct && module == KlibModule.CORE) {
                continue;
            }
            Object dependency = guardProduct
                    ? guardModuleDependency(project, module.artifactSuffix(), klibVersion)
                    : moduleDependency(module.artifactSuffix(), klibVersion);
            project.getDependencies().add(
                    JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
                    dependency);
        }
        shadowJar.configure(task -> {
            task.getRelocations().set(plan.relocations());
            task.getProtectedRelocationPrefixes().set(plan.protectedPrefixes());
        });
    }

    private static ExternalModuleDependency guardModuleDependency(
            Project project,
            String module,
            String version
    ) {
        ExternalModuleDependency dependency = (ExternalModuleDependency)
                project.getDependencies().create(moduleDependency(module, version));
        Map<String, String> core = new LinkedHashMap<String, String>();
        core.put("group", "me.kzheart.klib");
        core.put("module", "klib-core");
        dependency.exclude(core);
        return dependency;
    }

    private static Object moduleDependency(String module, String version) {
        return "me.kzheart.klib:klib-" + module + ":" + version;
    }

    private static String bundledLibraryVersion() {
        return bundledVersion("libraryVersion");
    }

    private static String bundledGuardApiVersion() {
        return bundledVersion("guardApiVersion");
    }

    private static String bundledVersion(String property) {
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
        String version = properties.getProperty(property, "").trim();
        if (version.isEmpty()) {
            throw new org.gradle.api.GradleException(
                    "Klib Gradle plugin has an empty bundled " + property);
        }
        return version;
    }

    private static void requireSemanticVersion(String field, String value) {
        if (!SEMANTIC_VERSION.matcher(value).matches()) {
            throw new org.gradle.api.GradleException(
                    field + " must be a three-part semantic version");
        }
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
