package me.kzheart.klib.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 为最终发行 JAR 生成 TabooLib OpenContainer 形状的 Bukkit 主类。 */
@CacheableTask
public abstract class PrepareKetherInteropJarTask extends DefaultTask {
    private static final int CLASS_MAGIC = 0xCAFEBABE;
    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_PROTECTED = 0x0004;
    private static final int ACC_FINAL = 0x0010;
    private static final int ACC_SUPER = 0x0020;
    private static final int ACC_INTERFACE = 0x0200;
    private static final int ACC_ABSTRACT = 0x0400;
    private static final int ACC_ANNOTATION = 0x2000;
    private static final int ACC_ENUM = 0x4000;

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getBaseJar();

    @Input
    public abstract Property<Boolean> getInteropEnabled();

    @Input
    public abstract Property<String> getMainClass();

    @Input
    public abstract Property<String> getTargetPackage();

    @OutputFile
    public abstract RegularFileProperty getArchiveFile();

    @TaskAction
    public void prepare() {
        Path input = getBaseJar().get().getAsFile().toPath();
        Path output = getArchiveFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            if (!getInteropEnabled().get()) {
                Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            writeInteropJar(input, output);
        } catch (IOException failure) {
            throw new GradleException("Cannot prepare Kether interoperability jar", failure);
        }
    }

    String generatedMainClass() {
        return generatedMainClass(getTargetPackage().get());
    }

    static String generatedMainClass(String targetPackage) {
        String target = targetPackage.trim();
        if (target.contains("taboolib")) {
            throw new GradleException("klib.targetPackage must not contain the reserved text "
                    + "'taboolib' when Kether interoperability is enabled: "
                    + targetPackage);
        }
        return target + ".libs.klib.script.taboolib.platform.BukkitPlugin";
    }

    private void writeInteropJar(Path input, Path output) throws IOException {
        String originalMain = MainClassSpec.require(getMainClass().getOrElse(""));
        String generatedMain = generatedMainClass();
        String originalPath = classPath(originalMain);
        String generatedPath = classPath(generatedMain);
        Map<String, byte[]> entries = readEntries(input);
        byte[] originalClass = entries.get(originalPath);
        if (originalClass == null) {
            throw new GradleException("Klib main class is missing from the plugin jar: "
                    + originalMain);
        }
        if (entries.containsKey(generatedPath)) {
            throw new GradleException("Generated Kether interoperability class conflicts with "
                    + generatedMain);
        }
        byte[] pluginYaml = entries.get("plugin.yml");
        if (pluginYaml == null) {
            throw new GradleException("plugin.yml is missing from the plugin jar");
        }

        entries.put(originalPath, makeExtendable(originalClass, originalMain));
        entries.put(generatedPath, generateSubclass(generatedMain, originalMain));
        entries.put("plugin.yml", rewriteMain(pluginYaml, generatedMain));
        writeEntries(output, entries);
    }

