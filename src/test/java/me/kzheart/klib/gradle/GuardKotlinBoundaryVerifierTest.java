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
