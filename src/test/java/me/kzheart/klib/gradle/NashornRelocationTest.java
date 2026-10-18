package me.kzheart.klib.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.script.ScriptEngine;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class NashornRelocationTest {
    @TempDir Path directory;

    @Test void concatDescriptorRecipesRelocateWithoutChangingOrdinaryLiterals() throws Exception {
        Path source = directory.resolve("Recipe.java");
        Files.write(source, ("public class Recipe {"
                + "public static String descriptor(String p) { return \"(\" + p + \")Lfixture/Type;\"; }"
                + "public static String literal() { return \"text Lfixture/Type;\"; } }").getBytes(StandardCharsets.UTF_8));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-d", directory.toString(), source.toString()));
        Path type = directory.resolve("Recipe.class");
        byte[] original = Files.readAllBytes(type);
        Files.write(type, ClassRelocator.relocate(original,
                Collections.singletonMap("fixture", "isolated.fixture")));
        try (URLClassLoader loader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, null)) {
            Class<?> recipe = loader.loadClass("Recipe");
            assertEquals("([I)Lisolated/fixture/Type;", recipe.getMethod("descriptor", String.class).invoke(null, "[I"));
            assertEquals("text Lfixture/Type;", recipe.getMethod("literal").invoke(null));
        }
        Files.write(type, ClassRelocator.relocate(original,
                Collections.singletonMap("fixture", "isolated.fixture"), Collections.singletonList("fixture.Type")));
        try (URLClassLoader loader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, null)) {
            assertEquals("([I)Lfixture/Type;", loader.loadClass("Recipe").getMethod("descriptor", String.class).invoke(null, "[I"));
        }
    }

    @Test void finalShadedNashornExecutesArraysAndDynamicRecompilationInIsolation() throws Exception {
        GradleFixture.writeProject(directory, "plugins { id(\"me.kzheart.klib\") }\n"
                + "version = \"1.0.0\"\nrepositories { mavenCentral() }\n"
                + "klib { main(\"example.Fixture\"); targetPackage(\"example\"); jvmTarget(17); "
                + "modules { none() }; relocate(\"org.openjdk.nashorn\", \"nashorn\"); relocate(\"org.objectweb.asm\", \"asm\") }\n"
                + "dependencies { klibEmbedded(\"org.openjdk.nashorn:nashorn-core:15.4\") }\n");
        GradleFixture.build(directory, "shadowJar");
        Path output = directory.resolve("build/libs/fixture-1.0.0-all.jar");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, ScriptEngine.class.getClassLoader())) {
            Class<?> factoryClass = loader.loadClass("example.libs.nashorn.api.scripting.NashornScriptEngineFactory");
            ScriptEngine engine = (ScriptEngine) factoryClass.getMethod("getScriptEngine").invoke(factoryClass.getConstructor().newInstance());
            assertEquals(3, ((Number) engine.eval("[1,2,3].length")).intValue());
            assertEquals("x,y", engine.eval("['x','y'].join(',')"));
            assertEquals(2, ((Number) engine.eval("var f=function(a){return [a,{n:2}];}; f(7)[1].n")).intValue());
            assertEquals("[1,2,3]", engine.eval("var a=[1,2]; a.push(3); JSON.stringify(a)"));
            assertEquals("a:a1,b:b1", engine.eval("function values(x){ return [x, x+1].join(':'); }; values(1); values('a') + ',' + values('b')"));
        }
    }
}
