package me.kzheart.klib.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.JavaVersion;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmVersion;
import org.gradle.api.plugins.JavaPluginExtension;
import org.jetbrains.kotlin.gradle.dsl.JvmTarget;
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType;
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.Delete;
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
    static final String KOTLIN_VERSION = "2.4.21";
    private static final String VERSION_RESOURCE = "/META-INF/klib-gradle-plugin.properties";
    private static final Pattern SEMANTIC_VERSION = Pattern.compile(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)");

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        project.getPluginManager().apply("org.jetbrains.kotlin.jvm");
        JavaPluginExtension javaExtension = project.getExtensions().getByType(JavaPluginExtension.class);
        javaExtension.setSourceCompatibility(JavaVersion.VERSION_1_8);
        javaExtension.setTargetCompatibility(JavaVersion.VERSION_1_8);
        KlibExtension extension = project.getExtensions().create(
                "klib", KlibExtension.class, project.getObjects());
        configureDefaults(project, extension);
        project.getTasks().withType(KotlinJvmCompile.class).configureEach(task -> {
            task.getCompilerOptions().getJvmTarget().set(extension.getJvmTarget().map(target ->
                    JvmTarget.valueOf(target == 8 ? "JVM_1_8" : "JVM_" + target)));
            task.getCompilerOptions().getFreeCompilerArgs().add(extension.getJvmTarget().map(target -> "-Xjdk-release=" + target));
        });
        project.getTasks().withType(JavaCompile.class).configureEach(
                task -> task.getOptions().getRelease().set(extension.getJvmTarget()));
        // klib.main 没有合理默认值；保持惰性并统一填空串，让缺失时由任务抛出指向 DSL 的错误，
        // 而不是 Gradle 报 "no value has been specified for property 'mainClass'"。
        Provider<String> mainClass = extension.getMain().orElse("");
        Provider<String> effectiveMainClass = project.provider(() ->
                extension.getGuardProductConfigured().get()
                        ? extension.getGuardProduct().getEntrypoint().getOrElse("")
                        : mainClass.get());

        Configuration embedded = project.getConfigurations().create(
                "klibEmbedded",
                configuration -> {
                    configuration.setCanBeConsumed(false);
                    configuration.setCanBeResolved(false);
                    configuration.setDescription(
                            "Dependencies explicitly embedded in the final Klib JAR.");
                });
        Configuration embeddedClasspath = project.getConfigurations().create(
                "klibEmbeddedClasspath",
                configuration -> {
                    configuration.setCanBeConsumed(false);
                    configuration.setCanBeResolved(true);
                    configuration.setDescription(
                            "Resolved dependencies embedded in the final Klib JAR.");
                    configuration.extendsFrom(embedded);
                    exclude(configuration, "org.xerial", "sqlite-jdbc");
                    runtimeAttributes(project, configuration);
                });
        project.getConfigurations().getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME)
                .extendsFrom(embedded);
        // 选择版本可以参考完整 runtime 图，但只从 embedded 根解析打包闭包。
        Configuration runtimeClasspath = project.getConfigurations().getByName(
                JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
        embeddedClasspath.shouldResolveConsistentlyWith(runtimeClasspath);
        project.getConfigurations().getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME)
                .shouldResolveConsistentlyWith(runtimeClasspath);
        project.getConfigurations().getByName(
                        JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME)
                .extendsFrom(embedded);

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
                    task.getLibraries().set(extension.getExternalLibraries().map(
                            values -> new java.util.ArrayList<String>(new java.util.LinkedHashSet<String>(values.values()))));
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

        TaskProvider<GenerateGuardDataDirectoryTask> guardDataDirectory =
                project.getTasks().register(
                        "generateGuardDataDirectory",
                        GenerateGuardDataDirectoryTask.class,
                        task -> {
                            task.setGroup("klib");
                            task.setDescription(
                                    "Generates the KlibGuard cloud product data directory name.");
                            task.getGuardProduct().set(extension.getGuardProductConfigured());
                            task.getDataDirectory().set(extension.getName());
                            task.getOutputFile().set(project.getLayout().getBuildDirectory().file(
                                    "generated/klib-guard/META-INF/klib-guard/data-directory"));
                        });

        TaskProvider<GenerateGuardKetherInteropTask> guardKetherInterop =
                project.getTasks().register(
                        "generateGuardKetherInterop",
                        GenerateGuardKetherInteropTask.class,
                        task -> {
                            task.setGroup("klib");
                            task.setDescription(
                                    "Generates the KlibGuard Kether interoperability descriptor.");
                            task.getGuardProduct().set(extension.getGuardProductConfigured());
                            task.getInteropEnabled().set(extension.getKetherInterop());
                            task.getOutputFile().set(project.getLayout().getBuildDirectory().file(
                                    "generated/klib-guard/META-INF/klib-guard/"
                                            + "kether-interop.properties"));
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
                .configure(task -> task.dependsOn(
                        pluginYaml, guardEntrypoint, guardDataDirectory, guardKetherInterop));

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
        jar.configure(task -> {
            task.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir(
                    "intermediates/klib/base"));
            task.getArchiveFileName().set("base.jar");
        });
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
        TaskProvider<AnalyzeKlibBundleTask> bundleReport = project.getTasks().register(
                "klibBundleReport",
                AnalyzeKlibBundleTask.class,
                task -> {
                    task.setGroup("klib");
                    task.setDescription("Reports the size of every dependency embedded by Klib.");
                    task.getLibraries().from(embeddedClasspath);
                    task.getHostProvidedDependencies().set(java.util.Arrays.asList(
                            "org.spigotmc:spigot-api",
                            "com.google.code.gson:gson",
                            "org.xerial:sqlite-jdbc"));
                    task.getReportFile().set(project.getLayout().getBuildDirectory().file(
                            "reports/klib/bundle-report.txt"));
                });

        Provider<String> finalArchiveName = project.provider(() -> project.getName() + "-"
                + project.getVersion() + (extension.getGuardProductConfigured().get()
                ? "-guard.jar" : "-all.jar"));
        Provider<org.gradle.api.file.RegularFile> finalArchive =
                project.getLayout().getBuildDirectory().file(
                        finalArchiveName.map(name -> "libs/" + name));
        TaskProvider<Delete> prepareArchive = project.getTasks().register(
                "prepareKlibArchive",
                Delete.class,
                task -> {
                    task.setGroup("build");
                    task.setDescription("Removes the previous final Klib JAR before validation.");
                    task.delete(finalArchive);
                });

        TaskProvider<KlibShadeJarTask> candidateJar = project.getTasks().register(
                "assembleKlibCandidate",
                KlibShadeJarTask.class,
                task -> {
                    task.setGroup("build");
                    task.setDescription("Assembles the relocated Klib JAR candidate.");
                    task.dependsOn(prepareArchive, interopJar, moduleGraph, bundleReport);
                    task.getBaseJar().set(interopJar.flatMap(
                            PrepareKetherInteropJarTask::getArchiveFile));
                    task.getLibraries().from(embeddedClasspath);
                    task.getGuardProduct().set(extension.getGuardProductConfigured());
                    task.getPaper().set(extension.getPackaging().getPaper());
                    task.getHostPackages().set(extension.getPackaging().getHostPackages());
                    task.getProtectedRelocationPrefixes().convention(
                            Collections.<String>emptyList());
                    task.getArchiveFile().set(project.getLayout().getBuildDirectory().file(
                            "intermediates/klib/candidate.jar"));
                });

        TaskProvider<VerifyGuardProductJarTask> verifyGuardProduct =
                project.getTasks().register(
                        "verifyGuardProductJar",
                        VerifyGuardProductJarTask.class,
                        task -> {
                            task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
                            task.setDescription(
                                    "Verifies the KlibGuard cloud product release boundary.");
                            task.dependsOn(candidateJar);
                            task.getGuardProduct().set(extension.getGuardProductConfigured());
                            task.getInteropEnabled().set(extension.getKetherInterop());
                            task.getArchiveFile().set(
                                    candidateJar.flatMap(KlibShadeJarTask::getArchiveFile));
                        });

        TaskProvider<VerifyKlibJarTask> verifyJar = project.getTasks().register(
                "verifyKlibJar", VerifyKlibJarTask.class, task -> {
                    task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
                    task.dependsOn(candidateJar);
                    task.getArchiveFile().set(candidateJar.flatMap(KlibShadeJarTask::getArchiveFile));
                    task.getPaper().set(extension.getPackaging().getPaper());
                    task.getGuardProduct().set(extension.getGuardProductConfigured());
                    task.getHostPackages().set(extension.getPackaging().getHostPackages());
                    task.getBuildVersion().set(project.provider(() -> String.valueOf(project.getVersion())));
                });
        TaskProvider<VerifyKlibApiJarTask> verifyApi = project.getTasks().register(
                "verifyKlibApiJar", VerifyKlibApiJarTask.class, task -> {
                    task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
                    task.dependsOn(prepareArchive);
                    task.getArchives().from(extension.getApi().getArchives());
                    task.getPackages().set(extension.getApi().getPackages());
                    task.getHostPackages().set(extension.getApi().getHostPackages());
                });
        project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME)
                .configure(task -> task.dependsOn(verifyJar, verifyApi, verifyGuardProduct));
        // 无论 publication 选用成品还是自有 API artifact，都必须先验证。
        project.getPlugins().withId("maven-publish", ignored -> {
            project.getTasks().withType(org.gradle.api.publish.maven.tasks.AbstractPublishToMaven.class)
                    .configureEach(task -> task.dependsOn(verifyJar, verifyApi, verifyGuardProduct));
            project.getTasks().withType(org.gradle.api.publish.tasks.GenerateModuleMetadata.class)
                    .configureEach(task -> task.dependsOn(verifyJar, verifyApi, verifyGuardProduct));
        });

        TaskProvider<PromoteKlibJarTask> publishJar = project.getTasks().register(
                "publishKlibJar",
                PromoteKlibJarTask.class,
                task -> {
                    task.setGroup("build");
                    task.setDescription("Publishes a validated Klib JAR to build/libs.");
                    task.dependsOn(verifyGuardProduct, verifyJar, verifyApi);
                    task.getCandidateFile().set(
                            candidateJar.flatMap(KlibShadeJarTask::getArchiveFile));
                    task.getArchiveFile().set(finalArchive);
                });

        project.getPlugins().withId("maven-publish", ignored -> project.getTasks()
                .withType(org.gradle.api.publish.maven.tasks.AbstractPublishToMaven.class)
                .configureEach(task -> task.dependsOn(publishJar)));

        for (String configuration : java.util.Arrays.asList("apiElements", "runtimeElements")) {
            project.getConfigurations().named(configuration, outgoing -> {
                outgoing.getOutgoing().getArtifacts().clear();
                outgoing.getOutgoing().artifact(finalArchive, artifact -> artifact.builtBy(publishJar));
            });
        }

        project.getTasks().register("shadowJar", task -> {
            task.setGroup("build");
            task.setDescription("Assembles and publishes the final Klib JAR.");
            task.dependsOn(publishJar);
        });

        project.getTasks().register("guardProductJar", task -> {
            task.setGroup("build");
            task.setDescription("Assembles and verifies a KlibGuard cloud product JAR.");
            task.dependsOn(publishJar);
            task.onlyIf(ignored -> extension.getGuardProductConfigured().get());
        });
        project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME)
                .configure(task -> task.dependsOn(publishJar));

        project.afterEvaluate(ignored -> {
            if (!extension.getApi().getArchives().isEmpty()) {
                if (extension.getApi().getArchives().getFiles().size() != 1)
                    throw new org.gradle.api.GradleException("klib.api.archive requires one API candidate JAR");
                // 声明的 Jar producer 统一移到候选目录，不能在验证前写入 build/libs。
                for (org.gradle.api.Task producer : extension.getApi().getArchives().getBuildDependencies().getDependencies(null)) {
                    if (producer instanceof Jar) ((Jar) producer).getDestinationDirectory().set(
                            project.getLayout().getBuildDirectory().dir("intermediates/klib/api/" + producer.getName()));
                }
                Provider<org.gradle.api.file.RegularFile> apiArchive = project.getLayout().getBuildDirectory().file(
                        project.provider(() -> "libs/" + project.getName() + "-" + project.getVersion() + "-api.jar"));
                if (extension.getApi().getArchives().getSingleFile().toPath().startsWith(
                        project.getLayout().getBuildDirectory().dir("libs").get().getAsFile().toPath()))
                    throw new org.gradle.api.GradleException("API candidate must be outside build/libs; supply a Jar archiveFile provider");
                prepareArchive.configure(task -> task.delete(apiArchive));
                verifyApi.configure(task -> task.dependsOn(prepareArchive));
                TaskProvider<PromoteKlibJarTask> promoteApi = project.getTasks().register(
                        "publishKlibApiJar", PromoteKlibJarTask.class, task -> {
                            task.setGroup("build");
                            task.dependsOn(verifyApi, verifyJar, verifyGuardProduct);
                            task.getCandidateFile().set(project.getLayout().file(project.provider(
                                    () -> extension.getApi().getArchives().getSingleFile())));
                            task.getArchiveFile().set(apiArchive);
                        });
                publishJar.configure(task -> task.dependsOn(promoteApi));
                project.getPlugins().withId("maven-publish", plugin -> {
                    org.gradle.api.publish.PublishingExtension publishing = project.getExtensions()
                            .getByType(org.gradle.api.publish.PublishingExtension.class);
                    publishing.getPublications().withType(org.gradle.api.publish.maven.MavenPublication.class)
                            .configureEach(publication -> {
                                publication.getArtifacts().removeIf(artifact -> extension.getApi().getArchives().contains(artifact.getFile()));
                                publication.artifact(apiArchive, artifact -> {
                                    artifact.setClassifier("api"); artifact.builtBy(promoteApi);
                                });
                            });
                });
            }
            includeScriptForKetherInterop(extension);
            configureSelection(project, extension, embedded, embeddedClasspath, candidateJar);
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
        extension.getExternalLibraries().convention(Collections.<String, String>emptyMap());
        extension.getLibraryVersion().convention(bundledLibraryVersion());
        extension.getTargetPackage().convention(project.provider(() -> defaultTargetPackage(project)));
        extension.getKetherInterop().convention(false);
        extension.getGuardProduct().getGuardApiVersion().convention(bundledGuardApiVersion());
    }

    private static void runtimeAttributes(Project project, Configuration configuration) {
        configuration.getAttributes()
                .attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME))
                .attribute(Category.CATEGORY_ATTRIBUTE, project.getObjects().named(Category.class, Category.LIBRARY))
                .attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.getObjects().named(LibraryElements.class, LibraryElements.JAR))
                .attribute(Bundling.BUNDLING_ATTRIBUTE, project.getObjects().named(Bundling.class, Bundling.EXTERNAL))
                .attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 8)
                .attribute(KotlinPlatformType.Companion.getAttribute(), KotlinPlatformType.jvm);
    }

    private static void excludeHostProvided(Configuration configuration) {
        exclude(configuration, "org.spigotmc", "spigot-api");
        exclude(configuration, "com.google.code.gson", "gson");
        exclude(configuration, "org.xerial", "sqlite-jdbc");
    }

    private static void exclude(Configuration configuration, String group, String module) {
        Map<String, String> rule = new LinkedHashMap<String, String>();
        rule.put("group", group);
        rule.put("module", module);
        configuration.exclude(rule);
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
            Configuration embedded,
            Configuration embeddedClasspath,
            TaskProvider<KlibShadeJarTask> candidateJar
    ) {
        if (!extension.getPackaging().getPaper().get()) excludeHostProvided(embeddedClasspath);
        ModuleSelection modules = ModuleSelection.resolve(extension.getModules().get());
        String klibVersion = extension.getLibraryVersion().get().trim();
        requireSemanticVersion("klib.libraryVersion", klibVersion);
        boolean guardProduct = extension.getGuardProductConfigured().get();
        if (guardProduct && !extension.getExternalLibraries().get().isEmpty()) {
            throw new org.gradle.api.GradleException("externalLibrary requires a Bukkit/Paper library loader");
        }
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
            candidateJar.configure(task -> task.getGuardBoundaryClasspath().from(
                    project.getConfigurations().getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME)));
            String guardApiVersion = extension.getGuardProduct()
                    .getGuardApiVersion().get().trim();
            requireSemanticVersion("klib.guardProduct.guardApiVersion", guardApiVersion);
            project.getDependencies().add(
                    JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                    "me.kzheart.klib:klib-guard-api:" + guardApiVersion);
            Map<String, String> core = new LinkedHashMap<String, String>();
            core.put("group", "me.kzheart.klib");
            core.put("module", "klib-core");
            embeddedClasspath.exclude(core);
        }
        Configuration paperCompile = null;
        if (!guardProduct && extension.getPackaging().getPaper().get()) {
            paperCompile = project.getConfigurations().create("klibPaperModuleClasspath", configuration -> {
                configuration.setCanBeConsumed(false); configuration.setCanBeResolved(true);
                configuration.setDescription("Unfiltered Klib module runtime closure used only for Paper compilation.");
                runtimeAttributes(project, configuration);
            });
            paperCompile.shouldResolveConsistentlyWith(project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
            project.getDependencies().add(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, project.files(paperCompile));
        }
        for (KlibModule module : modules.resolved()) {
            if (guardProduct && module == KlibModule.CORE) {
                continue;
            }
            Object dependency = guardProduct
                    ? guardModuleDependency(project, module.artifactSuffix(), klibVersion)
                    : moduleDependency(module.artifactSuffix(), klibVersion);
            if (!guardProduct && extension.getPackaging().getPaper().get()) {
                // 编译仍按完整模块 POM 解析；仅内嵌模块根排除宿主依赖。
                project.getDependencies().add(paperCompile.getName(), dependency);
                ExternalModuleDependency bundled = (ExternalModuleDependency) project.getDependencies().create(dependency);
                PaperHostDependencies.excludeFrom(bundled, extension.getPackaging().getHostPackages().get());
                dependency = bundled;
            }
            project.getDependencies().add(
                    embedded.getName(),
                    dependency);
        }
        if (!extension.getExternalLibraries().get().containsKey("kotlin")) {
            project.getDependencies().add(embedded.getName(),
                    "org.jetbrains.kotlin:kotlin-stdlib:" + KOTLIN_VERSION);
        }
        Map<String, String> relocations = new LinkedHashMap<String, String>(plan.relocations());
        if (extension.getPackaging().getPaper().get()) {
            if (guardProduct) throw new org.gradle.api.GradleException("packaging.paper() cannot be combined with guardProduct");
            java.util.List<String> hosts = extension.getPackaging().getHostPackages().get();
            JarBoundary.packages(hosts);
            // 只移除默认宿主映射；用户规则留给 shade 的门禁拒绝，确保先清掉旧正式包。
            relocations.entrySet().removeIf(rule -> !extension.getRelocations().get().containsKey(rule.getKey())
                    && hosts.stream().anyMatch(host -> JarBoundary.within(rule.getKey().replace('.', '/'), host.replace('.', '/'))));
        }

        java.util.List<String> protectedPrefixes = new java.util.ArrayList<String>(plan.protectedPrefixes());
        for (Map.Entry<String, String> library : extension.getExternalLibraries().get().entrySet()) {
            String[] coordinate = library.getValue().split(":");
            exclude(embeddedClasspath, coordinate[0], coordinate[1]);
            ExternalModuleDependency external = (ExternalModuleDependency) project.getDependencies().create(library.getValue());
            external.version(version -> version.strictly(coordinate[2]));
            project.getDependencies().add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, external);
            relocations.entrySet().removeIf(entry -> entry.getKey().equals(library.getKey())
                    || entry.getKey().startsWith(library.getKey() + "."));
            protectedPrefixes.add(library.getKey());
        }
        candidateJar.configure(task -> {
            task.getRelocations().set(relocations);
            task.getProtectedRelocationPrefixes().set(protectedPrefixes);
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
