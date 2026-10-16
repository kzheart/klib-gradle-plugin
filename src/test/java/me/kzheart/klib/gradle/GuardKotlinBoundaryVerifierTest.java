package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GuardKotlinBoundaryVerifierTest {
    private static final String KOTLIN_METHOD = "(Lkotlin/jvm/functions/Function0;)Lkotlin/Unit;";

    @Test void rejectsParentMethodFieldArrayAndMethodHandleBoundaries() {
        assertRejected(call("me/kzheart/klib/scope/Scope", KOTLIN_METHOD, false), "Scope.invoke");
        assertRejected(call("me/kzheart/klib/config/api/Host", KOTLIN_METHOD, false), "Host.invoke");
        assertRejected(call("me/kzheart/klib/scope/Scope", "()[Lkotlin/Unit;", false), "Scope.invoke");
        assertRejected(call("me/kzheart/klib/scope/Scope", KOTLIN_METHOD, true), "Scope.invoke");
        ClassWriter writer = base();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, "me/kzheart/klib/scope/Scope", "job", "Lkotlinx/coroutines/Job;");
        finish(method);
        assertRejected(writer.toByteArray(), "Scope.job");
    }

    @Test void permitsJavaHostCallsPrivateKlibCallsInternalKotlinAndExternalRuntime() {
        verify(call("me/kzheart/klib/scope/Scope", "(Ljava/lang/Runnable;)V", false), rules());
        verify(call("me/kzheart/klib/config/PrivateConfig", KOTLIN_METHOD, false), rules());
        verify(call("kotlin/collections/CollectionsKt", KOTLIN_METHOD, false), rules());
        verify(call("me/kzheart/klib/scope/Scope", KOTLIN_METHOD, false), Collections.<String,String>emptyMap());
    }

    @Test void rejectsAnOverrideWithoutAnyHostInvocation() {
        byte[] host = declaration("me/kzheart/klib/scope/Host", "java/lang/Object", null,
                Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "accept", KOTLIN_METHOD);
        byte[] consumer = declaration("example/Consumer", "me/kzheart/klib/scope/Host", null,
                Opcodes.ACC_PROTECTED, "accept", KOTLIN_METHOD);
        assertInheritedRejected(consumer, "Host.accept", host);
    }

    @Test void followsLocalSuperclassAndTransitiveInterfaceDeclarations() {
        byte[] host = declaration("me/kzheart/klib/scope/Host", "java/lang/Object", null,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "accept", KOTLIN_METHOD);
        byte[] local = declaration("example/Base", "me/kzheart/klib/scope/Host", null, 0, null, null);
        byte[] consumer = declaration("example/Consumer", "example/Base", null,
                Opcodes.ACC_PUBLIC, "accept", KOTLIN_METHOD);
        assertInheritedRejected(consumer, "Host.accept", host, local);

        byte[] contract = interfaceDeclaration("me/kzheart/klib/scope/Contract", null,
                "accept", "(Lkotlinx/coroutines/Job;)[Lkotlin/Unit;");
        byte[] childContract = interfaceDeclaration("example/ChildContract",
                new String[]{"me/kzheart/klib/scope/Contract"}, null, null);
        byte[] bridge = declaration("example/Base", "java/lang/Object",
                new String[]{"example/ChildContract"}, 0, null, null);
        byte[] implementation = declaration("example/Consumer", "example/Base", null,
                Opcodes.ACC_PUBLIC, "accept", "(Lkotlinx/coroutines/Job;)[Lkotlin/Unit;");
        assertInheritedRejected(implementation, "Contract.accept", contract, childContract, bridge);
    }

    @Test void rejectsLocallyInheritedImplementationOfAHostInterface() {
        byte[] contract = interfaceDeclaration("me/kzheart/klib/scope/Contract", null, "accept", KOTLIN_METHOD);
        byte[] local = declaration("example/Base", "java/lang/Object", null,
                Opcodes.ACC_PUBLIC, "accept", KOTLIN_METHOD);
        byte[] consumer = declaration("example/Consumer", "example/Base",
                new String[]{"me/kzheart/klib/scope/Contract"}, 0, null, null);
        assertInheritedRejected(consumer, "Contract.accept", contract, local);
    }

    @Test void permitsUnrelatedInternalMethodsAndExactDescriptorMismatches() {
        byte[] host = declaration("me/kzheart/klib/scope/Host", "java/lang/Object", null,
                Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "setup", "(Ljava/lang/Runnable;)V");
        verifyInherited(declaration("example/Consumer", "me/kzheart/klib/scope/Host", null,
                Opcodes.ACC_PUBLIC, "internalOnly", KOTLIN_METHOD), rules(), host);
        verifyInherited(declaration("example/Consumer", "me/kzheart/klib/scope/Host", null,
                Opcodes.ACC_PUBLIC, "setup", KOTLIN_METHOD), rules(), host);
        byte[] kotlinHost = declaration("me/kzheart/klib/scope/Host", "java/lang/Object", null,
                Opcodes.ACC_PUBLIC, "accept", KOTLIN_METHOD);
        verifyInherited(declaration("example/Consumer", "me/kzheart/klib/scope/Host", null,
                Opcodes.ACC_PUBLIC, "other", KOTLIN_METHOD), rules(), kotlinHost);
        verifyInherited(declaration("example/Consumer", "me/kzheart/klib/scope/Host", null,
                0, null, null), rules(), kotlinHost);
    }

    @Test void permitsStaticPrivateConstructorsAndInaccessibleOrFinalParentMethods() {
        byte[] host = declaration("me/kzheart/klib/scope/Host", "java/lang/Object", null,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "accept", KOTLIN_METHOD);
        for (int access : new int[]{Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, Opcodes.ACC_PRIVATE}) {
            verifyInherited(declaration("example/Consumer", "me/kzheart/klib/scope/Host", null,
                    access, "accept", KOTLIN_METHOD), rules(), host);
        }
        for (int access : new int[]{Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, Opcodes.ACC_PRIVATE,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, 0}) {
            byte[] nonOverridable = declaration("me/kzheart/klib/scope/Host", "java/lang/Object", null,
                    access, "accept", KOTLIN_METHOD);
            verifyInherited(declaration("example/Consumer", "me/kzheart/klib/scope/Host", null,
                    Opcodes.ACC_PUBLIC, "accept", KOTLIN_METHOD), rules(), nonOverridable);
        }
        byte[] constructor = declaration("me/kzheart/klib/scope/Host", "java/lang/Object", null,
                Opcodes.ACC_PUBLIC, "<init>", "(Lkotlin/Unit;)V");
        verifyInherited(declaration("example/Consumer", "me/kzheart/klib/scope/Host", null,
                Opcodes.ACC_PUBLIC, "<init>", "(Lkotlin/Unit;)V"), rules(), constructor);
    }

    @Test void permitsRelocatedHostClassesAndUnrelocatedKotlinSignatures() {
        byte[] privateHost = declaration("me/kzheart/klib/config/PrivateHost", "java/lang/Object", null,
                Opcodes.ACC_PUBLIC, "accept", KOTLIN_METHOD);
        byte[] consumer = declaration("example/Consumer", "me/kzheart/klib/config/PrivateHost", null,
                Opcodes.ACC_PUBLIC, "accept", KOTLIN_METHOD);
        verifyInherited(consumer, rules(), privateHost);
        byte[] host = declaration("me/kzheart/klib/config/api/Host", "java/lang/Object", null,
                Opcodes.ACC_PUBLIC, "accept", KOTLIN_METHOD);
        byte[] protectedConsumer = declaration("example/Consumer", "me/kzheart/klib/config/api/Host", null,
                Opcodes.ACC_PUBLIC, "accept", KOTLIN_METHOD);
        assertInheritedRejected(protectedConsumer, "Host.accept", host);
        verifyInherited(protectedConsumer, Collections.<String, String>emptyMap(), host);
    }

    @Test void resolvesInheritedVirtualStaticAndHandleCallsThroughAProductOwner() {
        for (int opcode : new int[]{Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESTATIC}) {
            int access = Opcodes.ACC_PUBLIC | (opcode == Opcodes.INVOKESTATIC ? Opcodes.ACC_STATIC : 0);
            byte[] host = declaration("me/kzheart/klib/scope/Host", "java/lang/Object", null,
                    access, "accept", KOTLIN_METHOD);
            assertResolvedRejected(memberReference("me/kzheart/klib/scope/Host", null,
                    opcode, false, false, false), "Host.accept", host);
            assertResolvedRejected(memberReference("me/kzheart/klib/scope/Host", null,
                    opcode, false, false, true), "Host.accept", host);
        }
        byte[] contract = interfaceDeclaration("me/kzheart/klib/scope/Contract", null, "accept", KOTLIN_METHOD);
        byte[] local = interfaceDeclaration("example/LocalContract",
                new String[]{"me/kzheart/klib/scope/Contract"}, null, null);
        assertResolvedRejected(memberReference("java/lang/Object", new String[]{"example/LocalContract"},
                Opcodes.INVOKEVIRTUAL, false, false, false), "Contract.accept", contract, local);
    }

    @Test void permitsLocalAndIntermediateStaticHidingWithAnExactDescriptor() {
        byte[] host = declaration("me/kzheart/klib/scope/Host", "java/lang/Object", null,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "accept", KOTLIN_METHOD);
        verifyInherited(memberReference("me/kzheart/klib/scope/Host", null,
                Opcodes.INVOKESTATIC, false, true, false), rules(), host);
        byte[] intermediate = declaration("example/Base", "me/kzheart/klib/scope/Host", null,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "accept", KOTLIN_METHOD);
        verifyInherited(memberReference("example/Base", null,
                Opcodes.INVOKESTATIC, false, false, false), rules(), host, intermediate);
        byte[] overload = declaration("example/Base", "me/kzheart/klib/scope/Host", null,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "accept", "(Lkotlin/Unit;)V");
        assertResolvedRejected(memberReference("example/Base", null,
                Opcodes.INVOKESTATIC, false, false, false), "Host.accept", host, overload);
    }

    @Test void resolvesInheritedFieldsAndPermitsLocalShadowing() {
        byte[] host = fieldDeclaration("me/kzheart/klib/scope/Host", "java/lang/Object", null);
        assertResolvedRejected(memberReference("me/kzheart/klib/scope/Host", null,
                Opcodes.GETSTATIC, true, false, false), "Host.job", host);
        assertResolvedRejected(memberReference("me/kzheart/klib/scope/Host", null,
                Opcodes.GETSTATIC, true, false, true), "Host.job", host);
        verifyInherited(memberReference("me/kzheart/klib/scope/Host", null,
                Opcodes.GETSTATIC, true, true, false), rules(), host);
        byte[] intermediate = fieldDeclaration("example/Base", "me/kzheart/klib/scope/Host", null);
        verifyInherited(memberReference("example/Base", null,
                Opcodes.GETSTATIC, true, false, false), rules(), host, intermediate);
    }

    @Test void resolvesInterfaceFieldsBeforeSuperclassFields() {
        byte[] host = fieldDeclaration("me/kzheart/klib/scope/Host", "java/lang/Object", null);
        byte[] contract = fieldDeclaration("example/LocalFields", "java/lang/Object", null);
        verifyInherited(memberReference("me/kzheart/klib/scope/Host", new String[]{"example/LocalFields"},
                Opcodes.GETSTATIC, true, false, false), rules(), host, contract);
    }

    private static void assertResolvedRejected(byte[] consumer, String member, byte[]... classes) {
        GradleException failure = assertThrows(GradleException.class,
                () -> verifyInherited(consumer, rules(), classes));
        assertTrue(failure.getMessage().contains("Unsupported Guard Kotlin parent boundary"), failure.getMessage());
        assertTrue(failure.getMessage().contains(" calls "), failure.getMessage());
        assertTrue(failure.getMessage().contains(member), failure.getMessage());
    }

    private static byte[] memberReference(String parent, String[] interfaces, int opcode,
                                          boolean field, boolean localDeclaration, boolean handle) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "example/Consumer", null, parent, interfaces);
        String name = field ? "job" : "accept";
        String descriptor = field ? "Lkotlinx/coroutines/Job;" : KOTLIN_METHOD;
        if (localDeclaration) {
            if (field) writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    name, descriptor, null, null).visitEnd();
            else writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    name, descriptor, null, null).visitEnd();
        }
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        if (handle) {
            int tag = field ? Opcodes.H_GETSTATIC
                    : opcode == Opcodes.INVOKESTATIC ? Opcodes.H_INVOKESTATIC : Opcodes.H_INVOKEVIRTUAL;
            method.visitLdcInsn(new Handle(tag, "example/Consumer", name, descriptor, false));
        } else if (field) {
            method.visitFieldInsn(opcode, "example/Consumer", name, descriptor);
        } else {
            method.visitMethodInsn(opcode, "example/Consumer", name, descriptor, false);
        }
        finish(method);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] fieldDeclaration(String name, String parent, String[] interfaces) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                name, null, parent, interfaces);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "job", "Lkotlinx/coroutines/Job;", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void assertInheritedRejected(byte[] consumer, String member, byte[]... classes) {
        GradleException failure = assertThrows(GradleException.class,
                () -> verifyInherited(consumer, rules(), classes));
        assertTrue(failure.getMessage().contains("Unsupported Guard Kotlin parent boundary"), failure.getMessage());
        assertTrue(failure.getMessage().contains("overrides"), failure.getMessage());
        assertTrue(failure.getMessage().contains(member), failure.getMessage());
    }

    private static void verifyInherited(byte[] consumer, Map<String, String> relocations, byte[]... classes) {
        GuardKotlinBoundaryVerifier.Hierarchy hierarchy = new GuardKotlinBoundaryVerifier.Hierarchy();
        for (byte[] bytes : classes) {
            hierarchy.add(bytes, new org.objectweb.asm.ClassReader(bytes).getClassName().startsWith("example/"));
        }
        GuardKotlinBoundaryVerifier.verify(consumer, relocations,
                Arrays.asList("me.kzheart.klib.config.api"), hierarchy);
    }

    private static byte[] declaration(String name, String parent, String[] interfaces,
                                      int access, String method, String descriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null, parent, interfaces);
        if (method != null) writer.visitMethod(access, method, descriptor, null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] interfaceDeclaration(String name, String[] interfaces, String method, String descriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                name, null, "java/lang/Object", interfaces);
        if (method != null) writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                method, descriptor, null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void assertRejected(byte[] bytes, String member) {
        GradleException failure = assertThrows(GradleException.class, () -> verify(bytes, rules()));
        assertTrue(failure.getMessage().contains("Unsupported Guard Kotlin parent boundary"));
        assertTrue(failure.getMessage().contains(member), failure.getMessage());
    }
    private static void verify(byte[] bytes, Map<String,String> relocations) {
        GuardKotlinBoundaryVerifier.verify(bytes, relocations, Arrays.asList("me.kzheart.klib.config.api"));
    }
    private static Map<String,String> rules() {
        Map<String,String> rules = new LinkedHashMap<>();
        rules.put("kotlin", "example.libs.kotlin");
        rules.put("kotlinx.coroutines", "example.libs.kotlinx.coroutines");
        rules.put("me.kzheart.klib.config", "example.libs.klib.config");
        return rules;
    }
    private static byte[] call(String owner, String descriptor, boolean handle) {
        ClassWriter writer = base();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        if (handle) method.visitLdcInsn(new Handle(Opcodes.H_INVOKESTATIC, owner, "invoke", descriptor, false));
        else method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "invoke", descriptor, false);
        finish(method);
        return writer.toByteArray();
    }
    private static ClassWriter base() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "example/Consumer", null, "java/lang/Object", null);
        return writer;
    }
    private static void finish(MethodVisitor method) {
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(4, 0);
        method.visitEnd();
    }
}
