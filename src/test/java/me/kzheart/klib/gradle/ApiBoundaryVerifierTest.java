package me.kzheart.klib.gradle;

import kotlin.Metadata;
import kotlin.metadata.KmClass;
import kotlin.metadata.KmClassifier;
import kotlin.metadata.KmType;
import kotlin.metadata.KmTypeAlias;
import kotlin.metadata.jvm.JvmMetadataVersion;
import kotlin.metadata.jvm.KotlinClassMetadata;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("classfile")
class ApiBoundaryVerifierTest {
    private static final String HIDDEN = "example/internal/Hidden";
    private final ApiBoundaryVerifier verifier = new ApiBoundaryVerifier(Collections.singletonList("example.api"), Arrays.asList("org.bukkit", "net.kyori"));
    @Test void inspectsEveryDeclarationAndCodeReference() {
        reject(w -> w.visit(52, Opcodes.ACC_PUBLIC, "example/api/Api", null, HIDDEN, null));
        reject(w -> w.visit(52, Opcodes.ACC_PUBLIC, "example/api/Api", null, "java/lang/Object", new String[] {HIDDEN}));
        reject(w -> w.visitField(1, "v", "L" + HIDDEN + ";", null, null).visitEnd());
        reject(w -> w.visitField(1, "v", "Ljava/util/List;", "Ljava/util/List<L" + HIDDEN + ";>;", null).visitEnd());
        reject(w -> w.visitMethod(1, "v", "()V", null, new String[] {HIDDEN}).visitEnd());
        reject(w -> w.visitMethod(1, "v", "(L" + HIDDEN + ";)V", null, null).visitEnd());
        reject(w -> w.visitAnnotation("L" + HIDDEN + ";", true).visitEnd());
        reject(w -> { AnnotationVisitor a = w.visitAnnotation("Lexample/api/Marker;", true); a.visit("type", Type.getObjectType(HIDDEN)); a.visitEnd(); });
        reject(w -> { AnnotationVisitor a = w.visitAnnotation("Lexample/api/Marker;", true); a.visitEnum("value", "L" + HIDDEN + ";", "ONE"); a.visitEnd(); });
        reject(w -> w.visitRecordComponent("v", "L" + HIDDEN + ";", null).visitEnd());
        reject(w -> code(w).visitTypeInsn(Opcodes.CHECKCAST, HIDDEN));
        reject(w -> code(w).visitFieldInsn(Opcodes.GETSTATIC, HIDDEN, "value", "I"));
        reject(w -> code(w).visitMethodInsn(Opcodes.INVOKESTATIC, HIDDEN, "run", "()V", false));
        reject(w -> code(w).visitLdcInsn(Type.getObjectType(HIDDEN)));
        reject(w -> code(w).visitLdcInsn(new Handle(Opcodes.H_INVOKESTATIC, HIDDEN, "run", "()V", false)));
        reject(w -> code(w).visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "()V", false),
                new Handle(Opcodes.H_INVOKESTATIC, HIDDEN, "run", "()V", false)));
        reject(w -> code(w).visitLdcInsn(new ConstantDynamic("value", "Ljava/lang/Object;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/ConstantBootstraps", "nullConstant", "()V", false),
                new ConstantDynamic("nested", "L" + HIDDEN + ";", new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/ConstantBootstraps", "nullConstant", "()V", false)))));
        reject(w -> code(w).visitTryCatchBlock(new Label(), new Label(), new Label(), HIDDEN));
        reject(w -> code(w).visitLocalVariable("v", "L" + HIDDEN + ";", null, new Label(), new Label(), 0));
        reject(w -> w.visitTypeAnnotation(TypeReference.newSuperTypeReference(-1).getValue(), null, "L" + HIDDEN + ";", true).visitEnd());
    }
    @Test void metadataExceptionDoesNotAllowKotlinImplementationTypes() {
        for (String forbidden : Arrays.asList("kotlin/jvm/internal/Intrinsics", "kotlin/jvm/internal/DefaultConstructorMarker",
                "kotlin/jvm/functions/Function1", "kotlinx/coroutines/Job", "me/kzheart/klib/scope/Scope")) {
            reject(w -> w.visitField(1, "value", "L" + forbidden + ";", null, null).visitEnd());
        }
        assertDoesNotThrow(() -> verifier.verify(bytes(w -> {
            w.visitMethod(1 | Opcodes.ACC_ABSTRACT, "value", "()Ljava/util/List;", "()Ljava/util/List<Ljava/lang/String;>;", null).visitEnd();
            w.visitField(1, "component", "Lnet/kyori/adventure/text/Component;", null, null).visitEnd();
            w.visitAnnotation("Lorg/jetbrains/annotations/NotNull;", false).visitEnd();
        }), "example/api/Api.class"));
    }
    @Test void metadataOnlyAliasesAreCheckedStructurally() {
        KmClass node = new KmClass(); node.setName("example/api/Api");
        KmTypeAlias alias = new KmTypeAlias("Alias"); alias.setUnderlyingType(type(HIDDEN)); alias.setExpandedType(type(HIDDEN)); node.getTypeAliases().add(alias);
        Metadata metadata = new KotlinClassMetadata.Class(node, new JvmMetadataVersion(2, 4, 0), 0).write();
        reject(w -> metadata(w, metadata));
    }
    @Test void markerExceptionIsRestrictedToAnnotationDescriptors() {
        for (String marker : Arrays.asList("kotlin/Metadata", "org/jetbrains/annotations/NotNull", "javax/annotation/Nullable")) {
            reject(w -> w.visitField(1, "value", "L" + marker + ";", null, null).visitEnd());
            reject(w -> w.visitMethod(1025, "value", "()L" + marker + ";", null, null).visitEnd());
            reject(w -> w.visitMethod(1025, "value", "(L" + marker + ";)V", null, null).visitEnd());
            reject(w -> w.visitField(1, "value", "Ljava/util/List;", "Ljava/util/List<L" + marker + ";>;", null).visitEnd());
            reject(w -> code(w).visitMethodInsn(Opcodes.INVOKESTATIC, marker, "value", "()V", false));
            reject(w -> code(w).visitLdcInsn(Type.getObjectType(marker)));
            reject(w -> { AnnotationVisitor a=w.visitAnnotation("Lexample/api/Marker;", true); a.visit("type", Type.getObjectType(marker)); a.visitEnd(); });
            reject(w -> { AnnotationVisitor a=w.visitAnnotation("Lexample/api/Marker;", true); a.visitEnum("value", "L" + marker + ";", "ONE"); a.visitEnd(); });
        }
        KmClass node = new KmClass(); node.setName("example/api/Api"); node.getSupertypes().add(type("kotlin/Any"));
        Metadata m = new KotlinClassMetadata.Class(node, new JvmMetadataVersion(2, 4, 0), 0).write();
        assertDoesNotThrow(() -> verifier.verify(bytes(w -> {
            metadata(w, m);
            w.visitField(1, "value", "Ljava/lang/String;", null, null).visitAnnotation("Lorg/jetbrains/annotations/NotNull;", false).visitEnd();
            MethodVisitor method=w.visitMethod(1025, "value", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
            method.visitParameterAnnotation(0, "Lorg/jetbrains/annotations/Nullable;", false).visitEnd();
            method.visitTypeAnnotation(TypeReference.newTypeReference(TypeReference.METHOD_RETURN).getValue(), null, "Ljavax/annotation/Nullable;", true).visitEnd();
            method.visitEnd();
        }), "example/api/Api.class"));
    }
    private void reject(Consumer<ClassWriter> mutation) { assertThrows(GradleException.class, () -> verifier.verify(bytes(mutation), "example/api/Api.class")); }
    private static MethodVisitor code(ClassWriter writer) { MethodVisitor method = writer.visitMethod(9, "run", "()V", null, null); method.visitCode(); method.visitInsn(Opcodes.NOP); return method; }
    private static byte[] bytes(Consumer<ClassWriter> mutation) {
        ClassWriter writer = new ClassWriter(0); writer.visit(61, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "example/api/Api", null, "java/lang/Object", null);
        mutation.accept(writer); writer.visitEnd(); return writer.toByteArray();
    }
    private static KmType type(String name) { KmType type = new KmType(); type.setClassifier(new KmClassifier.Class(name)); return type; }
    static void metadata(ClassWriter writer, Metadata metadata) {
        AnnotationVisitor a = writer.visitAnnotation("Lkotlin/Metadata;", true); a.visit("k", metadata.k()); a.visit("mv", metadata.mv());
        AnnotationVisitor d1 = a.visitArray("d1"); for (String value : metadata.d1()) d1.visit(null, value); d1.visitEnd();
        AnnotationVisitor d2 = a.visitArray("d2"); for (String value : metadata.d2()) d2.visit(null, value); d2.visitEnd(); a.visitEnd();
    }
}
