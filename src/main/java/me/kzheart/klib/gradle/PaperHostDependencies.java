package me.kzheart.klib.gradle;

import org.gradle.api.artifacts.ExternalModuleDependency;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仅排除 Klib 模块依赖边上的已知宿主制品，不过滤用户输入。 */
final class PaperHostDependencies {
    private PaperHostDependencies() { }
    static void excludeFrom(ExternalModuleDependency dependency, List<String> hosts) {
        exclude(dependency, hosts, "org.bukkit", "org.spigotmc", "spigot-api");
        exclude(dependency, hosts, "io.papermc", "io.papermc.paper", "paper-api");
        exclude(dependency, hosts, "com.google.gson", "com.google.code.gson", "gson");
        exclude(dependency, hosts, "org.yaml.snakeyaml", "org.yaml", "snakeyaml");
        for (String artifact : Arrays.asList("adventure-api", "adventure-key", "adventure-text-minimessage",
                "adventure-text-serializer-gson", "adventure-text-serializer-json", "adventure-text-serializer-legacy",
                "adventure-text-serializer-plain", "adventure-text-serializer-commons", "examination-api", "examination-string", "option"))
            exclude(dependency, hosts, "net.kyori", "net.kyori", artifact);
    }
    private static void exclude(ExternalModuleDependency dependency, List<String> hosts, String pkg, String group, String artifact) {
        for (String host : hosts) if (JarBoundary.within(pkg.replace('.', '/'), host.replace('.', '/'))) {
            Map<String, String> rule = new LinkedHashMap<String, String>(); rule.put("group", group); rule.put("module", artifact);
            dependency.exclude(rule); return;
        }
    }
}
