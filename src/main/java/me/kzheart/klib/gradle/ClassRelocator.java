package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 使用 JVM 结构重定位类引用与反射字符串，保留字段和方法标识符。 */
final class ClassRelocator {
    private static final String DESCRIPTOR_TYPE = "(?:\\[*[ZCBSIFJD]|\\[*L(?:[\\w$]+/)+[\\w$]+;)";
    private static final java.util.regex.Pattern TYPE_DESCRIPTOR = java.util.regex.Pattern.compile(DESCRIPTOR_TYPE);
    private static final java.util.regex.Pattern METHOD_DESCRIPTOR = java.util.regex.Pattern.compile(
            "\\(" + DESCRIPTOR_TYPE + "*\\)(?:V|" + DESCRIPTOR_TYPE + ")");
    private static final java.util.regex.Pattern REFLECTION_ARRAY = java.util.regex.Pattern.compile(
            "\\[+L(?:[\\w$]+\\.)+[\\w$]+;");


    private static final Pattern OBJECT_DESCRIPTOR_FRAGMENT = Pattern.compile("L((?:[\\w$]+/)+[\\w$]+);");

    private ClassRelocator() {
    }

    static byte[] relocate(byte[] source, Map<String, String> relocations) {
        return relocate(source, relocations, Collections.<String>emptyList());
    }

