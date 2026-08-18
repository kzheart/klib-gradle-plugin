package me.kzheart.klib.gradle;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KlibModulesSpecTest {
    @Test
    void exposesACompletionFriendlyMethodForEveryModule() {
        Set<String> expected = new LinkedHashSet<String>(Arrays.asList(
                "core", "compat", "compatV1_12", "compatV1_20", "compatV1_21",
                "compatV26", "config", "lang", "command", "item", "data", "ui",
                "script", "hook", "remote", "none"));
        Set<String> actual = new LinkedHashSet<String>();
        for (Method method : KlibModulesSpec.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && method.getParameterTypes().length == 0) {
                actual.add(method.getName());
            }
        }

        assertEquals(expected, actual);
    }
}
