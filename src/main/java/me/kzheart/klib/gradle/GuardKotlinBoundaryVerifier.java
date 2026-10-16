package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Map;

/** Rejects statically visible host calls that would change the parent-owned JVM signature. */
final class GuardKotlinBoundaryVerifier {
    private GuardKotlinBoundaryVerifier() { }

    static void verify(byte[] bytes, Map<String, String> relocations, List<String> protectedPrefixes) {
        ClassReader reader = new ClassReader(bytes);
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
                        if (!owner.startsWith("me/kzheart/klib/") || !owner.equals(
                                ClassRelocator.relocateInternalName(owner, relocations, protectedPrefixes))) return;
                        Type type = desc.startsWith("(") ? Type.getMethodType(desc) : Type.getType(desc);
                        if (containsPrivateKotlin(type, relocations, protectedPrefixes)) {
                            throw new GradleException("Unsupported Guard Kotlin parent boundary: "
                                    + reader.getClassName().replace('/', '.') + "." + name + " calls "
                                    + owner.replace('/', '.') + "." + member + desc
                                    + ". This parent-owned Klib API exposes Kotlin types that would be "
                                    + "relocated into the product's private runtime. Use a Java-only host API "
                                    + "signature or an ordinary Bukkit plugin until the Guard host contract "
                                    + "supports this boundary; internal Kotlin/stdlib use remains supported.");
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
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
