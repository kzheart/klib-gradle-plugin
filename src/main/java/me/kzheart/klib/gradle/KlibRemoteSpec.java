package me.kzheart.klib.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/**
 * {@code klib { remote { } }} 的构建期 Remote 接入配置。
 *
 * <p>{@code publicKey} 是只写的公开项目标识（{@code rpk_live_}/{@code rpk_test_}），会被写进随
 * JAR 分发的常量类。采集能力默认关闭，控制台运行策略只能在这里声明的上限内继续收紧。
 */
public class KlibRemoteSpec {
    private final Property<String> endpoint;
    private final Property<String> publicKey;
    private final Property<Boolean> exceptions;
    private final Property<Boolean> logs;
    private final Property<Boolean> manualIncidents;

    @Inject
    public KlibRemoteSpec(ObjectFactory objects) {
        endpoint = objects.property(String.class);
        publicKey = objects.property(String.class);
        exceptions = objects.property(Boolean.class);
        exceptions.convention(Boolean.FALSE);
        logs = objects.property(Boolean.class);
        logs.convention(Boolean.FALSE);
        manualIncidents = objects.property(Boolean.class);
        manualIncidents.convention(Boolean.FALSE);
    }

    public Property<String> getEndpoint() {
        return endpoint;
    }

    public Property<String> getPublicKey() {
        return publicKey;
    }

    public Property<Boolean> getExceptions() {
        return exceptions;
    }

    public Property<Boolean> getLogs() {
        return logs;
    }

    public Property<Boolean> getManualIncidents() {
        return manualIncidents;
    }

    public void endpoint(String value) {
        endpoint.set(value);
    }

    public void publicKey(String value) {
        publicKey.set(value);
    }
}
