package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;

import java.util.regex.Pattern;

/**
 * {@code klib { main(...) }} 的取值校验。
 *
 * <p>各任务统一走这里，保证缺失或格式错误时报出的都是指向 DSL 的可操作信息，
 * 而不是 Gradle 的内部属性名。
 */
final class MainClassSpec {
    private static final Pattern MAIN_CLASS = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");

    private MainClassSpec() {
    }

    /** 校验并返回规范化的主类名；缺失或非法时抛出可操作异常。 */
    static String require(String value) {
        String main = value == null ? "" : value.trim();
        if (main.isEmpty()) {
            throw new GradleException("klib.main is not set: declare the Bukkit main class with "
                    + "main(\"com.example.MyPlugin\") inside the klib { } block");
        }
        if (!MAIN_CLASS.matcher(main).matches()) {
            throw new GradleException("klib.main is not a fully-qualified Java class: " + main
                    + "; expected something like main(\"com.example.MyPlugin\")");
        }
        return main;
    }
}
