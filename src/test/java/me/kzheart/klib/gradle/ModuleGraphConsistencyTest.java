package me.kzheart.klib.gradle;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 固定插件公开支持的模块集合和直接依赖契约。 */
class ModuleGraphConsistencyTest {
    @Test
    void moduleGraphMatchesPublishedKlibContract() {
        Map<String, List<String>> expected = new LinkedHashMap<String, List<String>>();
        expected.put("core", Collections.<String>emptyList());
        expected.put("compat", Collections.<String>emptyList());
        expected.put("compat-v1_12", Collections.singletonList("compat"));
        expected.put("compat-v1_20", Collections.singletonList("compat"));
        expected.put("compat-v1_21", Collections.singletonList("compat"));
        expected.put("compat-v26", Collections.singletonList("compat"));
        expected.put("config", Collections.singletonList("core"));
        expected.put("lang", Arrays.asList("core", "config"));
        expected.put("command", Arrays.asList("core", "lang"));
        expected.put("item", Collections.<String>emptyList());
        expected.put("data", Collections.singletonList("core"));
        expected.put("data-json", Collections.singletonList("data"));
        expected.put("data-jdbc", Collections.singletonList("data"));
        expected.put("data-sqlite", Collections.singletonList("data-jdbc"));
        expected.put("data-mysql", Collections.singletonList("data-jdbc"));
        expected.put("ui", Arrays.asList("core", "item"));
        expected.put("script", Collections.singletonList("core"));
        expected.put("hook", Collections.singletonList("core"));
        expected.put("remote", Collections.singletonList("core"));

        assertEquals(new LinkedHashSet<String>(expected.keySet()), KlibModuleGraph.knownModules());
        assertEquals(expected, KlibModuleGraph.directDependencies());
    }
}