    private static Map<String, byte[]> readEntries(Path archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<? extends ZipEntry> ordered = Collections.list(zip.entries());
            Collections.sort(ordered, (left, right) -> left.getName().compareTo(right.getName()));
            for (ZipEntry entry : ordered) {
                if (entry.isDirectory()) {
                    continue;
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), readAll(input));
                }
            }
        }
        return entries;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void writeEntries(Path output, Map<String, byte[]> entries) throws IOException {
        Path temporary = Files.createTempFile(output.getParent(), output.getFileName().toString(),
                ".tmp");
        try {
            List<String> names = new ArrayList<String>(entries.keySet());
            Collections.sort(names);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                for (String name : names) {
                    ZipEntry entry = new ZipEntry(name);
                    entry.setTime(0L);
                    zip.putNextEntry(entry);
                    zip.write(entries.get(name));
                    zip.closeEntry();
                }
            }
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static byte[] makeExtendable(byte[] source, String mainClass) {
        try {
            ClassFile classFile = ClassFile.read(source);
            int access = classFile.accessFlags();
            if ((access & ACC_PUBLIC) == 0) {
                throw new GradleException("klib.main must be public for Kether interoperability: "
                        + mainClass);
            }
            if ((access & (ACC_INTERFACE | ACC_ABSTRACT | ACC_ANNOTATION | ACC_ENUM)) != 0) {
                throw new GradleException("klib.main must be a concrete class for Kether "
                        + "interoperability: " + mainClass);
            }
            if (!classFile.hasAccessibleNoArgConstructor()) {
                throw new GradleException("klib.main must declare a public or protected no-arg "
                        + "constructor for Kether interoperability: " + mainClass);
            }
            byte[] transformed = source.clone();
            int updated = access & ~ACC_FINAL;
            transformed[classFile.accessOffset] = (byte) (updated >>> 8);
            transformed[classFile.accessOffset + 1] = (byte) updated;
            return transformed;
        } catch (IOException failure) {
            throw new GradleException("Cannot inspect klib.main class " + mainClass, failure);
        }
    }

    static byte[] generateSubclass(String generatedClass, String mainClass) {
        String generatedInternal = generatedClass.replace('.', '/');
        String mainInternal = mainClass.replace('.', '/');
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(CLASS_MAGIC);
            output.writeShort(0);
            output.writeShort(52);
            output.writeShort(10);
            utf8(output, generatedInternal);       // 1
            classInfo(output, 1);                  // 2
            utf8(output, mainInternal);            // 3
            classInfo(output, 3);                  // 4
            utf8(output, "<init>");                // 5
            utf8(output, "()V");                   // 6
            utf8(output, "Code");                  // 7
            nameAndType(output, 5, 6);             // 8
            methodRef(output, 4, 8);               // 9
            output.writeShort(ACC_PUBLIC | ACC_FINAL | ACC_SUPER);
            output.writeShort(2);
            output.writeShort(4);
            output.writeShort(0);
            output.writeShort(0);
            output.writeShort(1);
            output.writeShort(ACC_PUBLIC);
            output.writeShort(5);
            output.writeShort(6);
            output.writeShort(1);
            output.writeShort(7);
            output.writeInt(17);
            output.writeShort(1);
            output.writeShort(1);
            output.writeInt(5);
            output.writeByte(0x2a);
            output.writeByte(0xb7);
            output.writeShort(9);
            output.writeByte(0xb1);
            output.writeShort(0);
            output.writeShort(0);
            output.writeShort(0);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot generate Kether interoperability class",
                    impossible);
        }
    }

    static byte[] rewriteMain(byte[] yamlBytes, String generatedMain) {
        String yaml = new String(yamlBytes, StandardCharsets.UTF_8);
        String[] lines = yaml.split("\\n", -1);
        int matches = 0;
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].matches("^main\\s*:.*$")) {
                lines[index] = "main: '" + generatedMain.replace("'", "''") + "'";
                matches++;
            }
        }
        if (matches != 1) {
            throw new GradleException("Expected exactly one top-level main entry in plugin.yml");
        }
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    private static String classPath(String className) {
        return className.replace('.', '/') + ".class";
    }

    private static void utf8(DataOutputStream output, String value) throws IOException {
        output.writeByte(1);
        output.writeUTF(value);
    }

    private static void classInfo(DataOutputStream output, int nameIndex) throws IOException {
        output.writeByte(7);
        output.writeShort(nameIndex);
    }

    private static void nameAndType(DataOutputStream output, int name, int descriptor)
            throws IOException {
        output.writeByte(12);
        output.writeShort(name);
        output.writeShort(descriptor);
    }

    private static void methodRef(DataOutputStream output, int owner, int nameAndType)
            throws IOException {
        output.writeByte(10);
        output.writeShort(owner);
        output.writeShort(nameAndType);
    }

    private static final class ClassFile {
        private final byte[] source;
        private final String[] utf8;
        private final int accessOffset;
        private final int methodsOffset;

        private ClassFile(byte[] source, String[] utf8, int accessOffset, int methodsOffset) {
            this.source = source;
            this.utf8 = utf8;
            this.accessOffset = accessOffset;
            this.methodsOffset = methodsOffset;
        }

        static ClassFile read(byte[] source) throws IOException {
            ByteArrayInputStream bytes = new ByteArrayInputStream(source);
            DataInputStream input = new DataInputStream(bytes);
            if (input.readInt() != CLASS_MAGIC) {
                throw new IOException("Invalid class file");
            }
            input.readUnsignedShort();
            input.readUnsignedShort();
            int poolCount = input.readUnsignedShort();
            String[] utf8 = new String[poolCount];
            for (int index = 1; index < poolCount; index++) {
                int tag = input.readUnsignedByte();
                if (tag == 1) {
                    utf8[index] = input.readUTF();
                } else if (tag == 3 || tag == 4) {
                    skip(input, 4);
                } else if (tag == 5 || tag == 6) {
                    skip(input, 8);
                    index++;
                } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                    skip(input, 2);
                } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12
                        || tag == 17 || tag == 18) {
                    skip(input, 4);
                } else if (tag == 15) {
                    skip(input, 3);
                } else {
                    throw new IOException("Unsupported class constant-pool tag: " + tag);
                }
            }
            int accessOffset = source.length - bytes.available();
            input.readUnsignedShort();
            input.readUnsignedShort();
            input.readUnsignedShort();
            int interfaces = input.readUnsignedShort();
            skip(input, interfaces * 2);
            int fields = input.readUnsignedShort();
            skipMembers(input, fields);
            int methodsOffset = source.length - bytes.available();
            return new ClassFile(source, utf8, accessOffset, methodsOffset);
        }

        int accessFlags() {
            return unsignedShort(source, accessOffset);
        }

        boolean hasAccessibleNoArgConstructor() throws IOException {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(
                    source, methodsOffset, source.length - methodsOffset));
            int methods = input.readUnsignedShort();
            for (int index = 0; index < methods; index++) {
                int access = input.readUnsignedShort();
                int name = input.readUnsignedShort();
                int descriptor = input.readUnsignedShort();
                int attributes = input.readUnsignedShort();
                boolean constructor = "<init>".equals(utf8[name])
                        && "()V".equals(utf8[descriptor]);
                skipAttributes(input, attributes);
                if (constructor && (access & (ACC_PUBLIC | ACC_PROTECTED)) != 0) {
                    return true;
                }
            }
            return false;
        }

        private static void skipMembers(DataInputStream input, int members) throws IOException {
            for (int index = 0; index < members; index++) {
                input.readUnsignedShort();
                input.readUnsignedShort();
                input.readUnsignedShort();
                skipAttributes(input, input.readUnsignedShort());
            }
        }

        private static void skipAttributes(DataInputStream input, int attributes)
                throws IOException {
            for (int index = 0; index < attributes; index++) {
                input.readUnsignedShort();
                long length = Integer.toUnsignedLong(input.readInt());
                if (length > Integer.MAX_VALUE) {
                    throw new IOException("Class attribute is too large");
                }
                skip(input, (int) length);
            }
        }

        private static void skip(DataInputStream input, int length) throws IOException {
            int remaining = length;
            while (remaining > 0) {
                int skipped = input.skipBytes(remaining);
                if (skipped <= 0) {
                    throw new IOException("Unexpected end of class file");
                }
                remaining -= skipped;
            }
        }

        private static int unsignedShort(byte[] source, int offset) {
            return (source[offset] & 0xff) << 8 | source[offset + 1] & 0xff;
        }
    }
}
