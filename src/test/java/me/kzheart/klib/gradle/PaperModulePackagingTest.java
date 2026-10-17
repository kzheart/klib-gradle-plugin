package me.kzheart.klib.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class PaperModulePackagingTest {
    @TempDir Path directory;
    @Test void publishedConfigLangAndUiUseHostRuntimeAndKeepCompilationVisible() throws Exception {
        for (String module : new String[] {"config", "lang", "ui"}) {
            Path fixture = Files.createDirectories(directory.resolve(module));
            String lang = module.equals("config") ? "" : " + net.kyori.adventure.text.Component.text(\"hello\").content()";
            String ui = module.equals("ui") ? " + new me.kzheart.klib.ui.Paginator<String>(java.util.Arrays.asList(\"one\"), 1).pageCount()" : "";
            Path source = fixture.resolve("src/main/java/example/Main.java"); Files.createDirectories(source.getParent());
            Files.write(source, ("package example; public class Main { public static String probe() { return "
                    + "me.kzheart.klib.config.YamlDocument.parse(\"probe\", \"value: ok\").sourceName() + new org.yaml.snakeyaml.Yaml().load(\"host\")"
                    + lang + ui + "; } }").getBytes(StandardCharsets.UTF_8));
            project(fixture, module, "");
            GradleFixture.build(fixture, "shadowJar", "hostClasspath");
            Path jar = fixture.resolve("build/libs/fixture-1.0.0-all.jar");
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                assertNotNull(zip.getEntry("example/libs/klib/config/YamlDocument.class"));
                assertFalse(zip.stream().anyMatch(e -> e.getName().startsWith("org/yaml/") || e.getName().startsWith("net/kyori/")
                        || e.getName().startsWith("example/libs/snakeyaml/") || e.getName().startsWith("example/libs/kyori/")));
            }
            List<URL> hosts = new ArrayList<URL>();
            for (String path : Files.readAllLines(fixture.resolve("build/host-classpath.txt"))) hosts.add(new java.io.File(path).toURI().toURL());
            try (URLClassLoader host = new URLClassLoader(hosts.toArray(new URL[0]), javax.sql.DataSource.class.getClassLoader());
                 URLClassLoader plugin = new URLClassLoader(new URL[] {jar.toUri().toURL()}, host)) {
                assertEquals("probehost" + (module.equals("config") ? "" : "hello") + (module.equals("ui") ? "1" : ""), plugin.loadClass("example.Main").getMethod("probe").invoke(null));
            }
            // 一个显式宿主根不能被模块边排除规则静默吞掉。
            project(fixture, module, "dependencies { klibEmbedded(\"org.yaml:snakeyaml:1.33\") }\n");
            assertTrue(GradleFixture.buildAndFail(fixture, "shadowJar").getOutput().contains("Paper host package must not be embedded"));
            assertFalse(Files.exists(jar));
            if (module.equals("config")) {
                project(fixture, module, "dependencies { klibEmbedded(\"com.google.code.gson:gson:2.11.0\") }\n");
                assertTrue(GradleFixture.buildAndFail(fixture, "shadowJar").getOutput().contains("Paper host package must not be embedded"));
            }
        }
    }
    private static void project(Path fixture, String module, String extra) throws Exception {
        GradleFixture.writeProject(fixture, "plugins { id(\"me.kzheart.klib\") }\nversion=\"1.0.0\"\n"
                + "repositories { mavenCentral { content { includeGroup(\"me.kzheart.klib\") } }; maven(\"https://repo.codemc.io/repository/maven-public/\") { content { includeGroup(\"de.tr7zw\") } } }\n"
                + "klib { main(\"example.Main\"); targetPackage(\"example\"); libraryVersion(\"0.18.0\"); packaging { paper() }; modules { " + module + "() } }\n"
                + "val hostFiles = configurations.compileClasspath.get().filter { it.name.startsWith(\"snakeyaml-\") || it.name.startsWith(\"adventure-\") || it.name.startsWith(\"examination-\") || it.name.startsWith(\"option-\") }\n"
                + "tasks.register(\"hostClasspath\") { val jars=hostFiles; val output=layout.buildDirectory.file(\"host-classpath.txt\"); inputs.files(jars); outputs.file(output); doLast { output.get().asFile.writeText(jars.joinToString(\"\\n\") { it.absolutePath }) } }\n" + extra);
    }
}
