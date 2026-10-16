package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * kotlin-reflect 2.4.21 内建包根从单段变成多段时所需的精确适配。
 * 仅接受官方发行版的 SHA-256、方法签名和唯一调用点；其他版本或形态拒绝，不改用户类。
 */
final class KotlinReflectRelocator {
    private static final String PREFIX = "kotlin/reflect/jvm/internal/impl/";
    private static final String STANDARD_NAMES = PREFIX + "builtins/StandardNames";
    private static final String FINDER = PREFIX + "descriptors/runtime/components/ReflectKotlinClassFinder";
    private static final String FQ_NAME = PREFIX + "name/FqName";
    private static final String NAME = PREFIX + "name/Name";
    private static final String HELPER = "klib$isRelocatedBuiltInPackage";
    private static final Map<String, Adaptation> ADAPTATIONS = adaptations();

    private KotlinReflectRelocator() {
    }

    /** 在通用 ASM class remapping 前调用；生成的类型和字符串常量仍使用原 kotlin 包。 */
    static byte[] prepare(byte[] source, Map<String, String> relocations, List<String> protectedPrefixes) {
        if ("kotlin".equals(ClassRelocator.relocateInternalName("kotlin", relocations, protectedPrefixes))) return source;
        ClassReader reader = new ClassReader(source);
        final String owner = reader.getClassName();
        final Adaptation adaptation = ADAPTATIONS.get(owner);
        if (adaptation == null) return source;
        if (owner.equals(ClassRelocator.relocateInternalName(owner, relocations, protectedPrefixes))) return source;
        if (!adaptation.sha256.equals(sha256(source))) {
            throw incompatible("unverified original class bytes for " + owner);
        }
        final boolean standardNames = STANDARD_NAMES.equals(owner);
        final int[] matches = {0};
        final int[] rootReads = {0};
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                        String signature, String[] exceptions) {
                if (HELPER.equals(name)) throw incompatible("helper method already exists");
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                boolean target = adaptation.method.equals(name) && adaptation.descriptor.equals(descriptor);
                if (!target) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public void visitFieldInsn(int opcode, String fieldOwner, String fieldName, String fieldDescriptor) {
                        if (opcode == Opcodes.GETSTATIC && STANDARD_NAMES.equals(fieldOwner)
                                && "BUILT_INS_PACKAGE_NAME".equals(fieldName) && ("L" + NAME + ";").equals(fieldDescriptor)) {
                            rootReads[0]++;
                        }
                        super.visitFieldInsn(opcode, fieldOwner, fieldName, fieldDescriptor);
                    }

                    @Override public void visitMethodInsn(int opcode, String methodOwner, String methodName,
                                                          String methodDescriptor, boolean isInterface) {
                        if (standardNames && opcode == Opcodes.INVOKEVIRTUAL
                                && (FQ_NAME + "$Companion").equals(methodOwner) && "topLevel".equals(methodName)
                                && ("(L" + NAME + ";)L" + FQ_NAME + ";").equals(methodDescriptor)) {
                            // topLevel(Name) 缓存一个单段父链，多段隔离包必须通过 String 构造器解析。
                            matches[0]++;
                            super.visitInsn(Opcodes.POP2);
                            super.visitTypeInsn(Opcodes.NEW, FQ_NAME);
                            super.visitInsn(Opcodes.DUP);
                            super.visitLdcInsn("kotlin");
                            super.visitMethodInsn(Opcodes.INVOKESPECIAL, FQ_NAME, "<init>", "(Ljava/lang/String;)V", false);
                        } else if (!standardNames && opcode == Opcodes.INVOKEVIRTUAL && adaptation.receiver.equals(methodOwner)
                                && "startsWith".equals(methodName) && ("(L" + NAME + ";)Z").equals(methodDescriptor)) {
                            // 原调用的 FqName/Name 两个栈值恰好作为静态 helper 参数。
                            matches[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, HELPER,
                                    "(L" + adaptation.receiver + ";L" + NAME + ";)Z", false);
                        } else {
                            super.visitMethodInsn(opcode, methodOwner, methodName, methodDescriptor, isInterface);
                        }
                    }
                };
            }

            @Override public void visitEnd() {
                if (matches[0] != 1 || rootReads[0] != 1) {
                    throw incompatible("expected one verified call site and root reference in " + owner
                            + ", found " + matches[0] + "/" + rootReads[0]);
                }
                if (!standardNames) writePackageTest(writer, adaptation.receiver);
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void writePackageTest(ClassWriter writer, String receiver) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                HELPER, "(L" + receiver + ";L" + NAME + ";)Z", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, receiver, "asString", "()Ljava/lang/String;", false);
        method.visitVarInsn(Opcodes.ASTORE, 2);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitLdcInsn("kotlin");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
        Label subpackage = new Label();
        method.visitJumpInsn(Opcodes.IFEQ, subpackage);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(subpackage);
        method.visitFrame(Opcodes.F_APPEND, 1, new Object[]{"java/lang/String"}, 0, null);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitLdcInsn("kotlin.");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(2, 3);
        method.visitEnd();
    }

    private static GradleException incompatible(String reason) {
        return new GradleException("Unsupported kotlin-reflect builtins relocation shape: " + reason
                + ". The verified runtime is kotlin-reflect 2.4.21.");
    }

    private static Map<String, Adaptation> adaptations() {
        Map<String, Adaptation> result = new LinkedHashMap<String, Adaptation>();
        result.put(STANDARD_NAMES, new Adaptation("<clinit>", "()V", FQ_NAME,
                "3a88b863af350e0ba2b414becf9259b97da065d1064b45ac198e24107650e0ff"));
        result.put(FINDER, new Adaptation("findBuiltInsData", "(L" + FQ_NAME + ";)Ljava/io/InputStream;", FQ_NAME,
                "27a402a5e76e3d1a2d544a5a448bc881bb0bafc7a1830d0aa2ac4f10bccebdad"));
        result.put("kotlin/reflect/jvm/internal/ConvertFromJavaKt", new Adaptation("getPurelyImplementedSupertype",
                "(Lkotlin/reflect/jvm/internal/KClassImpl;)Lkotlin/reflect/KType;", FQ_NAME,
                "03fe225de20ec3bead5472eda0fc5e84568c60580379f57bb6357bdcf40733a6"));
        result.put(PREFIX + "builtins/FunctionTypesKt", new Adaptation("isNumberedFunctionClassFqName",
                "(L" + FQ_NAME + "Unsafe;)Z", FQ_NAME + "Unsafe",
                "0cf6acf776531c7e40a18459c31baaae7fa2fd5b87c4faaa75cc0dca0319a184"));
        result.put(PREFIX + "builtins/KotlinBuiltIns", new Adaptation("isUnderKotlinPackage",
                "(L" + PREFIX + "descriptors/DeclarationDescriptor;)Z", FQ_NAME,
                "fb3e8130ecec70b05cc1f85c8d5c10d7f559daa067c66bb90586cb736eb91899"));
        result.put(PREFIX + "load/java/lazy/descriptors/LazyJavaClassDescriptor$LazyJavaClassTypeConstructor",
                new Adaptation("getPurelyImplementedSupertype", "()L" + PREFIX + "types/KotlinType;", FQ_NAME,
                        "da4d0fca188a5dc5b68238dd57d14cc004b3e1ca2f88a80f5bd8a718a0708d0c"));
        return result;
    }

    private static String sha256(byte[] source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new GradleException("SHA-256 is required to verify kotlin-reflect relocation", failure);
        }
    }

    private static final class Adaptation {
        final String method;
        final String descriptor;
        final String receiver;
        final String sha256;

        Adaptation(String method, String descriptor, String receiver, String sha256) {
            this.method = method;
            this.descriptor = descriptor;
            this.receiver = receiver;
            this.sha256 = sha256;
        }
    }
}
