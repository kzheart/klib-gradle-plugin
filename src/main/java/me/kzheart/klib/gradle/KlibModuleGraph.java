package me.kzheart.klib.gradle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 依赖配置和内嵌依赖配置使用的标准模块依赖图。 */
public final class KlibModuleGraph {
    private KlibModuleGraph() {
    }

    public static List<KlibModule> resolve(Collection<KlibModule> requested) {
        Set<KlibModule> resolved = new LinkedHashSet<KlibModule>();
        Set<KlibModule> visiting = new LinkedHashSet<KlibModule>();
        for (KlibModule module : requested) {
            visit(module, resolved, visiting);
        }
        return Collections.unmodifiableList(new ArrayList<KlibModule>(resolved));
    }

    public static Set<String> knownModules() {
        return Collections.unmodifiableSet(directDependencies().keySet());
    }

    private static void visit(
            KlibModule module,
            Set<KlibModule> resolved,
            Set<KlibModule> visiting
    ) {
        if (resolved.contains(module)) {
            return;
        }
        if (!visiting.add(module)) {
            throw new IllegalStateException(
                    "Cyclic klib module dependency at " + module.artifactSuffix());
        }
        for (KlibModule dependency : module.dependencies()) {
            visit(dependency, resolved, visiting);
        }
        visiting.remove(module);
        resolved.add(module);
    }

    /** 每个公开模块的直接运行时模块依赖。 */
    static Map<String, List<String>> directDependencies() {
        Map<String, List<String>> graph = new LinkedHashMap<String, List<String>>();
        for (KlibModule module : KlibModule.values()) {
            List<String> dependencies = new ArrayList<String>();
            for (KlibModule dependency : module.dependencies()) {
                dependencies.add(dependency.artifactSuffix());
            }
            graph.put(module.artifactSuffix(), Collections.unmodifiableList(dependencies));
        }
        return Collections.unmodifiableMap(graph);
    }
}
