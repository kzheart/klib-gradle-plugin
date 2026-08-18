package me.kzheart.klib.gradle;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Klib 可发布模块及其直接模块依赖。 */
public enum KlibModule {
    CORE("core"),
    COMPAT("compat"),
    COMPAT_V1_12("compat-v1_12", COMPAT),
    COMPAT_V1_20("compat-v1_20", COMPAT),
    COMPAT_V1_21("compat-v1_21", COMPAT),
    COMPAT_V26("compat-v26", COMPAT),
    CONFIG("config", CORE),
    LANG("lang", CORE, CONFIG),
    COMMAND("command", CORE, LANG),
    ITEM("item"),
    DATA("data", CORE),
    UI("ui", CORE, ITEM),
    SCRIPT("script", CORE),
    HOOK("hook", CORE),
    REMOTE("remote", CORE);

    private final String artifactSuffix;
    private final List<KlibModule> dependencies;

    KlibModule(String artifactSuffix, KlibModule... dependencies) {
        this.artifactSuffix = artifactSuffix;
        this.dependencies = Collections.unmodifiableList(Arrays.asList(dependencies));
    }

    public String artifactSuffix() {
        return artifactSuffix;
    }

    List<KlibModule> dependencies() {
        return dependencies;
    }
}
