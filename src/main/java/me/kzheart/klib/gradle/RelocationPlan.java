package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** klib 及其内嵌实现库的确定性命名空间重定位方案。 */
public final class RelocationPlan {
    private static final Pattern JAVA_PACKAGE = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private final ModuleSelection modules;
    private final String targetPackage;
    private final Map<String, String> relocations;

    private RelocationPlan(
            ModuleSelection modules,
            String targetPackage,
            Map<String, String> relocations
    ) {
        this.modules = modules;
        this.targetPackage = targetPackage;
        this.relocations = Collections.unmodifiableMap(relocations);
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
        return new RelocationPlan(modules, target, mappings);
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
}
