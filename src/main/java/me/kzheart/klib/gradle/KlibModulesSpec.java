package me.kzheart.klib.gradle;

import org.gradle.api.Action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** {@code klib.modules { }} 的类型安全模块选择 DSL。 */
public final class KlibModulesSpec {
    private final Set<KlibModule> selected = new LinkedHashSet<KlibModule>();

    public void core() {
        select(KlibModule.CORE);
    }

    public void compat() {
        select(KlibModule.COMPAT);
    }

    public void compatV1_12() {
        select(KlibModule.COMPAT_V1_12);
    }

    public void compatV1_20() {
        select(KlibModule.COMPAT_V1_20);
    }

    public void compatV1_21() {
        select(KlibModule.COMPAT_V1_21);
    }

    public void compatV26() {
        select(KlibModule.COMPAT_V26);
    }

    public void config() {
        select(KlibModule.CONFIG);
    }

    public void lang() {
        select(KlibModule.LANG);
    }

    public void command() {
        select(KlibModule.COMMAND);
    }

    public void item() {
        select(KlibModule.ITEM);
    }

    public void data() {
        select(KlibModule.DATA);
    }

    /** 选择数据基础模块及显式存储能力。 */
    public void data(Action<? super KlibDataModulesSpec> action) {
        select(KlibModule.DATA);
        KlibDataModulesSpec spec = new KlibDataModulesSpec();
        action.execute(spec);
        selected.addAll(spec.selected());
    }

    public void ui() {
        select(KlibModule.UI);
    }

    public void script() {
        select(KlibModule.SCRIPT);
    }

    public void hook() {
        select(KlibModule.HOOK);
    }

    public void remote() {
        select(KlibModule.REMOTE);
    }

    /** 清空当前块中的选择，用于构建完全不内嵌 Klib 的插件。 */
    public void none() {
        selected.clear();
    }

    List<KlibModule> selected() {
        return Collections.unmodifiableList(new ArrayList<KlibModule>(selected));
    }

    private void select(KlibModule module) {
        selected.add(module);
    }
}
