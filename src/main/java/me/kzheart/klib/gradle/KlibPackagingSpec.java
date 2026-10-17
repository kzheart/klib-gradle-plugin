package me.kzheart.klib.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import javax.inject.Inject;
import java.util.Arrays;

/** 显式选择宿主提供依赖的打包规则；默认保留通用模式。 */
public class KlibPackagingSpec {
    private final Property<Boolean> paper;
    private final ListProperty<String> hostPackages;
    @Inject public KlibPackagingSpec(ObjectFactory objects) {
        paper = objects.property(Boolean.class).convention(false);
        hostPackages = objects.listProperty(String.class).convention(Arrays.asList(
                "net.kyori", "org.bukkit", "io.papermc", "com.google.gson", "org.yaml.snakeyaml"));
    }
    public void paper() { paper.set(true); }
    public Property<Boolean> getPaper() { return paper; }
    /** 可替换完整名单，或通过 hostPackages(...) 追加。 */
    public ListProperty<String> getHostPackages() { return hostPackages; }
    public void hostPackages(String... values) { hostPackages.addAll(Arrays.asList(values)); }
}
