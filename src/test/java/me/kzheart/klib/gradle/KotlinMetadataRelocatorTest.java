package me.kzheart.klib.gradle;

import kotlin.Metadata;
import kotlin.metadata.KmAnnotation;
import kotlin.metadata.KmAnnotationArgument;
import kotlin.metadata.KmClass;
import kotlin.metadata.KmClassifier;
import kotlin.metadata.KmConstructor;
import kotlin.metadata.KmContract;
import kotlin.metadata.KmEffect;
import kotlin.metadata.KmEffectExpression;
import kotlin.metadata.KmEffectType;
import kotlin.metadata.KmFunction;
import kotlin.metadata.KmPackage;
import kotlin.metadata.KmProperty;
import kotlin.metadata.KmType;
import kotlin.metadata.KmTypeAlias;
import kotlin.metadata.KmValueParameter;
import kotlin.metadata.internal.common.KotlinCommonMetadata;
import kotlin.metadata.internal.metadata.ProtoBuf;
import kotlin.metadata.internal.metadata.jvm.deserialization.JvmNameResolver;
import kotlin.metadata.internal.metadata.jvm.deserialization.JvmProtoBufUtil;
import kotlin.metadata.internal.metadata.jvm.serialization.JvmStringTable;
import kotlin.metadata.internal.protobuf.ByteString;
import kotlin.metadata.jvm.JvmExtensionsKt;
import kotlin.metadata.jvm.JvmFieldSignature;
import kotlin.metadata.jvm.JvmMetadataUtil;
import kotlin.metadata.jvm.JvmMetadataVersion;
import kotlin.metadata.jvm.JvmMethodSignature;
import kotlin.metadata.jvm.KmModule;
import kotlin.metadata.jvm.KmPackageParts;
import kotlin.metadata.jvm.KotlinClassMetadata;
import kotlin.metadata.jvm.KotlinModuleMetadata;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"deprecation", "classfile"})
class KotlinMetadataRelocatorTest {
    private static final Map<String, String> RULES = Collections.singletonMap("old", "plugin.libs.old");
    private static final List<String> NONE = Collections.emptyList();
    private static final JvmMetadataVersion VERSION = new JvmMetadataVersion(2, 4, 0);

