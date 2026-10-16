package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Rejects host calls and overrides that would change the parent-owned JVM signature. */
final class GuardKotlinBoundaryVerifier {
    private GuardKotlinBoundaryVerifier() { }

    static void verify(byte[] bytes, Map<String, String> relocations, List<String> protectedPrefixes) {
        verify(bytes, relocations, protectedPrefixes, new Hierarchy());
    }

    static void verify(byte[] bytes, Map<String, String> relocations,
                       List<String> protectedPrefixes, Hierarchy hierarchy) {
        ClassReader reader = new ClassReader(bytes);
        hierarchy.add(bytes, true);
        ClassInfo current = ClassInfo.read(reader);
        List<MethodInfo> methods = new ArrayList<MethodInfo>(current.methods.values());
        hierarchy.collectRelocatedMethods(current, current.name, methods, new HashSet<String>());
        for (MethodInfo method : methods) {
            if (!isVirtual(method.access, method.name)
                    || !containsPrivateKotlin(Type.getMethodType(method.descriptor), relocations, protectedPrefixes)) {
                continue;
            }
            String owner = hierarchy.parentDeclaration(current, current.name, method,
                    relocations, protectedPrefixes, new HashSet<String>());
            if (owner != null) {
                throw boundaryFailure(current.name, method.name, " overrides ", owner,
                        method.name, method.descriptor);
            }
        }
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String member,
                                                          String desc, boolean isInterface) {
                        check(owner, member, desc);
                    }
                    @Override public void visitFieldInsn(int opcode, String owner, String member, String desc) {
                        check(owner, member, desc);
                    }
                    @Override public void visitLdcInsn(Object value) { checkConstant(value); }
                    @Override public void visitInvokeDynamicInsn(String member, String desc,
                                                                  Handle bootstrap, Object... arguments) {
                        checkConstant(bootstrap);
                        for (Object argument : arguments) checkConstant(argument);
                    }
                    private void checkConstant(Object value) {
                        if (value instanceof Handle) {
                            Handle handle = (Handle) value;
                            check(handle.getOwner(), handle.getName(), handle.getDesc());
                        } else if (value instanceof ConstantDynamic) {
                            ConstantDynamic dynamic = (ConstantDynamic) value;
                            checkConstant(dynamic.getBootstrapMethod());
                            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                                checkConstant(dynamic.getBootstrapMethodArgument(i));
                            }
                        }
                    }
                    private void check(String owner, String member, String desc) {
                        Type type = desc.startsWith("(") ? Type.getMethodType(desc) : Type.getType(desc);
                        if (!containsPrivateKotlin(type, relocations, protectedPrefixes)) return;
                        List<String> declarations = hierarchy.declarationOwners(owner, member, desc);
                        // Preserve direct-owner checks when external class definitions are unavailable.
                        if (declarations.isEmpty()) declarations = Collections.singletonList(owner);
                        for (String declaration : declarations) {
                            if (isParentOwned(declaration, relocations, protectedPrefixes)) {
                                throw boundaryFailure(reader.getClassName(), name, " calls ", declaration, member, desc);
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static GradleException boundaryFailure(String consumer, String method, String action,
                                                   String owner, String member, String descriptor) {
        return new GradleException("Unsupported Guard Kotlin parent boundary: "
                + consumer.replace('/', '.') + "." + method + action
                + owner.replace('/', '.') + "." + member + descriptor
                + ". This parent-owned Klib API exposes Kotlin types that would be "
                + "relocated into the product's private runtime. Use a Java-only host API "
                + "signature or an ordinary Bukkit plugin until the Guard host contract "
                + "supports this boundary; internal Kotlin/stdlib use remains supported.");
    }

    private static boolean isParentOwned(String owner, Map<String, String> relocations,
                                          List<String> protectedPrefixes) {
        return owner.startsWith("me/kzheart/klib/") && owner.equals(
                ClassRelocator.relocateInternalName(owner, relocations, protectedPrefixes));
    }

    private static boolean isVirtual(int access, String name) {
        return (access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0 && !name.startsWith("<");
    }

    private static boolean accessible(int access, String owner, String consumer) {
        return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                || owner.substring(0, owner.lastIndexOf('/') + 1)
                        .equals(consumer.substring(0, consumer.lastIndexOf('/') + 1));
    }

    /** Class headers and declarations only: never resolve or execute user/host classes in this JVM. */
    static final class Hierarchy {
        private final Map<String, ClassInfo> classes = new LinkedHashMap<String, ClassInfo>();
        private final Set<String> relocatedClasses = new HashSet<String>();
        private final List<ClasspathEntry> classpath = new ArrayList<ClasspathEntry>();
        private final Set<String> missing = new HashSet<String>();
        private long bytes;
        private int entries;

        static Hierarchy fromClasspath(File base, Iterable<File> libraries, Iterable<File> classpath) {
            Hierarchy hierarchy = new Hierarchy();
            // Product definitions win over duplicate compile-classpath entries.
            Set<File> seen = new HashSet<File>();
            hierarchy.addClasspath(base, true, seen);
            for (File library : libraries) hierarchy.addClasspath(library, true, seen);
            for (File entry : classpath) hierarchy.addClasspath(entry, false, seen);
            return hierarchy;
        }

        void add(byte[] content, boolean relocated) {
            ClassInfo info = ClassInfo.read(new ClassReader(content));
            if (!classes.containsKey(info.name)) {
                classes.put(info.name, info);
                if (relocated) relocatedClasses.add(info.name);
            }
        }

        private void addClasspath(File entry, boolean relocated, Set<File> seen) {
            if (seen.add(entry)) classpath.add(new ClasspathEntry(entry, relocated));
        }

        private ClassInfo find(String name) {
            ClassInfo cached = classes.get(name);
            if (cached != null || missing.contains(name)) return cached;
            String classFile = name + ".class";
            for (ClasspathEntry entry : classpath) {
                try {
                    if (entry.file.isDirectory()) {
                        Path root = entry.file.toPath().toAbsolutePath().normalize();
                        Path path = root.resolve(classFile).normalize();
                        if (!path.startsWith(root)) {
                            throw new IOException("Invalid Guard boundary class name: " + name);
                        }
                        if (Files.isRegularFile(path)) {
                            try (InputStream input = Files.newInputStream(path)) {
                                return readClass(input, name, entry.relocated);
                            }
                        }
                    } else if (entry.file.isFile() && entry.file.getName().endsWith(".jar")) {
                        try (ZipFile zip = new ZipFile(entry.file)) {
                            // Java 8 consumers use base definitions, not META-INF/versions entries.
                            ZipEntry item = zip.getEntry(classFile);
                            if (item != null && !item.isDirectory()) {
                                try (InputStream input = zip.getInputStream(item)) {
                                    return readClass(input, name, entry.relocated);
                                }
                            }
                        }
                    }
                } catch (IOException failure) {
                    throw new GradleException("Cannot inspect Guard boundary classpath " + entry.file, failure);
                }
            }
            missing.add(name);
            return null;
        }

        private ClassInfo readClass(InputStream input, String name, boolean relocated) throws IOException {
            if (++entries > KlibShadeJarTask.MAX_ENTRIES) {
                throw new IOException("Guard boundary classpath contains too many classes at " + name);
            }
            byte[] content = KlibShadeJarTask.readBounded(input, KlibShadeJarTask.MAX_TOTAL_BYTES - bytes, name);
            bytes += content.length;
            add(content, relocated);
            ClassInfo info = classes.get(name);
            if (info == null) throw new IOException("Mismatched Guard boundary class name: " + name);
            return info;
        }

        private static final class ClasspathEntry {
            private final File file;
            private final boolean relocated;

            private ClasspathEntry(File file, boolean relocated) {
                this.file = file;
                this.relocated = relocated;
            }
        }

        private List<String> declarationOwners(String owner, String name, String descriptor) {
            if (!descriptor.startsWith("(")) {
                String declaration = fieldDeclaration(owner, name + descriptor, new HashSet<String>());
                return declaration == null ? Collections.<String>emptyList() : Collections.singletonList(declaration);
            }
            String key = name + descriptor;
            List<String> interfaces = new ArrayList<String>();
            Set<String> visited = new HashSet<String>();
            String current = owner;
            while (current != null && visited.add(current)) {
                ClassInfo info = find(current);
                if (info == null) break;
                if (info.methods.containsKey(key)) return Collections.singletonList(current);
                interfaces.addAll(info.interfaces);
                current = info.superName;
            }
            // Class declarations, including inherited static methods, take precedence over interfaces.
            Set<String> candidates = new LinkedHashSet<String>();
            visited.clear();
            for (String contract : interfaces) interfaceDeclarations(contract, key, candidates, visited);
            List<String> maximal = new ArrayList<String>();
            for (String candidate : candidates) {
                boolean overridden = false;
                for (String other : candidates) {
                    if (!candidate.equals(other) && extendsInterface(other, candidate, new HashSet<String>())) {
                        overridden = true;
                        break;
                    }
                }
                if (!overridden) maximal.add(candidate);
            }
            return maximal;
        }

        private String fieldDeclaration(String owner, String key, Set<String> visited) {
            if (!visited.add(owner)) return null;
            ClassInfo info = find(owner);
            if (info == null) return null;
            if (info.fields.contains(key)) return owner;
            // JVM field resolution searches superinterfaces before the superclass.
            for (String contract : info.interfaces) {
                String declaration = fieldDeclaration(contract, key, visited);
                if (declaration != null) return declaration;
            }
            return info.superName == null ? null : fieldDeclaration(info.superName, key, visited);
        }

        private void interfaceDeclarations(String owner, String key, Set<String> declarations, Set<String> visited) {
            if (!visited.add(owner)) return;
            ClassInfo info = find(owner);
            if (info == null) return;
            MethodInfo method = info.methods.get(key);
            if (method != null && isVirtual(method.access, method.name)) declarations.add(owner);
            for (String parent : info.interfaces) interfaceDeclarations(parent, key, declarations, visited);
        }

        private boolean extendsInterface(String child, String ancestor, Set<String> visited) {
            if (!visited.add(child)) return false;
            ClassInfo info = find(child);
            if (info == null) return false;
            for (String parent : info.interfaces) {
                if (parent.equals(ancestor) || extendsInterface(parent, ancestor, visited)) return true;
            }
            return false;
        }

        private void collectRelocatedMethods(ClassInfo current, String consumer, List<MethodInfo> methods,
                                              Set<String> visited) {
            for (String parent : current.parents) {
                if (!visited.add(parent)) continue;
                ClassInfo ancestor = find(parent);
                if (ancestor == null) continue;
                if (relocatedClasses.contains(parent)) {
                    for (MethodInfo method : ancestor.methods.values()) {
                        if (isVirtual(method.access, method.name) && accessible(method.access, parent, consumer)) {
                            methods.add(method);
                        }
                    }
                }
                collectRelocatedMethods(ancestor, consumer, methods, visited);
            }
        }

        private String parentDeclaration(ClassInfo current, String consumer, MethodInfo method,
                                         Map<String, String> relocations, List<String> protectedPrefixes,
                                         Set<String> visited) {
            for (String parent : current.parents) {
                if (!visited.add(parent)) continue;
                ClassInfo ancestor = find(parent);
                if (ancestor == null) continue;
                MethodInfo declaration = ancestor.methods.get(method.name + method.descriptor);
                if (declaration != null && isVirtual(declaration.access, declaration.name)
                        && (declaration.access & Opcodes.ACC_FINAL) == 0
                        && accessible(declaration.access, parent, consumer)
                        && isParentOwned(parent, relocations, protectedPrefixes)) {
                    return parent;
                }
                String owner = parentDeclaration(ancestor, consumer, method, relocations, protectedPrefixes, visited);
                if (owner != null) return owner;
            }
            return null;
        }
    }

    private static final class ClassInfo {
        private final String name;
        private final String superName;
        private final List<String> interfaces = new ArrayList<String>();
        private final Set<String> fields = new HashSet<String>();
        private final List<String> parents = new ArrayList<String>();
        private final Map<String, MethodInfo> methods = new LinkedHashMap<String, MethodInfo>();

        private ClassInfo(ClassReader reader) {
            name = reader.getClassName();
            superName = reader.getSuperName();
            Collections.addAll(interfaces, reader.getInterfaces());
            if (superName != null) parents.add(superName);
            parents.addAll(interfaces);
        }

        private static ClassInfo read(ClassReader reader) {
            ClassInfo info = new ClassInfo(reader);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public FieldVisitor visitField(int access, String name, String descriptor,
                                                         String signature, Object value) {
                    info.fields.add(name + descriptor);
                    return null;
                }
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    info.methods.put(name + descriptor, new MethodInfo(access, name, descriptor));
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return info;
        }
    }

    private static final class MethodInfo {
        private final int access;
        private final String name;
        private final String descriptor;

        private MethodInfo(int access, String name, String descriptor) {
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    private static boolean containsPrivateKotlin(Type type, Map<String, String> relocations,
                                                 List<String> protectedPrefixes) {
        if (type.getSort() == Type.METHOD) {
            if (containsPrivateKotlin(type.getReturnType(), relocations, protectedPrefixes)) return true;
            for (Type argument : type.getArgumentTypes()) {
                if (containsPrivateKotlin(argument, relocations, protectedPrefixes)) return true;
            }
        } else if (type.getSort() == Type.ARRAY) {
            return containsPrivateKotlin(type.getElementType(), relocations, protectedPrefixes);
        } else if (type.getSort() == Type.OBJECT) {
            String name = type.getInternalName();
            return (name.startsWith("kotlin/") || name.startsWith("kotlinx/coroutines/"))
                    && !name.equals(ClassRelocator.relocateInternalName(name, relocations, protectedPrefixes));
        }
        return false;
    }
}
