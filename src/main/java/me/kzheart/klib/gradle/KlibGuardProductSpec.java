package me.kzheart.klib.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/** {@code klib { guardProduct { } }} 的云端商品构建配置。 */
public class KlibGuardProductSpec {
    private final Property<String> entrypoint;
    private final Property<String> guardApiVersion;

    @Inject
    public KlibGuardProductSpec(ObjectFactory objects) {
        entrypoint = objects.property(String.class);
        guardApiVersion = objects.property(String.class);
    }

    public Property<String> getEntrypoint() {
        return entrypoint;
    }

    public Property<String> getGuardApiVersion() {
        return guardApiVersion;
    }

    /** 设置商品 JAR 中 {@code RemotePluginEntrypoint} 实现类的完整类名。 */
    public void entrypoint(String value) {
        entrypoint.set(value);
    }

    /** 覆盖默认配套的 {@code klib-guard-api} 版本。 */
    public void guardApiVersion(String value) {
        guardApiVersion.set(value);
    }
}