    @Test void rewritesClassTypesSignaturesAnnotationsAndContractsWithoutChangingLiterals() {
        KmClass node = new KmClass();
        node.setName("old/Owner.Nested");
        node.getSupertypes().add(type("old/Base"));
        node.getSealedSubclasses().add("old/Child");
        node.setInlineClassUnderlyingType(type("old/Value"));
        JvmExtensionsKt.setAnonymousObjectOriginName(node, "old/Origin$Nested");
        KmConstructor constructor = new KmConstructor();
        KmValueParameter parameter = new KmValueParameter("value");
        parameter.setType(type("old/Value"));
        parameter.setVarargElementType(type("old/Value"));
        constructor.getValueParameters().add(parameter);
        JvmExtensionsKt.setSignature(constructor, new JvmMethodSignature("<init>", "([Lold/Value;)V"));
        node.getConstructors().add(constructor);
        KmFunction function = function("read", "old/Value");
        function.setReceiverParameterType(type("old/Owner"));
        JvmExtensionsKt.setSignature(function, new JvmMethodSignature("read", "(Lold/Owner;[Lold/Value;)Lold/Value;"));
        JvmExtensionsKt.setLambdaClassOriginName(function, "old/Origin$lambda");
        KmEffectExpression expression = new KmEffectExpression();
        expression.setInstanceType(type("old/Value"));
        KmEffect effect = new KmEffect(KmEffectType.RETURNS_NOT_NULL, null);
        effect.setConclusion(expression);
        KmContract contract = new KmContract();
        contract.getEffects().add(effect);
        function.setContract(contract);
        node.getFunctions().add(function);
        KmProperty property = new KmProperty("value");
        property.setReturnType(type("old/Value"));
        JvmExtensionsKt.setFieldSignature(property, new JvmFieldSignature("value", "Lold/Value;"));
        JvmExtensionsKt.setGetterSignature(property, new JvmMethodSignature("getValue", "()Lold/Value;"));
        node.getProperties().add(property);
        Map<String, KmAnnotationArgument> arguments = new LinkedHashMap<String, KmAnnotationArgument>();
        arguments.put("klass", new KmAnnotationArgument.KClassValue("old/Value"));
        arguments.put("enum", new KmAnnotationArgument.EnumValue("old/Mode", "ONE"));
        arguments.put("array", new KmAnnotationArgument.ArrayKClassValue("old/Value", 2));
        arguments.put("literal", new KmAnnotationArgument.StringValue("old.Value"));
        arguments.put("nested", new KmAnnotationArgument.AnnotationValue(
                new KmAnnotation("old/Nested", Collections.<String, KmAnnotationArgument>emptyMap())));
        node.getAnnotations().add(new KmAnnotation("old/Marker", arguments));
        JvmExtensionsKt.getAnnotations(function.getReturnType()).add(new KmAnnotation("old/TypeMarker", arguments));

        KmClass result = ((KotlinClassMetadata.Class) roundTrip(new KotlinClassMetadata.Class(node, VERSION, 0).write(), RULES, NONE)).getKmClass();
        assertEquals("plugin/libs/old/Owner.Nested", result.getName());
        assertEquals("plugin/libs/old/Base", classifier(result.getSupertypes().get(0)));
        assertEquals("plugin/libs/old/Child", result.getSealedSubclasses().get(0));
        assertEquals("plugin/libs/old/Origin$Nested", JvmExtensionsKt.getAnonymousObjectOriginName(result));
        assertEquals("([Lplugin/libs/old/Value;)V", JvmExtensionsKt.getSignature(result.getConstructors().get(0)).getDescriptor());
        KmFunction moved = result.getFunctions().get(0);
        assertEquals("(Lplugin/libs/old/Owner;[Lplugin/libs/old/Value;)Lplugin/libs/old/Value;", JvmExtensionsKt.getSignature(moved).getDescriptor());
        assertEquals("plugin/libs/old/Value", classifier(moved.getContract().getEffects().get(0).getConclusion().isInstanceType()));
        assertEquals("Lplugin/libs/old/Value;", JvmExtensionsKt.getFieldSignature(result.getProperties().get(0)).getDescriptor());
        KmAnnotation annotation = result.getAnnotations().get(0);
        assertEquals("plugin/libs/old/Marker", annotation.getClassName());
        assertEquals("plugin/libs/old/Value", ((KmAnnotationArgument.KClassValue) annotation.getArguments().get("klass")).getClassName());
        assertEquals("old.Value", ((KmAnnotationArgument.StringValue) annotation.getArguments().get("literal")).getValue());
        assertEquals("plugin/libs/old/TypeMarker", JvmExtensionsKt.getAnnotations(moved.getReturnType()).get(0).getClassName());
    }

    @Test void rewritesMultifileMetadataAndTypeAliases() {
        KmPackage pkg = new KmPackage();
        KmTypeAlias alias = new KmTypeAlias("Alias");
        alias.setUnderlyingType(type("old/Value"));
        alias.setExpandedType(type("old/Value"));
        pkg.getTypeAliases().add(alias);
        KotlinClassMetadata.MultiFileClassPart part = (KotlinClassMetadata.MultiFileClassPart) roundTrip(
                new KotlinClassMetadata.MultiFileClassPart(pkg, "old/Facade", VERSION, 0).write(), RULES, NONE);
        assertEquals("plugin/libs/old/Facade", part.getFacadeClassName());
        assertEquals("plugin/libs/old/Value", classifier(part.getKmPackage().getTypeAliases().get(0).getExpandedType()));
        KotlinClassMetadata.MultiFileClassFacade facade = (KotlinClassMetadata.MultiFileClassFacade) roundTrip(
                new KotlinClassMetadata.MultiFileClassFacade(Arrays.asList("old/PartA", "old/PartB"), VERSION, 0).write(), RULES, NONE);
        assertEquals(Arrays.asList("plugin/libs/old/PartA", "plugin/libs/old/PartB"), facade.getPartClassNames());
    }

