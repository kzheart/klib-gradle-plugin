package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class JarBoundaryTest {
    @Test void strictUtf8DescriptorsPlaceholdersVersionsAndDuplicateKeys() {
        assertThrows(GradleException.class, () -> JarBoundary.pluginYaml(new byte[] {(byte) 0xc3, 0x28}, "1.0.0"));
        yamlFails("version: '1.0.0'\nversion: '1.0.0'\n");
        yamlFails("version: '1.0.0'\n'version': '2.0.0'\n");
        yamlFails("version: '${project.version}'\n"); yamlFails("version: '@version@'\n");
        yamlFails("version: '2.0.0'\n");
        assertDoesNotThrow(() -> JarBoundary.pluginYaml("name: 'Test'\nmain: 'example.Main'\nversion: '1.0.0'\n".getBytes(StandardCharsets.UTF_8), "1.0.0"));
    }
    @Test void rawEntryChecksDeclaredClassNameBeforeAnyRelocation() {
        ClassWriter writer = new ClassWriter(0); writer.visit(52, 1, "net/kyori/Host", null, "java/lang/Object", null); writer.visitEnd();
        assertThrows(GradleException.class, () -> JarBoundary.entry("private/preRelocated.class", writer.toByteArray(), Collections.singletonList("net.kyori"), true));
        assertThrows(GradleException.class, () -> JarBoundary.entry("META-INF/versions/25/private/preRelocated.class", writer.toByteArray(), Collections.singletonList("net.kyori"), true));
    }
    private void yamlFails(String content) { assertThrows(GradleException.class, () -> JarBoundary.pluginYaml(("name: 'Test'\nmain: 'example.Main'\n" + content).getBytes(StandardCharsets.UTF_8), "1.0.0")); }
}
