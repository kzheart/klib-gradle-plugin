package me.kzheart.klib.gradle;

import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;
import java.util.Arrays;

/** 用于生成 Bukkit 元数据并组装内嵌依赖插件 JAR 的 DSL 配置值。 */
public class KlibExtension {
    private final Property<String> name;
    private final Property<String> main;
    private final Property<String> version;
    private final Property<String> apiVersion;
    private final ListProperty<String> depend;
    private final ListProperty<String> softdepend;
    private final Property<String> targetPackage;
    private final Property<String> libraryVersion;
    private final ListProperty<KlibModule> modules;
    private final MapProperty<String, String> relocations;
    private final Property<Boolean> ketherInterop;
    private final KlibRemoteSpec remote;
    private final Property<Boolean> remoteConfigured;

    @Inject
    public KlibExtension(ObjectFactory objects) {
        remote = objects.newInstance(KlibRemoteSpec.class);
        remoteConfigured = objects.property(Boolean.class);
        remoteConfigured.convention(Boolean.FALSE);
        name = objects.property(String.class);
        main = objects.property(String.class);
        version = objects.property(String.class);
        apiVersion = objects.property(String.class);
        depend = objects.listProperty(String.class);
        softdepend = objects.listProperty(String.class);
        targetPackage = objects.property(String.class);
        libraryVersion = objects.property(String.class);
        modules = objects.listProperty(KlibModule.class);
        relocations = objects.mapProperty(String.class, String.class);
        ketherInterop = objects.property(Boolean.class);
    }

    public Property<String> getName() {
        return name;
    }

    public Property<String> getMain() {
        return main;
    }

    public Property<String> getVersion() {
        return version;
    }

    public Property<String> getApiVersion() {
        return apiVersion;
    }

    public ListProperty<String> getDepend() {
        return depend;
    }

    public ListProperty<String> getSoftdepend() {
        return softdepend;
    }

    public Property<String> getTargetPackage() {
        return targetPackage;
    }

    public ListProperty<KlibModule> getModules() {
        return modules;
    }

    public Property<String> getLibraryVersion() {
        return libraryVersion;
    }

    public MapProperty<String, String> getRelocations() {
        return relocations;
    }

    public Property<Boolean> getKetherInterop() {
        return ketherInterop;
    }

    public void name(String value) {
        name.set(value);
    }

    public void main(String value) {
        main.set(value);
    }

    public void version(String value) {
        version.set(value);
    }

    /**
     * 设置 Bukkit {@code api-version}；默认 {@code 1.13}。传入空串等价于
     * {@link #noApiVersion()}，即不生成该键。
     */
    public void apiVersion(String value) {
        apiVersion.set(value);
    }

    /**
     * 不生成 {@code api-version} 键，用于需要支持 1.12.2 及更早服务端的插件。
     * 与 {@code apiVersion("")} 等价，只是意图更明确。
     */
    public void noApiVersion() {
        apiVersion.set("");
    }

    /** 追加硬依赖；多次调用会累加，并在生成时按首次出现顺序去重。 */
    public void depend(String... values) {
        depend.addAll(Arrays.asList(values));
    }

    /** 追加软依赖；多次调用会累加，并在生成时按首次出现顺序去重。 */
    public void softdepend(String... values) {
        softdepend.addAll(Arrays.asList(values));
    }

    public void targetPackage(String value) {
        targetPackage.set(value);
    }

    /** 覆盖当前 Gradle 插件默认配套的 Klib 模块版本。 */
    public void libraryVersion(String value) {
        libraryVersion.set(value);
    }

    /** 选择需要内嵌的 Klib 模块；依赖模块由插件自动补齐。 */
    public void modules(Action<? super KlibModulesSpec> action) {
        KlibModulesSpec spec = new KlibModulesSpec();
        action.execute(spec);
        modules.set(spec.selected());
    }

    /** 将外部依赖包重定位到 {@code targetPackage.libs.<targetSuffix>}。 */
    public void relocate(String sourcePackage, String targetSuffix) {
        relocations.put(sourcePackage, targetSuffix);
    }

    public void ketherInterop(boolean enabled) {
        ketherInterop.set(enabled);
    }

    public KlibRemoteSpec getRemote() {
        return remote;
    }

    /** 是否声明过 {@code remote { }} 块；未声明时不生成任何接入常量。 */
    public Property<Boolean> getRemoteConfigured() {
        return remoteConfigured;
    }

    public void remote(Action<? super KlibRemoteSpec> action) {
        action.execute(remote);
        remoteConfigured.set(Boolean.TRUE);
    }
}