    @Test void preservesProtectedNamesAndPackageBoundaries() {
        KmClass node = new KmClass();
        node.setName("old/shared/Api");
        node.getSupertypes().add(type("older/Base"));
        node.getSupertypes().add(type("old/privateapi/Base"));
        KmClass result = ((KotlinClassMetadata.Class) roundTrip(new KotlinClassMetadata.Class(node, VERSION, 0).write(),
                RULES, Collections.singletonList("old.shared"))).getKmClass();
        assertEquals("old/shared/Api", result.getName());
        assertEquals("older/Base", classifier(result.getSupertypes().get(0)));
        assertEquals("plugin/libs/old/privateapi/Base", classifier(result.getSupertypes().get(1)));
    }

    @Test void relocatesMetadataAnnotationDescriptorAndPackageName() {
        KmClass node = new KmClass();
        node.setName("old/Owner");
        Metadata base = new KotlinClassMetadata.Class(node, VERSION, 0).write();
        Metadata header = JvmMetadataUtil.Metadata(base.k(), base.mv(), base.d1(), base.d2(), "", "old.api", base.xi());
        Map<String, String> rules = new LinkedHashMap<String, String>(RULES);
        rules.put("kotlin", "plugin.libs.kotlin");
        byte[] moved = relocate(classBytes(header), rules, NONE);
        Metadata result = read(moved, "Lplugin/libs/kotlin/Metadata;");
        assertNotNull(result);
        assertEquals("plugin.libs.old.api", result.pn());
        assertNull(read(moved, "Lkotlin/Metadata;"));
    }

    @Test void rejectsUnknownVersionsKindsMalformedDataAndOpaqueCompilerPayloads() {
        Metadata future = JvmMetadataUtil.Metadata(1, new int[]{99, 0, 0}, new String[]{"broken"}, new String[0], "", "", 0);
        assertThrows(GradleException.class, () -> relocate(classBytes(future), RULES, NONE));
        Metadata unknown = JvmMetadataUtil.Metadata(99, new int[]{2, 4, 0}, new String[0], new String[0], "", "", 0);
        assertThrows(GradleException.class, () -> relocate(classBytes(unknown), RULES, NONE));
        Metadata malformed = JvmMetadataUtil.Metadata(1, new int[]{2, 4, 0}, new String[]{"broken"}, new String[0], "", "", 0);
        assertThrows(GradleException.class, () -> relocate(classBytes(malformed), RULES, NONE));
        KmClass node = new KmClass();
        node.setName("old/Owner");
        node.getCompilerPluginMetadata().put("opaque", new byte[]{1, 2, 3});
        assertThrows(GradleException.class, () -> relocate(classBytes(new KotlinClassMetadata.Class(node, VERSION, 0).write()), RULES, NONE));
    }

    @Test void rejectsUnknownProtoFieldsAndPropertyContractsInsteadOfDroppingThem() {
        KmClass node = new KmClass();
        node.setName("old/Owner");
        KmProperty property = new KmProperty("value");
        property.setReturnType(type("old/Value"));
        node.getProperties().add(property);
        Metadata original = new KotlinClassMetadata.Class(node, VERSION, 0).write();
        kotlin.Pair<JvmNameResolver, ProtoBuf.Class> parsed = JvmProtoBufUtil.readClassDataFrom(original.d1(), original.d2());
        ProtoBuf.Class unknown = parsed.getSecond().toBuilder()
                .setUnknownFields(ByteString.copyFrom(new byte[]{(byte) 0xf8, 0x7f, 1})).build();
        assertTrue(assertThrows(GradleException.class, () -> relocate(classBytes(withProto(original, parsed.getFirst(), unknown)), RULES, NONE))
                .getMessage().contains("Unknown protobuf fields"));
        ProtoBuf.Class contract = ProtoBuf.Class.newBuilder().setFqName(parsed.getSecond().getFqName())
                .addAllProperty(Collections.singletonList(parsed.getSecond().getProperty(0).toBuilder()
                        .mergeGetterContract(ProtoBuf.Contract.newBuilder().build()).build())).build();
        assertTrue(assertThrows(GradleException.class, () -> relocate(classBytes(withProto(original, parsed.getFirst(), contract)), RULES, NONE))
                .getMessage().contains("property contracts"));
    }

