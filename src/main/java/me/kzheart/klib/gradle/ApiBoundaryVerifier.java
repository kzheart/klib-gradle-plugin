package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 用 ASM 完整访问代码与声明；白名单只允许共享边界类型。 */
final class ApiBoundaryVerifier {
    private static final Set<String> MARKERS = new HashSet<String>(Arrays.asList(
            "kotlin/Metadata", "org/jetbrains/annotations/NotNull", "org/jetbrains/annotations/Nullable",
            "javax/annotation/Nonnull", "javax/annotation/Nullable"));
    // Kotlin metadata 使用 Kotlin 名称表示 JVM 内建类型，不能把整个 kotlin 包放行。
    private static final Set<String> JVM_BUILTINS = new HashSet<String>(Arrays.asList(
            "kotlin/Any", "kotlin/Nothing", "kotlin/Unit", "kotlin/String", "kotlin/CharSequence",
            "kotlin/Throwable", "kotlin/Number", "kotlin/Comparable", "kotlin/Enum", "kotlin/Annotation",
            "kotlin/Boolean", "kotlin/Byte", "kotlin/Short", "kotlin/Int", "kotlin/Long", "kotlin/Float", "kotlin/Double", "kotlin/Char",
            "kotlin/Array", "kotlin/BooleanArray", "kotlin/ByteArray", "kotlin/ShortArray", "kotlin/IntArray", "kotlin/LongArray", "kotlin/FloatArray", "kotlin/DoubleArray", "kotlin/CharArray",
            "kotlin/collections/Iterable", "kotlin/collections/MutableIterable", "kotlin/collections/Collection", "kotlin/collections/MutableCollection",
            "kotlin/collections/List", "kotlin/collections/MutableList", "kotlin/collections/Set", "kotlin/collections/MutableSet",
            "kotlin/collections/Map", "kotlin/collections/MutableMap", "kotlin/collections/Map.Entry", "kotlin/collections/MutableMap.MutableEntry",
            "kotlin/collections/Iterator", "kotlin/collections/MutableIterator", "kotlin/collections/ListIterator", "kotlin/collections/MutableListIterator"));
    private final List<String> packages;
    private final List<String> hosts;
    ApiBoundaryVerifier(List<String> packages, List<String> hosts) {
        JarBoundary.packages(packages); JarBoundary.packages(hosts);
        if (packages.isEmpty()) throw new GradleException("klib.api.packages must declare API packages");
        for (String pkg : packages) {
            String name = pkg.replace('.', '/');
            if (forbidden(name) || JarBoundary.overlaps(name, "java") || JarBoundary.overlaps(name, "javax")
                    || JarBoundary.overlaps(name, "org/jetbrains/annotations"))
                throw new GradleException("API package must belong to the API, not a runtime: " + pkg);
            for (String host : hosts) if (JarBoundary.overlaps(name, host.replace('.', '/')))
                throw new GradleException("API package overlaps host: " + pkg);
        }
        this.packages = packages; this.hosts = hosts;
    }
    void verify(byte[] bytes, String path) {
        ClassReader reader = new ClassReader(bytes);
        String name = reader.getClassName();
        if (!path.equals(name + ".class")) throw new GradleException("API class path/name mismatch: " + path + " / " + name);
        if (forbidden(name) || !in(name, packages)) throw new GradleException("Class outside declared API packages: " + name);
        Remapper remapper = new Remapper(Opcodes.ASM9) {
            @Override public String map(String reference) { check(reference, false); return reference; }
        };
        // 无 SKIP_CODE：包括指令、堆栈帧、局部变量、handle、indy 与递归 condy。
        reader.accept(new ClassRemapper(new ClassWriter(0), remapper), 0);
        KotlinMetadataRelocator.verifyApi(bytes, reference -> check(reference, true));
    }
    void checkMetadata(String name) { check(name, true); }
    private void check(String name, boolean metadata) {
        if (MARKERS.contains(name)) return;
        if (metadata && JVM_BUILTINS.contains(name)) return;
        if (!forbidden(name) && (jdk(name) || JarBoundary.within(name, "org/bukkit") || in(name, packages) || in(name, hosts))) return;
        throw new GradleException("API references forbidden implementation/runtime type: " + name);
    }
    private static boolean forbidden(String name) {
        return JarBoundary.within(name, "kotlin") || JarBoundary.within(name, "kotlinx") || JarBoundary.within(name, "me/kzheart/klib");
    }
    private static boolean jdk(String name) {
        return JarBoundary.within(name, "java") || jdkJavax(name)
                || JarBoundary.within(name, "org/w3c/dom") || JarBoundary.within(name, "org/xml/sax")
                || JarBoundary.within(name, "org/ietf/jgss");
    }
    private static boolean jdkJavax(String name) {
        for (String pkg : Arrays.asList("sql", "naming", "xml", "management", "crypto", "net", "security", "accessibility", "imageio", "print", "sound", "swing", "tools", "lang/model", "annotation/processing"))
            if (JarBoundary.within(name, "javax/" + pkg)) return true;
        return false;
    }
    private static boolean in(String name, List<String> packages) {
        for (String pkg : packages) if (JarBoundary.within(name, pkg.replace('.', '/'))) return true;
        return false;
    }
}
