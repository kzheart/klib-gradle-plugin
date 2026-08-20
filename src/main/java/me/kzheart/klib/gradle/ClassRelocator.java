package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 无需加载目标类即可重写类文件中的 UTF-8 常量。 */
final class ClassRelocator {
    private static final int CLASS_MAGIC = 0xCAFEBABE;

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
        try {
            ByteArrayInputStream bytes = new ByteArrayInputStream(source);
            DataInputStream input = new DataInputStream(bytes);
            ByteArrayOutputStream result = new ByteArrayOutputStream(source.length + 128);
            DataOutputStream output = new DataOutputStream(result);
            int magic = input.readInt();
            if (magic != CLASS_MAGIC) {
                throw new GradleException("Invalid class file in shaded dependency");
            }
            output.writeInt(magic);
            output.writeShort(input.readUnsignedShort());
            output.writeShort(input.readUnsignedShort());
            int constantPoolCount = input.readUnsignedShort();
            output.writeShort(constantPoolCount);
            for (int index = 1; index < constantPoolCount; index++) {
                int tag = input.readUnsignedByte();
                output.writeByte(tag);
                if (tag == 1) {
                    output.writeUTF(replace(input.readUTF(), relocations, protectedPrefixes));
                } else if (tag == 3 || tag == 4) {
                    copy(input, output, 4);
                } else if (tag == 5 || tag == 6) {
                    copy(input, output, 8);
                    index++;
                } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                    copy(input, output, 2);
                } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12
                        || tag == 17 || tag == 18) {
                    copy(input, output, 4);
                } else if (tag == 15) {
                    copy(input, output, 3);
                } else {
                    throw new GradleException("Unsupported class constant-pool tag: " + tag);
                }
            }
            copy(input, output, bytes.available());
            output.flush();
            return result.toByteArray();
        } catch (IOException failure) {
            throw new GradleException("Cannot relocate shaded class", failure);
        }
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

    private static void copy(DataInputStream input, DataOutputStream output, int length)
            throws IOException {
        byte[] buffer = new byte[length];
        input.readFully(buffer);
        output.write(buffer);
    }
}