    @Test void relocatesAndMergesModulePackageAndMultifileMappings() {
        byte[] original = module("old", "old/FileA", "old/PartA", "old/Facade");
        byte[] relocated = KotlinMetadataRelocator.relocateModule(original, RULES, NONE);
        KmPackageParts parts = KotlinModuleMetadata.read(relocated).getKmModule().getPackageParts().get("plugin.libs.old");
        assertEquals(Collections.singletonList("plugin/libs/old/FileA"), parts.getFileFacades());
        assertEquals("plugin/libs/old/Facade", parts.getMultiFileClassParts().get("plugin/libs/old/PartA"));
        byte[] merged = KotlinMetadataRelocator.mergeModules(original, module("old", "old/FileB", "old/PartB", "old/Facade"));
        KmPackageParts both = KotlinModuleMetadata.read(merged).getKmModule().getPackageParts().get("old");
        assertEquals(Arrays.asList("old/FileA", "old/FileB"), both.getFileFacades());
        assertEquals(2, both.getMultiFileClassParts().size());
        assertThrows(GradleException.class, () -> KotlinMetadataRelocator.mergeModules(original,
                module("old", "old/FileB", "old/PartA", "old/DifferentFacade")));
    }

    @Test void rejectsUnsupportedModuleFieldsAndCorruptResourceHeaders() {
        byte[] valid = module("old", "old/File", "old/Part", "old/Facade");
        byte[] unknown = Arrays.copyOf(valid, valid.length + 3);
        unknown[valid.length] = (byte) 0x9a; // unknown field 99
        unknown[valid.length + 1] = 6;
        unknown[valid.length + 2] = 0;
        assertThrows(GradleException.class, () -> KotlinMetadataRelocator.relocateModule(unknown, RULES, NONE));
        assertThrows(GradleException.class, () -> KotlinMetadataRelocator.mergeModules(valid, unknown));
        assertThrows(GradleException.class, () -> KotlinMetadataRelocator.relocateModule(new byte[3], RULES, NONE));
    }

    @Test void preservesRealStdlibOptionalAnnotationsAndTheirRelocatedNames() throws Exception {
        byte[] source = resource("/META-INF/kotlin-stdlib.kotlin_module");
        KotlinModuleMetadata before = KotlinModuleMetadata.read(source);
        assertFalse(before.getKmModule().getOptionalAnnotationClasses().isEmpty());
        Map<String, String> rules = Collections.singletonMap("kotlin", "plugin.libs.kotlin");
        byte[] relocated = KotlinMetadataRelocator.relocateModule(source, rules, NONE);
        KotlinModuleMetadata after = KotlinModuleMetadata.read(relocated);
        assertEquals(before.getKmModule().getOptionalAnnotationClasses().size(), after.getKmModule().getOptionalAnnotationClasses().size());
        assertTrue(after.getKmModule().getOptionalAnnotationClasses().stream()
                .allMatch(node -> node.getName().startsWith("plugin/libs/kotlin/")));
        byte[] merged = KotlinMetadataRelocator.mergeModules(relocated, module("old", "old/File", "old/Part", "old/Facade"));
        assertEquals(after.getKmModule().getOptionalAnnotationClasses().size(),
                KotlinModuleMetadata.read(merged).getKmModule().getOptionalAnnotationClasses().size());
    }

