package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;

import java.util.regex.Pattern;

/** Guard 商品入口类名的统一校验。 */
final class GuardEntrypointSpec {
    private static final Pattern CLASS_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");

    private GuardEntrypointSpec() {
    }

    static String require(String value) {
        String entrypoint = value == null ? "" : value.trim();
        if (entrypoint.isEmpty()) {
            throw new GradleException("klib.guardProduct.entrypoint is not set: declare it with "
                    + "entrypoint(\"com.example.CloudPlugin\")");
        }
        if (!CLASS_NAME.matcher(entrypoint).matches()) {
            throw new GradleException("klib.guardProduct.entrypoint is not a fully-qualified Java "
                    + "class: " + entrypoint);
        }
        return entrypoint;
    }
}
