package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardRelocationPlanTest {
    @Test
    void relocatesNonCoreModulesButPreservesCoreApiPackages() {
        RelocationPlan plan = RelocationPlan.resolveGuard(
                ModuleSelection.resolve(Arrays.asList(KlibModule.CONFIG, KlibModule.COMMAND)),
                "com.example.product",
                Collections.<String, String>emptyMap());

        assertFalse(plan.relocations().containsKey("me.kzheart.klib"));
        assertEquals(
                "com.example.product.libs.klib.config",
                plan.relocations().get("me.kzheart.klib.config"));
        assertEquals(
                "com.example.product.libs.klib.command",
                plan.relocations().get("me.kzheart.klib.command"));

        String descriptor = "Lme/kzheart/klib/config/api/ConfigDocument;"
                + "Lme/kzheart/klib/config/ConfigModule;"
                + "Lme/kzheart/klib/command/api/CommandSpec;"
                + "Lme/kzheart/klib/command/CommandModule;";
        String relocated = ClassRelocator.replace(
                descriptor,
                plan.relocations(),
                plan.protectedPrefixes());

        assertTrue(relocated.contains("me/kzheart/klib/config/api/ConfigDocument"));
        assertTrue(relocated.contains("me/kzheart/klib/command/api/CommandSpec"));
        assertTrue(relocated.contains("com/example/product/libs/klib/config/ConfigModule"));
        assertTrue(relocated.contains("com/example/product/libs/klib/command/CommandModule"));
    }

    @Test
    void rejectsParentProvidedTargetsAndCustomRelocations() {
        ModuleSelection core = ModuleSelection.resolve(
                Collections.singletonList(KlibModule.CORE));

        assertThrows(GradleException.class, () -> RelocationPlan.resolveGuard(
                core,
                "me.kzheart.klib.product",
                Collections.<String, String>emptyMap()));
        assertThrows(GradleException.class, () -> RelocationPlan.resolveGuard(
                core,
                "com.example.product",
                Collections.singletonMap("org.bukkit", "bukkit")));
    }
}
