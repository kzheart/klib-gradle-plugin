package me.kzheart.klib.gradle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** {@code modules { data { } }} 的显式数据能力选择。 */
public final class KlibDataModulesSpec {
    private final Set<KlibModule> selected = new LinkedHashSet<KlibModule>();

    public void json() {
        selected.add(KlibModule.DATA_JSON);
    }

    public void jdbc() {
        selected.add(KlibModule.DATA_JDBC);
    }

    public void sqlite() {
        selected.add(KlibModule.DATA_SQLITE);
    }

    public void mysql() {
        selected.add(KlibModule.DATA_MYSQL);
    }

    List<KlibModule> selected() {
        return Collections.unmodifiableList(new ArrayList<KlibModule>(selected));
    }
}
