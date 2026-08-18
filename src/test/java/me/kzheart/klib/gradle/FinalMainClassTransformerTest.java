package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalMainClassTransformerTest {
    @TempDir
    Path directory;

    @Test
    void makesFinalMainExtendableAndGeneratesJava8Subclass() throws Exception {
        byte[] original = compile(
                "com.example.FinalPlugin",
                "package com.example; public final class FinalPlugin { "
                        + "public FinalPlugin() {} public String marker() { return \"ok\"; } }");
        byte[] transformed = PrepareKetherInteropJarTask.makeExtendable(
                original, "com.example.FinalPlugin");
        byte[] generated = PrepareKetherInteropJarTask.generateSubclass(
                "com.example.bridge.taboolib.platform.BukkitPlugin",
                "com.example.FinalPlugin");

        Map<String, byte[]> classes = new LinkedHashMap<String, byte[]>();
        classes.put("com.example.FinalPlugin", transformed);
        classes.put("com.example.bridge.taboolib.platform.BukkitPlugin", generated);
        ClassLoader loader = new BytesClassLoader(classes);
        Class<?> main = loader.loadClass("com.example.FinalPlugin");
        Class<?> bridge = loader.loadClass(
                "com.example.bridge.taboolib.platform.BukkitPlugin");
        Object instance = bridge.getConstructor().newInstance();

        assertFalse(Modifier.isFinal(main.getModifiers()));
        assertTrue(Modifier.isFinal(bridge.getModifiers()));
        assertEquals(main, bridge.getSuperclass());
        assertEquals("ok", main.getMethod("marker").invoke(instance));
        assertEquals(52, classMajorVersion(generated));
    }

    @Test
    void rejectsMainWithoutAccessibleNoArgConstructor() throws Exception {
        byte[] original = compile(
                "com.example.PrivatePlugin",
                "package com.example; public final class PrivatePlugin { "
                        + "private PrivatePlugin() {} }");

        GradleException failure = assertThrows(GradleException.class,
                () -> PrepareKetherInteropJarTask.makeExtendable(
                        original, "com.example.PrivatePlugin"));

        assertTrue(failure.getMessage().contains("public or protected no-arg constructor"));
    }

    @Test
    void rejectsNonPublicMain() throws Exception {
        byte[] original = compile(
                "com.example.PackagePlugin",
                "package com.example; final class PackagePlugin { "
                        + "public PackagePlugin() {} }");

        GradleException failure = assertThrows(GradleException.class,
                () -> PrepareKetherInteropJarTask.makeExtendable(
                        original, "com.example.PackagePlugin"));

        assertTrue(failure.getMessage().contains("must be public"));
    }

    @Test
    void rejectsAbstractMain() throws Exception {
        byte[] original = compile(
                "com.example.AbstractPlugin",
                "package com.example; public abstract class AbstractPlugin { "
                        + "protected AbstractPlugin() {} }");

        GradleException failure = assertThrows(GradleException.class,
                () -> PrepareKetherInteropJarTask.makeExtendable(
                        original, "com.example.AbstractPlugin"));

        assertTrue(failure.getMessage().contains("must be a concrete class"));
    }

    @Test
    void rejectsTargetPackageContainingReservedTabooLibText() {
        GradleException failure = assertThrows(GradleException.class,
                () -> PrepareKetherInteropJarTask.generatedMainClass(
                        "com.example.taboolibplugin.feature"));

        assertTrue(failure.getMessage().contains("reserved text 'taboolib'"));
    }

    private byte[] compile(String className, String source) throws Exception {
        Path sourceRoot = Files.createDirectories(directory.resolve("source"));
        Path output = Files.createDirectories(directory.resolve("classes"));
        Path sourceFile = sourceRoot.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int result = compiler.run(
                null,
                null,
                null,
                "-source", "8",
                "-target", "8",
                "-d", output.toString(),
                sourceFile.toString());
        assertEquals(0, result);
        return Files.readAllBytes(output.resolve(className.replace('.', '/') + ".class"));
    }

    private static int classMajorVersion(byte[] bytes) {
        return (bytes[6] & 0xff) << 8 | bytes[7] & 0xff;
    }

    private static final class BytesClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private BytesClassLoader(Map<String, byte[]> classes) {
            super(FinalMainClassTransformerTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
