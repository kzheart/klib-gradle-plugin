package me.kzheart.klib.gradle;

import kotlin.Metadata;
import kotlin.metadata.KmAnnotation;
import kotlin.metadata.KmAnnotationArgument;
import kotlin.metadata.KmClass;
import kotlin.metadata.KmClassifier;
import kotlin.metadata.KmConstructor;
import kotlin.metadata.KmDeclarationContainer;
import kotlin.metadata.KmEffect;
import kotlin.metadata.KmEffectExpression;
import kotlin.metadata.KmEnumEntry;
import kotlin.metadata.KmFunction;
import kotlin.metadata.KmPackage;
import kotlin.metadata.KmProperty;
import kotlin.metadata.KmType;
import kotlin.metadata.KmTypeAlias;
import kotlin.metadata.KmTypeParameter;
import kotlin.metadata.KmTypeProjection;
import kotlin.metadata.KmValueParameter;
import kotlin.metadata.jvm.JvmExtensionsKt;
import kotlin.metadata.jvm.JvmFieldSignature;
import kotlin.metadata.jvm.JvmMetadataUtil;
import kotlin.metadata.jvm.JvmMetadataVersion;
import kotlin.metadata.jvm.JvmMethodSignature;
import kotlin.metadata.jvm.KmPackageParts;
import kotlin.metadata.jvm.KotlinClassMetadata;
import kotlin.metadata.jvm.KotlinModuleMetadata;
import kotlin.metadata.internal.common.KotlinCommonMetadata;
import kotlin.metadata.internal.metadata.ProtoBuf;
import kotlin.metadata.internal.metadata.jvm.JvmProtoBuf;
import kotlin.metadata.internal.metadata.jvm.deserialization.JvmProtoBufUtil;
import kotlin.metadata.internal.protobuf.GeneratedMessageLite;
import kotlin.metadata.internal.protobuf.MessageLite;
import org.gradle.api.GradleException;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构化重写 Kotlin JVM metadata，不把 protobuf 的编码字符串当作普通类名替换。
 *
 * <p>支持 kotlin-metadata-jvm 已知的 1.4 至当前稳定版本。未知版本、metadata kind、
 * 不透明 compiler-plugin payload 及不能无损重写的 module 扩展都会拒绝打包。
 * kotlin 内建类型也随隔离命名空间搬迁，因此必须配套搬迁运行时、反射库及 builtins 资源；
 * 此转换本身不承诺任意第三方编译器插件或反射库的兼容性。</p>
 */
@SuppressWarnings({"deprecation", "classfile"}) // 旧 metadata 的 context receivers 仍须完整遍历。
final class KotlinMetadataRelocator {
    private static final String METADATA_DESCRIPTOR = "Lkotlin/Metadata;";
    private java.util.function.Consumer<String> referenceCheck;
    private final Map<String, String> relocations;
    private final List<String> protectedPrefixes;
    private final Set<KmType> visitedTypes = Collections.newSetFromMap(new IdentityHashMap<KmType, Boolean>());

    private KotlinMetadataRelocator(Map<String, String> relocations, List<String> protectedPrefixes) {
        this.relocations = relocations;
        this.protectedPrefixes = protectedPrefixes;
    }

