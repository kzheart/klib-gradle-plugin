package me.kzheart.klib.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class PoolPackagingTest {
    @TempDir Path directory;
    @Test void poolDslResolvesClosureAndIsolatesActualDbcpTransitivesOnlyWhenSelected() throws Exception {
        publish("core", null, ""); publish("data", "core", ""); publish("data-jdbc", "data", "");
        publish("data-pool", "data-jdbc", "<dependency><groupId>org.apache.commons</groupId><artifactId>commons-dbcp2</artifactId><version>2.14.0</version></dependency>");
        project("pool()"); GradleFixture.build(directory, "shadowJar");
        String graph = new String(Files.readAllBytes(directory.resolve("build/klib/module-graph.txt")), StandardCharsets.UTF_8);
        assertEquals("core\ndata\ndata-jdbc\ndata-pool\n", graph);
        Path output = directory.resolve("build/libs/fixture-1.0.0-all.jar");
        try (ZipFile zip = new ZipFile(output.toFile())) {
            for (String name : new String[] {"example/libs/dbcp2/BasicDataSource.class", "example/libs/pool2/impl/GenericObjectPool.class", "example/libs/commonslogging/LogFactory.class"}) assertNotNull(zip.getEntry(name), name);
            assertNull(zip.getEntry("org/apache/commons/dbcp2/BasicDataSource.class"));
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, javax.sql.DataSource.class.getClassLoader())) {
            Object pool = loader.loadClass("example.libs.dbcp2.BasicDataSource").getConstructor().newInstance();
            pool.getClass().getMethod("close").invoke(pool);
        }
        project("jdbc()"); GradleFixture.build(directory, "shadowJar");
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertFalse(zip.stream().anyMatch(e -> e.getName().contains("/dbcp2/") || e.getName().contains("/pool2/") || e.getName().contains("/commonslogging/")));
        }
    }
    private void project(String selection) throws Exception {
        GradleFixture.writeProject(directory, "plugins { id(\"me.kzheart.klib\") }\nversion=\"1.0.0\"\n"
                + "repositories { maven { url=uri(\"repo\") } }\n"
                + "klib { main(\"example.Main\"); targetPackage(\"example\"); modules { data { " + selection + " } } }\n");
    }
    private void publish(String module, String parent, String extra) throws Exception {
        Path target = Files.createDirectories(directory.resolve("repo/me/kzheart/klib/klib-" + module + "/" + GradleFixture.KLIB_VERSION));
        String dep = parent == null ? "" : "<dependency><groupId>me.kzheart.klib</groupId><artifactId>klib-" + parent + "</artifactId><version>" + GradleFixture.KLIB_VERSION + "</version></dependency>";
        Files.write(target.resolve("klib-" + module + "-" + GradleFixture.KLIB_VERSION + ".pom"),
                ("<project><modelVersion>4.0.0</modelVersion><groupId>me.kzheart.klib</groupId><artifactId>klib-" + module + "</artifactId><version>" + GradleFixture.KLIB_VERSION + "</version><dependencies>" + dep + extra + "</dependencies></project>").getBytes(StandardCharsets.UTF_8));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target.resolve("klib-" + module + "-" + GradleFixture.KLIB_VERSION + ".jar")))) {
            ClassWriter writer = new ClassWriter(0); String name = "me/kzheart/klib/fixture/" + module.replace('-', '_'); writer.visit(52, 1, name, null, "java/lang/Object", null); writer.visitEnd();
            zip.putNextEntry(new ZipEntry(name + ".class")); zip.write(writer.toByteArray()); zip.closeEntry();
        }
    }
}
