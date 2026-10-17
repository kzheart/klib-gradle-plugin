package me.kzheart.klib.gradle;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.commons.Remapper;
import java.util.Set;

/** 注解描述符单独检查；注解值的 class/enum/handle 仍走普通类型边界。 */
final class ApiAnnotationBoundary extends ClassVisitor {
    private final Remapper references;
    private final Set<String> markers;
    ApiAnnotationBoundary(ClassVisitor delegate, Remapper references, Set<String> markers) {
        super(Opcodes.ASM9, delegate); this.references = references; this.markers = markers;
    }
    private AnnotationVisitor annotation(String descriptor) {
        if (descriptor != null && !markers.contains(Type.getType(descriptor).getInternalName())) references.mapDesc(descriptor);
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String name, Object value) { references.mapValue(value); }
            @Override public void visitEnum(String name, String type, String value) { references.mapDesc(type); }
            @Override public AnnotationVisitor visitAnnotation(String name, String type) { return annotation(type); }
            @Override public AnnotationVisitor visitArray(String name) { return annotation(null); }
        };
    }
    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { return annotation(descriptor); }
    @Override public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String descriptor, boolean visible) { return annotation(descriptor); }
    @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        return new FieldVisitor(Opcodes.ASM9, super.visitField(access, name, descriptor, signature, value)) {
            @Override public AnnotationVisitor visitAnnotation(String d, boolean v) { return annotation(d); }
            @Override public AnnotationVisitor visitTypeAnnotation(int r, TypePath p, String d, boolean v) { return annotation(d); }
        };
    }
    @Override public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
        return new RecordComponentVisitor(Opcodes.ASM9, super.visitRecordComponent(name, descriptor, signature)) {
            @Override public AnnotationVisitor visitAnnotation(String d, boolean v) { return annotation(d); }
            @Override public AnnotationVisitor visitTypeAnnotation(int r, TypePath p, String d, boolean v) { return annotation(d); }
        };
    }
    @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
            @Override public AnnotationVisitor visitAnnotation(String d, boolean v) { return annotation(d); }
            @Override public AnnotationVisitor visitAnnotationDefault() { return annotation(null); }
            @Override public AnnotationVisitor visitTypeAnnotation(int r, TypePath p, String d, boolean v) { return annotation(d); }
            @Override public AnnotationVisitor visitParameterAnnotation(int i, String d, boolean v) { return annotation(d); }
            @Override public AnnotationVisitor visitInsnAnnotation(int r, TypePath p, String d, boolean v) { return annotation(d); }
            @Override public AnnotationVisitor visitTryCatchAnnotation(int r, TypePath p, String d, boolean v) { return annotation(d); }
            @Override public AnnotationVisitor visitLocalVariableAnnotation(int r, TypePath p, Label[] s, Label[] e, int[] i, String d, boolean v) { return annotation(d); }
        };
    }
}