    /** 提取并移除原注解，随后可安全执行常量池重定位，再调用 {@link PreparedClass#restore(byte[])}。 */
    static PreparedClass prepare(byte[] source, Map<String, String> relocations, List<String> protectedPrefixes) {
        if (relocations.isEmpty()) {
            return new PreparedClass(source, null, null, false, null);
        }
        final ClassReader reader = new ClassReader(source);
        final MetadataFields[] fields = new MetadataFields[1];
        final boolean[] visible = new boolean[1];
        // 不复用原 ClassReader 的常量池，以免旧 d1/d2 再被字符串重定位破坏。
        ClassWriter writer = new ClassWriter(0);
        try {
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean isVisible) {
                    if (!METADATA_DESCRIPTOR.equals(descriptor)) {
                        return super.visitAnnotation(descriptor, isVisible);
                    }
                    if (fields[0] != null) {
                        throw unsafe("Duplicate kotlin.Metadata annotation");
                    }
                    fields[0] = new MetadataFields();
                    visible[0] = isVisible;
                    return fields[0];
                }
            }, 0);
            if (fields[0] == null) {
                return new PreparedClass(source, null, null, false, null);
            }
            KotlinMetadataRelocator relocator = new KotlinMetadataRelocator(relocations, protectedPrefixes);
            Metadata original = fields[0].metadata();
            validateVersion(original.mv());
            validateClassPayload(original);
            KotlinClassMetadata parsed = KotlinClassMetadata.readStrict(original);
            relocator.rewrite(parsed);
            Metadata rewritten = parsed.write();
            Metadata complete = JvmMetadataUtil.Metadata(rewritten.k(), rewritten.mv(), rewritten.d1(), rewritten.d2(),
                    parsed instanceof KotlinClassMetadata.MultiFileClassPart ? rewritten.xs() : original.xs(),
                    relocator.dottedName(original.pn()), rewritten.xi());
            return new PreparedClass(writer.toByteArray(), complete,
                    "L" + relocator.internalName("kotlin/Metadata") + ";", visible[0], fields[0].bytecodeVersion());
        } catch (Exception failure) {
            throw new GradleException("Cannot safely relocate Kotlin metadata in " + reader.getClassName()
                    + ": " + failure.getMessage(), failure);
        }
    }

    /** API 检查复用同一结构化遍历；不重写、不信任 d1/d2 普通字符串搜索。 */
    static void verifyApi(byte[] source, java.util.function.Consumer<String> referenceCheck) {
        final MetadataFields[] fields = new MetadataFields[1];
        new ClassReader(source).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!METADATA_DESCRIPTOR.equals(descriptor)) return null;
                if (fields[0] != null) throw unsafe("Duplicate kotlin.Metadata annotation");
                fields[0] = new MetadataFields(); return fields[0];
            }
        }, 0);
        if (fields[0] == null) return;
        Metadata metadata = fields[0].metadata();
        validateVersion(metadata.mv()); validateClassPayload(metadata);
        KotlinMetadataRelocator visitor = new KotlinMetadataRelocator(Collections.emptyMap(), Collections.emptyList());
        visitor.referenceCheck = referenceCheck;
        visitor.rewrite(KotlinClassMetadata.readStrict(metadata));
    }

    static final class PreparedClass {
        private final byte[] classBytes;
        private final Metadata metadata;
        private final String descriptor;
        private final boolean visible;
        private final int[] bytecodeVersion;

        private PreparedClass(byte[] classBytes, Metadata metadata, String descriptor,
                              boolean visible, int[] bytecodeVersion) {
            this.classBytes = classBytes;
            this.metadata = metadata;
            this.descriptor = descriptor;
            this.visible = visible;
            this.bytecodeVersion = bytecodeVersion;
        }

        byte[] getClassBytes() {
            return classBytes;
        }

        byte[] restore(byte[] relocatedClass) {
            if (metadata == null) {
                return relocatedClass;
            }
            ClassReader reader = new ClassReader(relocatedClass);
            final ClassWriter writer = new ClassWriter(reader, 0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public void visitEnd() {
                    AnnotationVisitor annotation = super.visitAnnotation(descriptor, visible);
                    annotation.visit("k", metadata.k());
                    annotation.visit("mv", metadata.mv());
                    if (bytecodeVersion != null) {
                        annotation.visit("bv", bytecodeVersion);
                    }
                    writeStrings(annotation, "d1", metadata.d1());
                    writeStrings(annotation, "d2", metadata.d2());
                    if (!metadata.xs().isEmpty()) annotation.visit("xs", metadata.xs());
                    if (!metadata.pn().isEmpty()) annotation.visit("pn", metadata.pn());
                    annotation.visit("xi", metadata.xi());
                    annotation.visitEnd();
                    super.visitEnd();
                }
            }, 0);
            return writer.toByteArray();
        }
    }

    static byte[] relocateModule(byte[] source, Map<String, String> relocations, List<String> protectedPrefixes) {
        if (relocations.isEmpty()) return source;
        try {
            BinaryMetadata binary = BinaryMetadata.read(source);
            validateVersion(binary.version);
            validateModuleFields(binary);
            KotlinModuleMetadata.read(source); // 官方严格读取器验证声明和引用。
            KotlinMetadataRelocator relocator = new KotlinMetadataRelocator(relocations, protectedPrefixes);
            List<WireField> fields = relocateNameTables(WireField.read(binary.payload), 4, 5, relocator, false);
            List<WireField> moved = new ArrayList<WireField>();
            for (WireField field : fields) {
                if (field.number == 1) {
                    List<WireField> parts = new ArrayList<WireField>();
                    for (WireField part : WireField.read(field.bytes)) {
                        parts.add(part.number == 1 ? WireField.bytes(1,
                                relocator.dottedName(decodeUtf8(part.bytes)).getBytes(StandardCharsets.UTF_8)) : part);
                    }
                    moved.add(WireField.bytes(1, WireField.write(parts)));
                } else if (field.number == 3) {
                    moved.add(WireField.bytes(3, relocator.dottedName(decodeUtf8(field.bytes)).getBytes(StandardCharsets.UTF_8)));
                } else moved.add(field);
            }
            byte[] result = new BinaryMetadata(binary.version, binary.flags, WireField.write(moved)).write();
            KotlinModuleMetadata.read(result);
            return result;
        } catch (IOException | RuntimeException failure) {
            throw new GradleException("Cannot safely relocate .kotlin_module: " + failure.getMessage(), failure);
        }
    }

    static void verifyApiModule(byte[] source, java.util.function.Consumer<String> referenceCheck) {
        try {
            BinaryMetadata binary = BinaryMetadata.read(source);
            validateVersion(binary.version); validateModuleFields(binary);
            KotlinModuleMetadata module = KotlinModuleMetadata.read(source);
            for (KmPackageParts parts : module.getKmModule().getPackageParts().values()) {
                for (String facade : parts.getFileFacades()) referenceCheck.accept(facade);
                for (Map.Entry<String, String> part : parts.getMultiFileClassParts().entrySet()) {
                    referenceCheck.accept(part.getKey()); referenceCheck.accept(part.getValue());
                }
            }
            if (!module.getKmModule().getOptionalAnnotationClasses().isEmpty())
                throw unsafe("API module-level annotation metadata is not supported");
        } catch (IOException failure) { throw new GradleException("Cannot verify API .kotlin_module", failure); }
    }

    private void rewrite(KotlinClassMetadata metadata) {
        if (metadata instanceof KotlinClassMetadata.Class) {
            rewriteClass(((KotlinClassMetadata.Class) metadata).getKmClass());
        } else if (metadata instanceof KotlinClassMetadata.FileFacade) {
            rewritePackage(((KotlinClassMetadata.FileFacade) metadata).getKmPackage());
        } else if (metadata instanceof KotlinClassMetadata.MultiFileClassPart) {
            KotlinClassMetadata.MultiFileClassPart part = (KotlinClassMetadata.MultiFileClassPart) metadata;
            rewritePackage(part.getKmPackage());
            part.setFacadeClassName(internalName(part.getFacadeClassName()));
        } else if (metadata instanceof KotlinClassMetadata.MultiFileClassFacade) {
            KotlinClassMetadata.MultiFileClassFacade facade = (KotlinClassMetadata.MultiFileClassFacade) metadata;
            List<String> names = new ArrayList<String>();
            for (String name : facade.getPartClassNames()) names.add(internalName(name));
            facade.setPartClassNames(names);
        } else if (metadata instanceof KotlinClassMetadata.SyntheticClass) {
            KotlinClassMetadata.SyntheticClass synthetic = (KotlinClassMetadata.SyntheticClass) metadata;
            if (synthetic.getKmLambda() != null) rewriteFunction(synthetic.getKmLambda().getFunction());
        } else {
            throw unsafe("Unknown kotlin.Metadata kind");
        }
    }

    private void rewriteClass(KmClass node) {
        rejectOpaque(node.getCompilerPluginMetadata());
        node.setName(className(node.getName()));
        rewriteTypeParameters(node.getTypeParameters());
        rewriteTypes(node.getSupertypes());
        rewriteTypes(node.getContextReceiverTypes());
        rewriteType(node.getInlineClassUnderlyingType());
        rewriteContainer(node);
        for (KmConstructor constructor : node.getConstructors()) {
            rejectOpaque(constructor.getCompilerPluginMetadata());
            rewriteParameters(constructor.getValueParameters());
            rewriteAnnotations(constructor.getAnnotations());
            JvmExtensionsKt.setSignature(constructor, methodSignature(JvmExtensionsKt.getSignature(constructor)));
        }
        List<String> subclasses = node.getSealedSubclasses();
        for (int i = 0; i < subclasses.size(); i++) subclasses.set(i, className(subclasses.get(i)));
        for (KmProperty property : JvmExtensionsKt.getLocalDelegatedProperties(node)) rewriteProperty(property);
        JvmExtensionsKt.setAnonymousObjectOriginName(node, internalName(JvmExtensionsKt.getAnonymousObjectOriginName(node)));
        rewriteAnnotations(node.getAnnotations());
        for (KmEnumEntry entry : node.getKmEnumEntries()) rewriteAnnotations(entry.getAnnotations());
    }

    private void rewritePackage(KmPackage node) {
        rewriteContainer(node);
        for (KmProperty property : JvmExtensionsKt.getLocalDelegatedProperties(node)) rewriteProperty(property);
    }

    private void rewriteContainer(KmDeclarationContainer node) {
        for (KmFunction function : node.getFunctions()) rewriteFunction(function);
        for (KmProperty property : node.getProperties()) rewriteProperty(property);
        for (KmTypeAlias alias : node.getTypeAliases()) {
            rejectOpaque(alias.getCompilerPluginMetadata());
            rewriteTypeParameters(alias.getTypeParameters());
            rewriteType(alias.getUnderlyingType());
            rewriteType(alias.getExpandedType());
            rewriteAnnotations(alias.getAnnotations());
        }
    }

    private void rewriteFunction(KmFunction node) {
        rejectOpaque(node.getCompilerPluginMetadata());
        rewriteTypeParameters(node.getTypeParameters());
        rewriteType(node.getReceiverParameterType());
        rewriteTypes(node.getContextReceiverTypes());
        rewriteParameters(node.getContextParameters());
        rewriteParameters(node.getValueParameters());
        rewriteType(node.getReturnType());
        rewriteAnnotations(node.getAnnotations());
        rewriteAnnotations(node.getExtensionReceiverParameterAnnotations());
        JvmExtensionsKt.setSignature(node, methodSignature(JvmExtensionsKt.getSignature(node)));
        JvmExtensionsKt.setLambdaClassOriginName(node, internalName(JvmExtensionsKt.getLambdaClassOriginName(node)));
        if (node.getContract() != null) {
            for (KmEffect effect : node.getContract().getEffects()) {
                for (KmEffectExpression expression : effect.getConstructorArguments()) rewriteExpression(expression);
                rewriteExpression(effect.getConclusion());
            }
        }
    }

    private void rewriteProperty(KmProperty node) {
        rejectOpaque(node.getCompilerPluginMetadata());
        rewriteTypeParameters(node.getTypeParameters());
        rewriteType(node.getReceiverParameterType());
        rewriteTypes(node.getContextReceiverTypes());
        rewriteParameters(node.getContextParameters());
        rewriteParameter(node.getSetterParameter());
        rewriteType(node.getReturnType());
        rewriteAnnotations(node.getAnnotations());
        rewriteAnnotations(node.getBackingFieldAnnotations());
        rewriteAnnotations(node.getDelegateFieldAnnotations());
        rewriteAnnotations(node.getExtensionReceiverParameterAnnotations());
        rewriteAnnotations(node.getGetter().getAnnotations());
        if (node.getSetter() != null) rewriteAnnotations(node.getSetter().getAnnotations());
        JvmFieldSignature field = JvmExtensionsKt.getFieldSignature(node);
        if (field != null) JvmExtensionsKt.setFieldSignature(node,
                new JvmFieldSignature(field.getName(), descriptor(field.getDescriptor())));
        JvmExtensionsKt.setGetterSignature(node, methodSignature(JvmExtensionsKt.getGetterSignature(node)));
        JvmExtensionsKt.setSetterSignature(node, methodSignature(JvmExtensionsKt.getSetterSignature(node)));
        JvmExtensionsKt.setSyntheticMethodForAnnotations(node,
                methodSignature(JvmExtensionsKt.getSyntheticMethodForAnnotations(node)));
        JvmExtensionsKt.setSyntheticMethodForDelegate(node,
                methodSignature(JvmExtensionsKt.getSyntheticMethodForDelegate(node)));
    }

    private void rewriteTypeParameters(List<KmTypeParameter> parameters) {
        for (KmTypeParameter parameter : parameters) {
            rewriteTypes(parameter.getUpperBounds());
            rewriteAnnotations(JvmExtensionsKt.getAnnotations(parameter));
        }
    }

    private void rewriteParameters(List<KmValueParameter> parameters) {
        for (KmValueParameter parameter : parameters) rewriteParameter(parameter);
    }

    private void rewriteParameter(KmValueParameter node) {
        if (node == null) return;
        rewriteType(node.getType());
        rewriteType(node.getVarargElementType());
        rewriteAnnotations(node.getAnnotations());
        if (node.getAnnotationParameterDefaultValue() != null) {
            node.setAnnotationParameterDefaultValue(annotationArgument(node.getAnnotationParameterDefaultValue()));
        }
    }

    private void rewriteTypes(List<KmType> types) {
        for (KmType type : types) rewriteType(type);
    }

    private void rewriteType(KmType node) {
        if (node == null || !visitedTypes.add(node)) return;
        KmClassifier classifier = node.getClassifier();
        if (classifier instanceof KmClassifier.Class) {
            node.setClassifier(new KmClassifier.Class(className(((KmClassifier.Class) classifier).getName())));
        } else if (classifier instanceof KmClassifier.TypeAlias) {
            node.setClassifier(new KmClassifier.TypeAlias(className(((KmClassifier.TypeAlias) classifier).getName())));
        } else if (!(classifier instanceof KmClassifier.TypeParameter)) {
            throw unsafe("Unknown Kotlin type classifier");
        }
        for (KmTypeProjection projection : node.getArguments()) rewriteType(projection.getType());
        rewriteType(node.getAbbreviatedType());
        rewriteType(node.getOuterType());
        if (node.getFlexibleTypeUpperBound() != null) rewriteType(node.getFlexibleTypeUpperBound().getType());
        rewriteAnnotations(JvmExtensionsKt.getAnnotations(node));
    }

    private void rewriteExpression(KmEffectExpression expression) {
        if (expression == null) return;
        rewriteType(expression.isInstanceType());
        for (KmEffectExpression child : expression.getAndArguments()) rewriteExpression(child);
        for (KmEffectExpression child : expression.getOrArguments()) rewriteExpression(child);
    }

    private void rewriteAnnotations(List<KmAnnotation> annotations) {
        for (int i = 0; i < annotations.size(); i++) annotations.set(i, annotation(annotations.get(i)));
    }

    private KmAnnotation annotation(KmAnnotation annotation) {
        Map<String, KmAnnotationArgument> arguments = new LinkedHashMap<String, KmAnnotationArgument>();
        for (Map.Entry<String, KmAnnotationArgument> argument : annotation.getArguments().entrySet()) {
            arguments.put(argument.getKey(), annotationArgument(argument.getValue()));
        }
        return new KmAnnotation(className(annotation.getClassName()), arguments);
    }

    private KmAnnotationArgument annotationArgument(KmAnnotationArgument value) {
        if (value instanceof KmAnnotationArgument.KClassValue) {
            return new KmAnnotationArgument.KClassValue(className(((KmAnnotationArgument.KClassValue) value).getClassName()));
        }
        if (value instanceof KmAnnotationArgument.ArrayKClassValue) {
            KmAnnotationArgument.ArrayKClassValue array = (KmAnnotationArgument.ArrayKClassValue) value;
            return new KmAnnotationArgument.ArrayKClassValue(className(array.getClassName()), array.getArrayDimensionCount());
        }
        if (value instanceof KmAnnotationArgument.EnumValue) {
            KmAnnotationArgument.EnumValue enumeration = (KmAnnotationArgument.EnumValue) value;
            return new KmAnnotationArgument.EnumValue(className(enumeration.getEnumClassName()), enumeration.getEnumEntryName());
        }
        if (value instanceof KmAnnotationArgument.AnnotationValue) {
            return new KmAnnotationArgument.AnnotationValue(annotation(((KmAnnotationArgument.AnnotationValue) value).getAnnotation()));
        }
        if (value instanceof KmAnnotationArgument.ArrayValue) {
            List<KmAnnotationArgument> elements = new ArrayList<KmAnnotationArgument>();
            for (KmAnnotationArgument element : ((KmAnnotationArgument.ArrayValue) value).getElements()) {
                elements.add(annotationArgument(element));
            }
            return new KmAnnotationArgument.ArrayValue(elements);
        }
        if (value instanceof KmAnnotationArgument.LiteralValue<?>) return value;
        throw unsafe("Unknown Kotlin metadata annotation argument");
    }

    private JvmMethodSignature methodSignature(JvmMethodSignature signature) {
        return signature == null ? null : new JvmMethodSignature(signature.getName(), descriptor(signature.getDescriptor()));
    }

    private String descriptor(String descriptor) {
        return descriptorType(Type.getType(descriptor)).getDescriptor();
    }

    private Type descriptorType(Type type) {
        if (type.getSort() == Type.OBJECT) return Type.getObjectType(internalName(type.getInternalName()));
        if (type.getSort() == Type.ARRAY) {
            StringBuilder array = new StringBuilder();
            for (int i = 0; i < type.getDimensions(); i++) array.append('[');
            return Type.getType(array.append(descriptorType(type.getElementType()).getDescriptor()).toString());
        }
        if (type.getSort() == Type.METHOD) {
            Type[] arguments = type.getArgumentTypes();
            for (int i = 0; i < arguments.length; i++) arguments[i] = descriptorType(arguments[i]);
            return Type.getMethodType(descriptorType(type.getReturnType()), arguments);
        }
        return type;
    }

    private String className(String name) {
        return name.startsWith(".") ? "." + internalName(name.substring(1)) : internalName(name);
    }

    private String internalName(String name) {
        if (referenceCheck != null && name != null && !name.isEmpty()) referenceCheck.accept(name);
        return relocateName(name, '/');
    }

    private String dottedName(String name) {
        return relocateName(name, '.');
    }

    private String relocateName(String name, char separator) {
        if (name == null || name.isEmpty()) return name;
        for (String prefix : protectedPrefixes) {
            String candidate = prefix.replace('.', separator);
            if (prefixMatch(name, candidate, separator)) return name;
        }
        Map.Entry<String, String> match = null;
        for (Map.Entry<String, String> entry : relocations.entrySet()) {
            if (prefixMatch(name, entry.getKey().replace('.', separator), separator)
                    && (match == null || entry.getKey().length() > match.getKey().length())) match = entry;
        }
        return match == null ? name : match.getValue().replace('.', separator) + name.substring(match.getKey().length());
    }

    private static boolean prefixMatch(String name, String prefix, char separator) {
        return name.equals(prefix) || name.startsWith(prefix + separator);
    }

    private static void rejectOpaque(Map<String, byte[]> payloads) {
        if (!payloads.isEmpty()) throw unsafe("Opaque compiler-plugin metadata cannot be safely relocated: " + payloads.keySet());
    }

    private static void validateVersion(int[] version) {
        JvmMetadataVersion latest = JvmMetadataVersion.LATEST_STABLE_SUPPORTED;
        if (version.length != 3 || version[0] < 1 || version[1] < 0 || version[2] < 0
                || (version[0] == 1 && version[1] < 4)
                || version[0] > latest.getMajor()
                || (version[0] == latest.getMajor() && version[1] > latest.getMinor())) {
            throw unsafe("Unsupported Kotlin metadata version " + Arrays.toString(version)
                    + "; supported versions are 1.4 through " + latest.getMajor() + "." + latest.getMinor());
        }
    }

    private static IllegalArgumentException unsafe(String message) {
        return new IllegalArgumentException(message);
    }

    /**
     * 公开 Km 模型会忽略未知 protobuf 字段，也尚未暴露 property contracts。
     * 使用与 writer 相同、固定版本的 schema 在写入前拒绝这些不可无损表达的输入。
     */
    private static void validateClassPayload(Metadata metadata) {
        MessageLite proto;
        MessageLite strings;
        if (metadata.k() == 1) {
            kotlin.Pair<kotlin.metadata.internal.metadata.jvm.deserialization.JvmNameResolver, ProtoBuf.Class> data =
                    JvmProtoBufUtil.readClassDataFrom(metadata.d1(), metadata.d2());
            proto = data.getSecond();
            strings = data.getFirst().getTypes();
        } else if (metadata.k() == 2 || metadata.k() == 5) {
            kotlin.Pair<kotlin.metadata.internal.metadata.jvm.deserialization.JvmNameResolver, ProtoBuf.Package> data =
                    JvmProtoBufUtil.readPackageDataFrom(metadata.d1(), metadata.d2());
            proto = data.getSecond();
            strings = data.getFirst().getTypes();
        } else if (metadata.k() == 3 && metadata.d1().length != 0) {
            kotlin.Pair<kotlin.metadata.internal.metadata.jvm.deserialization.JvmNameResolver, ProtoBuf.Function> data =
                    JvmProtoBufUtil.readFunctionDataFrom(metadata.d1(), metadata.d2());
            proto = data.getSecond();
            strings = data.getFirst().getTypes();
        } else return;
        Set<MessageLite> visited = Collections.newSetFromMap(new IdentityHashMap<MessageLite, Boolean>());
        validateProto(proto, visited, 0);
        validateProto(strings, visited, 0);
    }

    private static final ClassValue<List<ProtoChild>> PROTO_CHILDREN = new ClassValue<List<ProtoChild>>() {
        @Override protected List<ProtoChild> computeValue(Class<?> type) {
            List<ProtoChild> result = new ArrayList<ProtoChild>();
            for (Method getter : type.getMethods()) {
                if (!getter.getName().startsWith("get") || getter.getName().startsWith("getDefaultInstance")
                        || getter.getParameterTypes().length != 0 || getter.isBridge()) continue;
                Class<?> returned = getter.getReturnType();
                if (!MessageLite.class.isAssignableFrom(returned) && returned != List.class) continue;
                Method has = null;
                if (returned != List.class) {
                    try { has = type.getMethod("has" + getter.getName().substring(3)); }
                    catch (NoSuchMethodException ignored) { /* 非 optional 消息字段。 */ }
                }
                result.add(new ProtoChild(getter, has));
            }
            return result;
        }
    };

    private static final class ProtoChild {
        final Method getter;
        final Method has;
        ProtoChild(Method getter, Method has) { this.getter = getter; this.has = has; }
    }

    private static void validateProto(MessageLite node, Set<MessageLite> visited, int depth) {
        if (!visited.add(node)) return;
        if (depth > 256) throw unsafe("Excessive Kotlin metadata nesting");
        GeneratedMessageLite.Builder<?, ?> builder = (GeneratedMessageLite.Builder<?, ?>) node.toBuilder();
        if (!builder.getUnknownFields().isEmpty()) {
            throw unsafe("Unknown protobuf fields in Kotlin " + node.getClass().getSimpleName());
        }
        if (node instanceof ProtoBuf.Property) {
            ProtoBuf.Property property = (ProtoBuf.Property) node;
            if (property.hasGetterContract() || property.hasSetterContract()) {
                throw unsafe("Kotlin property contracts are not losslessly supported by the metadata writer");
            }
            if (property.hasExtension(JvmProtoBuf.propertySignature)) {
                validateProto(property.getExtension(JvmProtoBuf.propertySignature), visited, depth + 1);
            }
        } else if (node instanceof ProtoBuf.Class) {
            for (ProtoBuf.Property property : ((ProtoBuf.Class) node).getExtension(JvmProtoBuf.classLocalVariable)) {
                validateProto(property, visited, depth + 1);
            }
        } else if (node instanceof ProtoBuf.Package) {
            for (ProtoBuf.Property property : ((ProtoBuf.Package) node).getExtension(JvmProtoBuf.packageLocalVariable)) {
                validateProto(property, visited, depth + 1);
            }
        } else if (node instanceof ProtoBuf.Function) {
            ProtoBuf.Function function = (ProtoBuf.Function) node;
            if (function.hasExtension(JvmProtoBuf.methodSignature)) {
                validateProto(function.getExtension(JvmProtoBuf.methodSignature), visited, depth + 1);
            }
        } else if (node instanceof ProtoBuf.Constructor) {
            ProtoBuf.Constructor constructor = (ProtoBuf.Constructor) node;
            if (constructor.hasExtension(JvmProtoBuf.constructorSignature)) {
                validateProto(constructor.getExtension(JvmProtoBuf.constructorSignature), visited, depth + 1);
            }
        }
        try {
            for (ProtoChild child : PROTO_CHILDREN.get(node.getClass())) {
                if (child.has != null && !((Boolean) child.has.invoke(node))) continue;
                Object value = child.getter.invoke(node);
                if (value instanceof MessageLite) validateProto((MessageLite) value, visited, depth + 1);
                else if (value instanceof List<?>) {
                    for (Object element : (List<?>) value) {
                        if (element instanceof MessageLite) validateProto((MessageLite) element, visited, depth + 1);
                    }
                }
            }
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalArgumentException("Cannot validate Kotlin metadata schema", failure);
        }
    }

    private static void writeStrings(AnnotationVisitor annotation, String name, String[] values) {
        AnnotationVisitor array = annotation.visitArray(name);
        for (String value : values) array.visit(null, value);
        array.visitEnd();
    }

    private static final class MetadataFields extends AnnotationVisitor {
        private final Map<String, Object> values = new LinkedHashMap<String, Object>();

        MetadataFields() { super(Opcodes.ASM9); }

        @Override public void visit(String name, Object value) {
            if (!Arrays.asList("k", "mv", "bv", "d1", "d2", "xs", "pn", "xi").contains(name)) {
                throw unsafe("Unknown kotlin.Metadata field: " + name);
            }
            if (values.put(name, value) != null) throw unsafe("Duplicate kotlin.Metadata field: " + name);
        }

        @Override public AnnotationVisitor visitArray(final String name) {
            if (!"d1".equals(name) && !"d2".equals(name)) throw unsafe("Invalid metadata array: " + name);
            final List<String> entries = new ArrayList<String>();
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override public void visit(String ignored, Object value) {
                    if (!(value instanceof String)) throw unsafe("Non-string metadata array element");
                    entries.add((String) value);
                }
                @Override public void visitEnd() {
                    MetadataFields.this.visit(name, entries.toArray(new String[0]));
                }
            };
        }

        @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            throw unsafe("Unexpected nested annotation in kotlin.Metadata");
        }

        @Override public void visitEnum(String name, String descriptor, String value) {
            throw unsafe("Unexpected enum in kotlin.Metadata");
        }

        Metadata metadata() {
            return JvmMetadataUtil.Metadata((Integer) values.get("k"), (int[]) values.get("mv"),
                    (String[]) values.get("d1"), (String[]) values.get("d2"), (String) values.get("xs"),
                    (String) values.get("pn"), (Integer) values.get("xi"));
        }

        int[] bytecodeVersion() { return (int[]) values.get("bv"); }
    }

    /** 合并同名模块资源；相同 part 指向不同 facade 或 single/multifile 身份冲突时拒绝。 */
    static byte[] mergeModules(byte[] first, byte[] second) {
        try {
            BinaryMetadata left = BinaryMetadata.read(first);
            BinaryMetadata right = BinaryMetadata.read(second);
            validateVersion(left.version);
            validateVersion(right.version);
            validateModuleFields(left);
            validateModuleFields(right);
            KotlinModuleMetadata a = KotlinModuleMetadata.read(first);
            KotlinModuleMetadata b = KotlinModuleMetadata.read(second);
            for (Map.Entry<String, KmPackageParts> entry : b.getKmModule().getPackageParts().entrySet()) {
                KmPackageParts destination = a.getKmModule().getPackageParts().get(entry.getKey());
                if (destination == null) {
                    a.getKmModule().getPackageParts().put(entry.getKey(), entry.getValue());
                    continue;
                }
                for (String facade : entry.getValue().getFileFacades()) {
                    if (destination.getMultiFileClassParts().containsKey(facade)) {
                        throw unsafe("Conflicting Kotlin module part: " + facade);
                    }
                    if (!destination.getFileFacades().contains(facade)) destination.getFileFacades().add(facade);
                }
                for (Map.Entry<String, String> part : entry.getValue().getMultiFileClassParts().entrySet()) {
                    String existing = destination.getMultiFileClassParts().get(part.getKey());
                    if (destination.getFileFacades().contains(part.getKey())
                            || (existing != null && !existing.equals(part.getValue()))) {
                        throw unsafe("Conflicting Kotlin module facade for " + part.getKey());
                    }
                    destination.getMultiFileClassParts().put(part.getKey(), part.getValue());
                }
            }
            if (a.getVersion().compareTo(b.getVersion()) < 0) a.setVersion(b.getVersion());
            BinaryMetadata result = BinaryMetadata.read(a.write());
            List<WireField> leftExtras = moduleExtras(left);
            List<WireField> rightExtras = moduleExtras(right);
            if (!leftExtras.isEmpty() && !rightExtras.isEmpty()
                    && !Arrays.equals(WireField.write(leftExtras), WireField.write(rightExtras))) {
                throw unsafe("Cannot merge distinct optional-annotation name tables in same-named Kotlin modules");
            }
            List<WireField> combined = new ArrayList<WireField>();
            for (WireField field : WireField.read(result.payload)) {
                if (field.number < 4) combined.add(field);
            }
            combined.addAll(leftExtras.isEmpty() ? rightExtras : leftExtras);
            byte[] output = new BinaryMetadata(result.version, left.flags | right.flags, WireField.write(combined)).write();
            KotlinModuleMetadata.read(output);
            return output;
        } catch (IOException | RuntimeException failure) {
            throw new GradleException("Cannot safely merge .kotlin_module: " + failure.getMessage(), failure);
        }
    }

    /**
     * 重写 builtins 1.0.7 的 qualified-name 包索引。所有声明、注解及字符串常量原样保留，
     * 仅追加目标包段并更新原 PACKAGE 节点；因此不会把普通字符串误当成类名。
     */
    static byte[] relocateBuiltIns(byte[] source, Map<String, String> relocations, List<String> protectedPrefixes) {
        if (relocations.isEmpty()) return source;
        try {
            BinaryMetadata binary = BinaryMetadata.read(source);
            if (!Arrays.equals(binary.version, new int[]{1, 0, 7})) {
                throw unsafe("Unsupported .kotlin_builtins version " + Arrays.toString(binary.version));
            }
            if (KotlinCommonMetadata.read(source) == null) throw unsafe("Malformed .kotlin_builtins declarations");
            List<WireField> fields = WireField.read(binary.payload);
            for (WireField field : fields) {
                if (field.wire != 2 || field.number < 1 || field.number > 4) {
                    throw unsafe("Unsupported .kotlin_builtins package-fragment field " + field.number);
                }
            }
            List<WireField> result = relocateNameTables(fields, 1, 2,
                    new KotlinMetadataRelocator(relocations, protectedPrefixes), true);
            byte[] output = new BinaryMetadata(binary.version, binary.flags, WireField.write(result)).write();
            if (KotlinCommonMetadata.read(output) == null) throw unsafe("Malformed relocated .kotlin_builtins declarations");
            return output;
        } catch (IOException | RuntimeException failure) {
            throw new GradleException("Cannot safely relocate .kotlin_builtins: " + failure.getMessage(), failure);
        }
    }

    private static List<WireField> relocateNameTables(List<WireField> fields, int stringsNumber,
            int namesNumber, KotlinMetadataRelocator relocator, boolean required) {
            WireField stringsField = null;
            WireField namesField = null;
            for (WireField field : fields) {
                if (field.number == stringsNumber) {
                    if (stringsField != null) throw unsafe("Duplicate builtins string table");
                    stringsField = field;
                } else if (field.number == namesNumber) {
                    if (namesField != null) throw unsafe("Duplicate builtins qualified-name table");
                    namesField = field;
                }
            }
            if (stringsField == null && namesField == null && !required) return fields;
            if (stringsField == null || namesField == null) throw unsafe("Missing Kotlin metadata name tables");
            List<WireField> strings = WireField.read(stringsField.bytes);
            List<String> stringValues = new ArrayList<String>();
            Map<String, Integer> stringIndices = new LinkedHashMap<String, Integer>();
            for (WireField string : strings) {
                if (string.number != 1 || string.wire != 2) throw unsafe("Unknown builtins string-table field");
                String value = decodeUtf8(string.bytes);
                if (!stringIndices.containsKey(value)) stringIndices.put(value, stringValues.size());
                stringValues.add(value);
            }
            List<WireField> names = WireField.read(namesField.bytes);
            List<QualifiedName> original = new ArrayList<QualifiedName>();
            for (WireField name : names) {
                if (name.number != 1 || name.wire != 2) throw unsafe("Unknown builtins qualified-name-table field");
                QualifiedName parsed = QualifiedName.read(name.bytes);
                if (parsed.shortName < 0 || parsed.shortName >= strings.size()) throw unsafe("Invalid builtins string index");
                original.add(parsed);
            }
            List<QualifiedName> rewritten = new ArrayList<QualifiedName>();
            for (QualifiedName name : original) rewritten.add(new QualifiedName(name.parent, name.shortName, name.kind));
            String[] packageNames = new String[original.size()];
            Map<String, Integer> helpers = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < original.size(); i++) {
                QualifiedName name = original.get(i);
                validateParents(i, original);
                if (name.kind != 1) continue;
                String oldName = packageName(i, original, stringValues, packageNames, 0);
                String newName = relocator.dottedName(oldName);
                // 即使子包受保护，也重建其原父链，避免被已经搬迁的祖先改变。
                int separator = newName.lastIndexOf('.');
                String parent = separator < 0 ? "" : newName.substring(0, separator);
                String shortName = newName.substring(separator + 1);
                rewritten.set(i, new QualifiedName(appendPackage(parent, rewritten, helpers, strings, stringIndices),
                        appendString(shortName, strings, stringIndices), 1));
            }
            List<WireField> rewrittenNames = new ArrayList<WireField>();
            for (QualifiedName name : rewritten) rewrittenNames.add(WireField.bytes(1, name.write()));
            List<WireField> result = new ArrayList<WireField>();
            for (WireField field : fields) {
                result.add(field == stringsField ? WireField.bytes(stringsNumber, WireField.write(strings))
                        : field == namesField ? WireField.bytes(namesNumber, WireField.write(rewrittenNames)) : field);
            }
            return result;
    }

    private static int appendString(String value, List<WireField> strings, Map<String, Integer> indices) {
        Integer existing = indices.get(value);
        if (existing != null) return existing;
        int index = strings.size();
        strings.add(WireField.bytes(1, value.getBytes(StandardCharsets.UTF_8)));
        indices.put(value, index);
        return index;
    }

    private static int appendPackage(String name, List<QualifiedName> names, Map<String, Integer> helpers,
                                     List<WireField> strings, Map<String, Integer> stringIndices) {
        if (name.isEmpty()) return -1;
        Integer existing = helpers.get(name);
        if (existing != null) return existing;
        int separator = name.lastIndexOf('.');
        int parent = appendPackage(separator < 0 ? "" : name.substring(0, separator), names, helpers, strings, stringIndices);
        int index = names.size();
        names.add(new QualifiedName(parent, appendString(name.substring(separator + 1), strings, stringIndices), 1));
        helpers.put(name, index);
        return index;
    }

    private static String packageName(int index, List<QualifiedName> names, List<String> strings,
                                      String[] cache, int depth) {
        if (depth > 256) throw unsafe("Excessive builtins package nesting");
        if (cache[index] != null) return cache[index];
        QualifiedName name = names.get(index);
        if (name.kind != 1) throw unsafe("Class node used as builtins package parent");
        String shortName = strings.get(name.shortName);
        if (shortName.isEmpty() || shortName.indexOf('.') >= 0 || shortName.indexOf('/') >= 0) {
            throw unsafe("Invalid builtins package segment");
        }
        String parent = name.parent == -1 ? "" : packageName(name.parent, names, strings, cache, depth + 1);
        cache[index] = parent.isEmpty() ? shortName : parent + "." + shortName;
        return cache[index];
    }

    private static void validateParents(int index, List<QualifiedName> names) {
        int depth = 0;
        while (index != -1) {
            if (index < 0 || index >= names.size()) throw unsafe("Invalid builtins parent index");
            if (++depth > 256) throw unsafe("Cyclic or excessive builtins qualified-name nesting");
            index = names.get(index).parent;
        }
    }

    private static final class QualifiedName {
        final int parent;
        final int shortName;
        final int kind;

        QualifiedName(int parent, int shortName, int kind) {
            this.parent = parent;
            this.shortName = shortName;
            this.kind = kind;
        }

        static QualifiedName read(byte[] bytes) {
            int parent = -1;
            int shortName = -1;
            int kind = 1;
            int seen = 0;
            for (WireField field : WireField.read(bytes)) {
                if (field.wire != 0 || field.number < 1 || field.number > 3 || (seen & (1 << field.number)) != 0) {
                    throw unsafe("Unknown or duplicate builtins qualified-name field");
                }
                seen |= 1 << field.number;
                if (field.number == 1) parent = (int) field.value;
                if (field.number == 2) shortName = (int) field.value;
                if (field.number == 3) kind = (int) field.value;
            }
            if (shortName < 0 || kind < 0 || kind > 2) throw unsafe("Invalid builtins qualified-name record");
            return new QualifiedName(parent, shortName, kind);
        }

        byte[] write() {
            List<WireField> fields = new ArrayList<WireField>();
            if (parent != -1) fields.add(WireField.integer(1, parent));
            fields.add(WireField.integer(2, shortName));
            fields.add(WireField.integer(3, kind));
            return WireField.write(fields);
        }
    }

    private static void validateModuleFields(BinaryMetadata binary) {
        if ((binary.flags & ~1) != 0) throw unsafe("Unknown .kotlin_module flags");
        int namesSeen = 0;
        List<WireField> fields = WireField.read(binary.payload);
        int jvmPackages = 0;
        for (WireField field : fields) if (field.number == 3) jvmPackages++;
        for (WireField field : fields) {
            if (field.number == 1 && field.wire == 2) validatePackageParts(field.bytes, jvmPackages);
            else if (field.number == 3 && field.wire == 2) decodeUtf8(field.bytes);
            else if ((field.number == 4 || field.number == 5) && field.wire == 2) {
                if ((namesSeen & (1 << field.number)) != 0) throw unsafe("Duplicate Kotlin module name table");
                namesSeen |= 1 << field.number;
            } else if (field.number == 6 && field.wire == 2) {
                WireField.read(field.bytes); // class IDs 由公共读取器与名称表验证。
            } else if (field.number == 16 && field.wire == 2) {
                validateCommonAnnotationClass(field.bytes);
            } else throw unsafe("Unsupported .kotlin_module field " + field.number);
        }
    }

    private static List<WireField> moduleExtras(BinaryMetadata binary) {
        List<WireField> result = new ArrayList<WireField>();
        boolean nonempty = false;
        for (WireField field : WireField.read(binary.payload)) {
            if (field.number >= 4) {
                result.add(field);
                nonempty |= field.bytes.length != 0;
            }
        }
        return nonempty ? result : Collections.<WireField>emptyList();
    }

    private static void validateCommonAnnotationClass(byte[] source) {
        for (WireField field : WireField.read(source)) {
            if (field.number == 33 || field.number == 102 || field.number == 103
                    || (field.number >= 100 && field.number != 101 && field.number != 104 && field.number != 150)) {
                throw unsafe("Unsupported optional-annotation class extension " + field.number);
            }
            if (field.number == 8 || field.number == 9 || field.number == 10 || field.number == 11) {
                if (field.wire != 2) throw unsafe("Invalid optional-annotation member");
                for (WireField member : WireField.read(field.bytes)) {
                    if (member.number == 100 || member.number == 101
                            || ((field.number == 8 || field.number == 10 || field.number == 11) && member.number == 32)
                            || (field.number == 9 && member.number == 33)) {
                        throw unsafe("Optional annotations with JVM signatures or opaque compiler-plugin data are unsupported");
                    }
                }
            }
        }
    }

    private static void validatePackageParts(byte[] bytes, int jvmPackages) {
        int packageNames = 0;
        int ordinaryNames = 0;
        int relocatedNames = 0;
        int facades = 0;
        List<Integer> ordinaryFacadeIds = new ArrayList<Integer>();
        List<Integer> relocatedFacadeIds = new ArrayList<Integer>();
        List<Integer> packageIds = new ArrayList<Integer>();
        for (WireField field : WireField.read(bytes)) {
            if (field.number == 1 || field.number == 2 || field.number == 4 || field.number == 5) {
                if (field.wire != 2) throw unsafe("Invalid Kotlin module package-parts string");
                decodeUtf8(field.bytes);
                if (field.number == 1) packageNames++;
                if (field.number == 2) ordinaryNames++;
                if (field.number == 4) facades++;
                if (field.number == 5) relocatedNames++;
            } else if (field.number == 3 || field.number == 6 || field.number == 7) {
                if (field.wire != 0 && field.wire != 2) throw unsafe("Invalid Kotlin module package-parts index");
                List<Integer> destination = field.number == 3 ? ordinaryFacadeIds
                        : field.number == 6 ? packageIds : relocatedFacadeIds;
                if (field.wire == 0) destination.add(checkedIndex(field.value));
                else {
                    int[] cursor = {0};
                    while (cursor[0] < field.bytes.length) destination.add(checkedIndex(WireField.varint(field.bytes, cursor)));
                }
            } else throw unsafe("Unknown Kotlin module package-parts field " + field.number);
        }
        if (packageNames != 1) throw unsafe("Missing or duplicate Kotlin module package name");
        if (ordinaryFacadeIds.size() > ordinaryNames || relocatedFacadeIds.size() > relocatedNames
                || packageIds.size() > relocatedNames || (relocatedNames > 0 && packageIds.isEmpty())) {
            throw unsafe("Invalid Kotlin module package-parts index counts");
        }
        for (int index : ordinaryFacadeIds) if (index > facades) throw unsafe("Invalid Kotlin module multifile facade index");
        for (int index : relocatedFacadeIds) if (index > facades) throw unsafe("Invalid Kotlin module JVM facade index");
        for (int index : packageIds) if (index >= jvmPackages) throw unsafe("Invalid Kotlin module JVM package index");
    }

    private static int checkedIndex(long index) {
        if (index < 0 || index > Integer.MAX_VALUE) throw unsafe("Invalid Kotlin module unsigned index");
        return (int) index;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw unsafe("Malformed UTF-8 in Kotlin metadata resource");
        }
    }

    private static final class BinaryMetadata {
        final int[] version;
        final int flags;
        final byte[] payload;

        BinaryMetadata(int[] version, int flags, byte[] payload) {
            this.version = version;
            this.flags = flags;
            this.payload = payload;
        }

        static BinaryMetadata read(byte[] bytes) throws IOException {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            int size = input.readInt();
            if (size != 3) throw unsafe("Unsupported Kotlin metadata version header length: " + size);
            int[] version = new int[size];
            for (int i = 0; i < size; i++) version[i] = input.readInt();
            int flags = hasFlags(version) ? input.readInt() : 0;
            byte[] payload = new byte[input.available()];
            input.readFully(payload);
            return new BinaryMetadata(version, flags, payload);
        }

        byte[] write() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(version.length);
            for (int component : version) output.writeInt(component);
            if (hasFlags(version)) output.writeInt(flags);
            output.write(payload);
            return bytes.toByteArray();
        }

        private static boolean hasFlags(int[] version) {
            return version[0] > 1 || (version[0] == 1 && version[1] >= 4);
        }
    }

    /** 有界 protobuf wire 读取器：保留未改写字段的原始字节，拒绝非法 tag、长度及 groups。 */
    private static final class WireField {
        final int number;
        final int wire;
        final long value;
        final byte[] bytes;
        final byte[] encoded;

        private WireField(int number, int wire, long value, byte[] bytes, byte[] encoded) {
            this.number = number;
            this.wire = wire;
            this.value = value;
            this.bytes = bytes;
            this.encoded = encoded;
        }

        static List<WireField> read(byte[] source) {
            List<WireField> fields = new ArrayList<WireField>();
            int[] cursor = {0};
            while (cursor[0] < source.length) {
                int start = cursor[0];
                long tag = varint(source, cursor);
                int wire = (int) (tag & 7);
                int number = (int) (tag >>> 3);
                if (tag >>> 32 != 0 || number < 1 || number > 0x1fffffff) throw unsafe("Invalid protobuf tag");
                long value = 0;
                byte[] bytes = null;
                if (wire == 0) value = varint(source, cursor);
                else {
                    long length;
                    if (wire == 1) length = 8;
                    else if (wire == 5) length = 4;
                    else if (wire == 2) length = varint(source, cursor);
                    else throw unsafe("Unsupported protobuf wire type " + wire);
                    if (length < 0 || length > source.length - cursor[0]) throw unsafe("Truncated protobuf field");
                    bytes = Arrays.copyOfRange(source, cursor[0], cursor[0] + (int) length);
                    cursor[0] += (int) length;
                }
                fields.add(new WireField(number, wire, value, bytes, Arrays.copyOfRange(source, start, cursor[0])));
            }
            return fields;
        }

        static long varint(byte[] source, int[] cursor) {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (cursor[0] >= source.length) throw unsafe("Truncated protobuf varint");
                int next = source[cursor[0]++] & 255;
                if (shift == 63 && next > 1) throw unsafe("Overflowing protobuf varint");
                value |= (long) (next & 127) << shift;
                if ((next & 128) == 0) return value;
            }
            throw unsafe("Overflowing protobuf varint");
        }

        static WireField bytes(int number, byte[] payload) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writeVarint(output, ((long) number << 3) | 2);
            writeVarint(output, payload.length);
            output.write(payload, 0, payload.length);
            return new WireField(number, 2, 0, payload, output.toByteArray());
        }

        static WireField integer(int number, int value) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writeVarint(output, (long) number << 3);
            writeVarint(output, value);
            return new WireField(number, 0, value, null, output.toByteArray());
        }

        static byte[] write(List<WireField> fields) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (WireField field : fields) output.write(field.encoded, 0, field.encoded.length);
            return output.toByteArray();
        }

        private static void writeVarint(ByteArrayOutputStream output, long value) {
            while ((value & ~127L) != 0) {
                output.write(((int) value & 127) | 128);
                value >>>= 7;
            }
            output.write((int) value);
        }
    }
}