    static byte[] relocate(
            byte[] source,
            Map<String, String> relocations,
            List<String> protectedPrefixes
    ) {
        source = KotlinReflectRelocator.prepare(source, relocations, protectedPrefixes);
        KotlinMetadataRelocator.PreparedClass metadata =
                KotlinMetadataRelocator.prepare(source, relocations, protectedPrefixes);
        try {
            ClassReader reader = new ClassReader(metadata.getClassBytes());
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassRemapper(writer, new Remapper(Opcodes.ASM9) {
                @Override public String map(String internalName) {
                    return relocateInternalName(internalName, relocations, protectedPrefixes);
                }
                @Override public Object mapValue(Object value) {
                    // Dynamic class names and reflection package strings need relocation too;
                    // field, method, local-variable and annotation-element identifiers do not.
                    if (value instanceof String) {
                        String text = (String) value;
                        // A user's label "kotlin" is not a class name. Kotlin's own reflection
                        // implementation constructs built-in package IDs from that bare string.
                        if (relocations.containsKey(text)) {
                            String ownerPrefix = text.replace('.', '/') + "/";
                            if (!reader.getClassName().startsWith(ownerPrefix)) return text;
                        }
                        int arguments = text.indexOf('(');
                        if (arguments > 0 && text.substring(0, arguments).matches("[\\w$]+|<init>")
                                && METHOD_DESCRIPTOR.matcher(text.substring(arguments)).matches()) {
                            return text.substring(0, arguments) + mapMethodDesc(text.substring(arguments));
                        }
                        if (text.startsWith("(") && METHOD_DESCRIPTOR.matcher(text).matches()) {
                            try { return mapMethodDesc(text); }
                            catch (IllegalArgumentException | IndexOutOfBoundsException malformed) { return text; }
                        }
                        if (REFLECTION_ARRAY.matcher(text).matches()) {
                            int element = text.indexOf('L') + 1;
                            String name = text.substring(element, text.length() - 1).replace('.', '/');
                            return text.substring(0, element) + map(name).replace('/', '.') + ";";
                        }
                        if (TYPE_DESCRIPTOR.matcher(text).matches()) {
                            try { return mapDesc(text); }
                            catch (IllegalArgumentException | IndexOutOfBoundsException malformed) { return text; }
                        }
                        if (text.indexOf('/') >= 0) {
                            return relocateInternalName(text, relocations, protectedPrefixes);
                        }
                        return relocateInternalName(text.replace('.', '/'), relocations, protectedPrefixes)
                                .replace('/', '.');
                    }
                    return super.mapValue(value);
                }
            }) {
                @Override protected MethodVisitor createMethodRemapper(MethodVisitor visitor) {
                    return new MethodRemapper(Opcodes.ASM9, visitor, remapper) {
                        @Override public void visitInvokeDynamicInsn(String name, String descriptor,
                                Handle bootstrap, Object... arguments) {
                            // Java 9+ concat recipes are not complete method descriptors: their
                            // argument slots are represented by U+0001/U+0002 markers. Nashorn uses
                            // such recipes to assemble descriptors for generated script bytecode.
                            if (bootstrap.getOwner().equals("java/lang/invoke/StringConcatFactory")
                                    && bootstrap.getName().equals("makeConcatWithConstants")
                                    && arguments.length > 0 && arguments[0] instanceof String) {
                                arguments = arguments.clone();
                                Matcher fragments = OBJECT_DESCRIPTOR_FRAGMENT.matcher((String) arguments[0]);
                                StringBuffer relocated = new StringBuffer();
                                while (fragments.find()) {
                                    fragments.appendReplacement(relocated, Matcher.quoteReplacement(
                                            "L" + remapper.map(fragments.group(1)) + ";"));
                                }
                                fragments.appendTail(relocated);
                                arguments[0] = relocated.toString();
                            }
                            super.visitInvokeDynamicInsn(name, descriptor, bootstrap, arguments);
                        }
                    };
                }
            }, 0);
            return metadata.restore(writer.toByteArray());
        } catch (RuntimeException failure) {
            throw new GradleException("Cannot relocate shaded class", failure);
        }
    }

    static String relocateInternalName(String name, Map<String, String> relocations,
                                       List<String> protectedPrefixes) {
        for (String prefix : protectedPrefixes) {
            String internal = prefix.replace('.', '/');
            if (name.equals(internal) || name.startsWith(internal + "/")) return name;
        }
        Map.Entry<String, String> match = null;
        for (Map.Entry<String, String> entry : relocations.entrySet()) {
            String prefix = entry.getKey().replace('.', '/');
            if ((name.equals(prefix) || name.startsWith(prefix + "/"))
                    && (match == null || entry.getKey().length() > match.getKey().length())) match = entry;
        }
        return match == null ? name : match.getValue().replace('.', '/') + name.substring(match.getKey().length());
    }

    static String replace(String source, Map<String, String> relocations) {
        return replace(source, relocations, Collections.<String>emptyList());
    }

    static String replace(
            String source,
            Map<String, String> relocations,
            List<String> protectedPrefixes
    ) {
        String result = source;
        for (Map.Entry<String, String> relocation : relocations.entrySet()) {
            // 单段包名的 dotted/internal 拼写相同，裸替换两遍会再次命中目标包，
            // 并把 JVM 路径写成带点的名称。分别匹配分隔符，不触碰 kotlin_module 等资源名。
            if (relocation.getKey().indexOf('.') < 0) {
                if (result.equals(relocation.getKey()) && !isProtected(result, 0, protectedPrefixes, false)) {
                    result = relocation.getValue();
                } else {
                    result = replaceUnprotected(result, relocation.getKey() + ".",
                            relocation.getValue() + ".", protectedPrefixes, false);
                    result = replaceUnprotected(result, relocation.getKey() + "/",
                            relocation.getValue().replace('.', '/') + "/", protectedPrefixes, true);
                }
                continue;
            }
            result = replaceUnprotected(
                    result,
                    relocation.getKey(),
                    relocation.getValue(),
                    protectedPrefixes,
                    false);
            result = replaceUnprotected(
                    result,
                    relocation.getKey().replace('.', '/'),
                    relocation.getValue().replace('.', '/'),
                    protectedPrefixes,
                    true);
        }
        return result;
    }

    private static String replaceUnprotected(
            String value,
            String source,
            String target,
            List<String> protectedPrefixes,
            boolean internalNames
    ) {
        int match = value.indexOf(source);
        if (match < 0) {
            return value;
        }
        StringBuilder result = new StringBuilder(value.length() + 32);
        int cursor = 0;
        while (match >= 0) {
            result.append(value, cursor, match);
            result.append(isProtected(value, match, protectedPrefixes, internalNames)
                    ? source : target);
            cursor = match + source.length();
            match = value.indexOf(source, cursor);
        }
        return result.append(value, cursor, value.length()).toString();
    }

    private static boolean isProtected(
            String value,
            int offset,
            List<String> protectedPrefixes,
            boolean internalNames
    ) {
        for (String prefix : protectedPrefixes) {
            String candidate = internalNames ? prefix.replace('.', '/') : prefix;
            if (value.startsWith(candidate, offset)) {
                return true;
            }
        }
        return false;
    }

}
