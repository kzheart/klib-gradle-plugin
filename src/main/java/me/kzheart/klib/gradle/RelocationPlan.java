package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** klib 及其内嵌实现库的确定性命名空间重定位方案。 */
public final class RelocationPlan {
    private static final Pattern JAVA_PACKAGE = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private final ModuleSelection modules;
    private final String targetPackage;
    private final Map<String, String> relocations;
    private final List<String> protectedPrefixes;

    private RelocationPlan(
            ModuleSelection modules,
            String targetPackage,
            Map<String, String> relocations,
            List<String> protectedPrefixes
    ) {
        this.modules = modules;
        this.targetPackage = targetPackage;
        this.relocations = Collections.unmodifiableMap(relocations);
        this.protectedPrefixes = Collections.unmodifiableList(
                new ArrayList<String>(protectedPrefixes));
    }

    public static RelocationPlan resolve(
            ModuleSelection modules,
            String targetPackage,
            Map<String, String> customRelocations
    ) {
        if (modules == null) {
            throw new GradleException("module selection must not be null");
        }
        String target = targetPackage == null ? "" : targetPackage.trim();
        if (!JAVA_PACKAGE.matcher(target).matches()) {
            throw new GradleException("Invalid klib targetPackage: " + targetPackage);
        }
        String libraries = target + ".libs";
        Map<String, String> mappings = new LinkedHashMap<String, String>();
        mappings.put("me.kzheart.klib", libraries + ".klib");
        mappings.put("net.kyori", libraries + ".kyori");
        mappings.put("org.yaml.snakeyaml", libraries + ".snakeyaml");
        mappings.put("de.tr7zw.changeme.nbtapi", libraries + ".nbtapi");
        mappings.put("com.cryptomorin.xseries", libraries + ".xseries");
        mappings.put("org.sqlite", libraries + ".sqlite");
        for (Map.Entry<String, String> relocation : customRelocations.entrySet()) {
            String source = relocation.getKey() == null ? "" : relocation.getKey().trim();
            String suffix = relocation.getValue() == null ? "" : relocation.getValue().trim();
            if (!JAVA_PACKAGE.matcher(source).matches()) {
                throw new GradleException("Invalid relocation source package: " + relocation.getKey());
            }
            if (!JAVA_PACKAGE.matcher(suffix).matches()) {
                throw new GradleException("Invalid relocation target suffix: " + relocation.getValue());
            }
            mappings.put(source, libraries + "." + suffix);
        }
        return new RelocationPlan(
                modules,
                target,
                mappings,
                Collections.<String>emptyList());
    }

    /** Guard/Core 由父加载器提供，云端商品只重定位非 Core 模块与私有依赖。 */
    public static RelocationPlan resolveGuard(
            ModuleSelection modules,
            String targetPackage,
            Map<String, String> customRelocations
    ) {
        if (modules == null) {
            throw new GradleException("module selection must not be null");
        }
        String target = targetPackage == null ? "" : targetPackage.trim();
        if (!JAVA_PACKAGE.matcher(target).matches()) {
            throw new GradleException("Invalid klib targetPackage: " + targetPackage);
        }
        if (overlapsReservedTarget(target)) {
            throw new GradleException("Guard product targetPackage must not overlap a "
                    + "parent-provided namespace: " + targetPackage);
        }
        String libraries = target + ".libs";
        Map<String, String> mappings = new LinkedHashMap<String, String>();
        for (KlibModule module : modules.resolved()) {
            String source = guardModulePackage(module);
            if (source != null) {
                mappings.put(source, libraries + ".klib."
                        + source.substring("me.kzheart.klib.".length()));
            }
        }
        mappings.put("net.kyori", libraries + ".kyori");
        mappings.put("org.yaml.snakeyaml", libraries + ".snakeyaml");
        mappings.put("de.tr7zw.changeme.nbtapi", libraries + ".nbtapi");
        mappings.put("com.cryptomorin.xseries", libraries + ".xseries");
        mappings.put("org.sqlite", libraries + ".sqlite");
        for (Map.Entry<String, String> relocation : customRelocations.entrySet()) {
            String source = relocation.getKey() == null ? "" : relocation.getKey().trim();
            String suffix = relocation.getValue() == null ? "" : relocation.getValue().trim();
            if (!JAVA_PACKAGE.matcher(source).matches()) {
                throw new GradleException("Invalid relocation source package: "
                        + relocation.getKey());
            }
            if (!JAVA_PACKAGE.matcher(suffix).matches()) {
                throw new GradleException("Invalid relocation target suffix: "
                        + relocation.getValue());
            }
            if (overlapsParentProvided(source)) {
                throw new GradleException("Guard products must not relocate parent-provided package: "
                        + source);
            }
            mappings.put(source, libraries + "." + suffix);
        }
        List<String> protectedPrefixes = new ArrayList<String>();
        protectedPrefixes.add("me.kzheart.klib.command.api");
        protectedPrefixes.add("me.kzheart.klib.config.api");
        return new RelocationPlan(modules, target, mappings, protectedPrefixes);
    }

    private static String guardModulePackage(KlibModule module) {
        switch (module) {
            case CORE:
                return null;
            case COMPAT:
            case COMPAT_V1_12:
            case COMPAT_V1_20:
            case COMPAT_V1_21:
            case COMPAT_V26:
                return "me.kzheart.klib.compat";
            case CONFIG:
                return "me.kzheart.klib.config";
            case LANG:
                return "me.kzheart.klib.lang";
            case COMMAND:
                return "me.kzheart.klib.command";
            case ITEM:
                return "me.kzheart.klib.item";
            case DATA:
                return "me.kzheart.klib.data";
            case UI:
                return "me.kzheart.klib.ui";
            case SCRIPT:
                return "me.kzheart.klib.script";
            case HOOK:
                return "me.kzheart.klib.hook";
            case REMOTE:
                return "me.kzheart.klib.remote";
            default:
                throw new GradleException("Unsupported Guard Klib module: " + module);
        }
    }

    private static boolean overlapsParentProvided(String source) {
        return source.equals("me.kzheart.klib")
                || source.startsWith("me.kzheart.klib.")
                || "me.kzheart.klib".startsWith(source + ".")
                || source.equals("org.bukkit")
                || source.startsWith("org.bukkit.")
                || "org.bukkit".startsWith(source + ".");
    }

    private static boolean overlapsReservedTarget(String target) {
        return overlaps("me.kzheart.klib", target) || overlaps("org.bukkit", target);
    }

    private static boolean overlaps(String reserved, String candidate) {
        return reserved.equals(candidate)
                || reserved.startsWith(candidate + ".")
                || candidate.startsWith(reserved + ".");
    }

    public ModuleSelection modules() {
        return modules;
    }

    public String targetPackage() {
        return targetPackage;
    }

    public Map<String, String> relocations() {
        return relocations;
    }

    public List<String> protectedPrefixes() {
        return protectedPrefixes;
    }
}