    @Test void acceptsValidEmptyModulePayload() {
        byte[] empty = new KotlinModuleMetadata(new KmModule(), VERSION).write();
        assertTrue(KotlinModuleMetadata.read(KotlinMetadataRelocator.relocateModule(empty, RULES, NONE))
                .getKmModule().getPackageParts().isEmpty());
        assertTrue(KotlinModuleMetadata.read(KotlinMetadataRelocator.mergeModules(empty, empty))
                .getKmModule().getPackageParts().isEmpty());
    }

    @Test void relocatesRealBuiltinsQualifiedNamesAndPreservesProtectedPackages() throws Exception {
        byte[] original = resource("/kotlin/kotlin.kotlin_builtins");
        Map<String, String> rules = Collections.singletonMap("kotlin", "plugin.libs.kotlin");
        byte[] relocated = KotlinMetadataRelocator.relocateBuiltIns(original, rules, NONE);
        List<KmClass> classes = KotlinCommonMetadata.read(relocated).getKmModuleFragment().getClasses();
        assertFalse(classes.isEmpty());
        assertTrue(classes.stream().anyMatch(node -> node.getName().equals("plugin/libs/kotlin/String")));
        assertTrue(classes.stream().allMatch(node -> node.getName().startsWith("plugin/libs/kotlin/")));
        byte[] protectedResource = resource("/kotlin/collections/collections.kotlin_builtins");
        byte[] protectedResult = KotlinMetadataRelocator.relocateBuiltIns(protectedResource, rules,
                Collections.singletonList("kotlin.collections"));
        List<KmClass> protectedClasses = KotlinCommonMetadata.read(protectedResult).getKmModuleFragment().getClasses();
        assertTrue(protectedClasses.stream().anyMatch(node -> node.getName().equals("kotlin/collections/List")));
        byte[] unknown = original.clone();
        unknown[15] = 99;
        assertThrows(GradleException.class, () -> KotlinMetadataRelocator.relocateBuiltIns(unknown, rules, NONE));
    }

    @Test void rejectsUnverifiedReflectBytesForEveryAdaptedRuntimeClass() {
        String prefix = "kotlin/reflect/jvm/internal/";
        Map<String, String> rules = Collections.singletonMap("kotlin", "plugin.libs.kotlin");
        for (String name : Arrays.asList(
                prefix + "impl/builtins/StandardNames",
                prefix + "impl/descriptors/runtime/components/ReflectKotlinClassFinder",
                prefix + "ConvertFromJavaKt",
                prefix + "impl/builtins/FunctionTypesKt",
                prefix + "impl/builtins/KotlinBuiltIns",
                prefix + "impl/load/java/lazy/descriptors/LazyJavaClassDescriptor$LazyJavaClassTypeConstructor")) {
            byte[] unverified = emptyClass(name);
            GradleException failure = assertThrows(GradleException.class,
                    () -> KotlinReflectRelocator.prepare(unverified, rules, NONE), name);
            assertTrue(failure.getMessage().contains("unverified original class bytes"), failure.getMessage());
            assertTrue(failure.getMessage().contains("kotlin-reflect 2.4.21"), failure.getMessage());
            assertTrue(failure.getMessage().contains(name), failure.getMessage());
        }
    }

