package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;

import java.util.regex.Pattern;

/** Guard 商品数据目录名的统一校验（与 Bukkit 插件目录命名一致）。 */
final class GuardDataDirectorySpec {
    private static final Pattern DATA_DIRECTORY = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private GuardDataDirectorySpec() {
    }

    static String require(String value) {
        String directory = value == null ? "" : value.trim();
        if (directory.isEmpty()) {
            throw new GradleException("klib.name is not set: Guard 商品需要插件目录名，"
                    + "请使用 name(\"MyPlugin\")");
        }
        if (!DATA_DIRECTORY.matcher(directory).matches()) {
            throw new GradleException("klib.name is not a valid plugin data directory: "
                    + directory);
        }
        return directory;
    }
}
