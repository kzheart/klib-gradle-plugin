package me.kzheart.klib.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import javax.inject.Inject;
import java.util.Arrays;
import java.util.Collections;

/** 待验证的 API 原始 JAR 与明确的包白名单。 */
public class KlibApiSpec {
    private final ConfigurableFileCollection archives;
    private final ListProperty<String> packages;
    private final ListProperty<String> hostPackages;
    @Inject public KlibApiSpec(ObjectFactory objects) {
        archives = objects.fileCollection();
        packages = objects.listProperty(String.class).convention(Collections.emptyList());
        hostPackages = objects.listProperty(String.class).convention(Arrays.asList("org.bukkit", "io.papermc", "net.kyori"));
    }
    public void archive(Object provider) { archives.from(provider); }
    public void packages(String... values) { packages.addAll(Arrays.asList(values)); }
    public ConfigurableFileCollection getArchives() { return archives; }
    public void hostPackages(String... values) { hostPackages.addAll(Arrays.asList(values)); }
    public ListProperty<String> getHostPackages() { return hostPackages; }
    public ListProperty<String> getPackages() { return packages; }
}
