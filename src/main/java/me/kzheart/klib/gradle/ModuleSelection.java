package me.kzheart.klib.gradle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** 不可变且依赖完整的 klib 模块选择结果。 */
public final class ModuleSelection {
    private final List<KlibModule> requested;
    private final List<KlibModule> resolved;

    private ModuleSelection(List<KlibModule> requested, List<KlibModule> resolved) {
        this.requested = Collections.unmodifiableList(new ArrayList<KlibModule>(requested));
        this.resolved = Collections.unmodifiableList(new ArrayList<KlibModule>(resolved));
    }

    public static ModuleSelection resolve(Collection<KlibModule> requested) {
        List<KlibModule> input = new ArrayList<KlibModule>(requested);
        return new ModuleSelection(input, KlibModuleGraph.resolve(input));
    }

    public List<KlibModule> requested() {
        return requested;
    }

    public List<KlibModule> resolved() {
        return resolved;
    }
}