    @Test void reflectAdapterLeavesUserClassesAndExternalRuntimeUntouched() {
        Map<String, String> rules = Collections.singletonMap("kotlin", "plugin.libs.kotlin");
        byte[] user = emptyClass("user/ReflectKotlinClassFinder");
        assertSame(user, KotlinReflectRelocator.prepare(user, rules, NONE));
        byte[] target = emptyClass("kotlin/reflect/jvm/internal/impl/builtins/StandardNames");
        assertSame(target, KotlinReflectRelocator.prepare(target, RULES, NONE));
        assertSame(target, KotlinReflectRelocator.prepare(target, rules, Collections.singletonList("kotlin")));
        assertSame(target, KotlinReflectRelocator.prepare(target, rules, Collections.singletonList("kotlin.reflect")));
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] module(String pkg, String file, String part, String facade) {
        KmModule module = new KmModule();
        module.getPackageParts().put(pkg, new KmPackageParts(new ArrayList<String>(Collections.singletonList(file)),
                new LinkedHashMap<String, String>(Collections.singletonMap(part, facade))));
        return new KotlinModuleMetadata(module, VERSION).write();
    }

    private static Metadata withProto(Metadata original, JvmNameResolver resolver, ProtoBuf.Class proto) {
        JvmStringTable strings = new JvmStringTable(resolver);
        String[] d1 = JvmProtoBufUtil.writeData(proto, strings);
        return JvmMetadataUtil.Metadata(original.k(), original.mv(), d1, strings.getStrings().toArray(new String[0]),
                original.xs(), original.pn(), original.xi());
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream input = Metadata.class.getResourceAsStream(name)) {
            assertNotNull(input, name);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static KmType type(String name) {
        KmType type = new KmType();
        type.setClassifier(new KmClassifier.Class(name));
        return type;
    }

    private static String classifier(KmType type) {
        return ((KmClassifier.Class) type.getClassifier()).getName();
    }

    private static KmFunction function(String name, String result) {
        KmFunction function = new KmFunction(name);
        function.setReturnType(type(result));
        return function;
    }

    private static KotlinClassMetadata roundTrip(Metadata metadata, Map<String, String> rules, List<String> protectedPrefixes) {
        return KotlinClassMetadata.readStrict(read(relocate(classBytes(metadata), rules, protectedPrefixes), "Lkotlin/Metadata;"));
    }

    private static byte[] relocate(byte[] source, Map<String, String> rules, List<String> protectedPrefixes) {
        KotlinMetadataRelocator.PreparedClass prepared = KotlinMetadataRelocator.prepare(source, rules, protectedPrefixes);
        // prepare/restore 的契约独立验证，常量池变换由 ClassRelocator 的集成用例覆盖。
        return prepared.restore(prepared.getClassBytes());
    }

    private static byte[] classBytes(Metadata metadata) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "old/Owner", null, "java/lang/Object", null);
        AnnotationVisitor annotation = writer.visitAnnotation("Lkotlin/Metadata;", true);
        annotation.visit("k", metadata.k());
        annotation.visit("mv", metadata.mv());
        strings(annotation, "d1", metadata.d1());
        strings(annotation, "d2", metadata.d2());
        annotation.visit("xs", metadata.xs());
        annotation.visit("pn", metadata.pn());
        annotation.visit("xi", metadata.xi());
        annotation.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void strings(AnnotationVisitor parent, String name, String[] values) {
        AnnotationVisitor array = parent.visitArray(name);
        for (String value : values) array.visit(null, value);
        array.visitEnd();
    }

    private static Metadata read(byte[] source, final String descriptor) {
        final Map<String, Object> values = new LinkedHashMap<String, Object>();
        final boolean[] found = {false};
        new ClassReader(source).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String name, boolean visible) {
                if (!name.equals(descriptor)) return null;
                found[0] = true;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public void visit(String key, Object value) { values.put(key, value); }
                    @Override public AnnotationVisitor visitArray(final String key) {
                        final List<String> strings = new ArrayList<String>();
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override public void visit(String ignored, Object value) { strings.add((String) value); }
                            @Override public void visitEnd() { values.put(key, strings.toArray(new String[0])); }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0] ? JvmMetadataUtil.Metadata((Integer) values.get("k"), (int[]) values.get("mv"),
                (String[]) values.get("d1"), (String[]) values.get("d2"), (String) values.get("xs"),
                (String) values.get("pn"), (Integer) values.get("xi")) : null;
    }
}
