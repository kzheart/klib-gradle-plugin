package me.kzheart.klib.gradle;

import kotlin.metadata.KmClassifier;
import kotlin.metadata.KmTypeAlias;
import kotlin.metadata.internal.metadata.jvm.deserialization.ClassMapperLite;
import kotlin.metadata.jvm.KotlinClassMetadata;
import org.gradle.api.GradleException;
import org.objectweb.asm.Type;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/** 复用当前 metadata 库的 JVM 映射及配套 stdlib 的正式 typealias 声明。 */
final class KotlinJvmTypeMappings {
    private static final Set<String> JDK_ALIASES = aliases();
    private KotlinJvmTypeMappings() { }
    static boolean isJdkType(String classId) {
        // 通用 Array 的元素由 KmType.arguments 单独检查，JVM 中没有 kotlin.Array 类。
        return classId.equals("kotlin/Array") || jdkDescriptor(ClassMapperLite.mapClass(classId)) || JDK_ALIASES.contains(classId);
    }
    private static boolean jdkDescriptor(String descriptor) {
        Type type = Type.getType(descriptor);
        if (type.getSort() == Type.ARRAY) type = type.getElementType();
        return type.getSort() != Type.OBJECT || ApiBoundaryVerifier.jdk(type.getInternalName());
    }
    private static Set<String> aliases() {
        Set<String> result = new HashSet<String>();
        for (String pkg : new String[] {"kotlin", "kotlin/collections", "kotlin/text"}) {
            String resource = "/" + pkg + "/TypeAliasesKt.class";
            try (InputStream input = kotlin.Unit.class.getResourceAsStream(resource)) {
                if (input == null) throw new GradleException("Missing stdlib JVM typealias metadata: " + resource);
                KotlinClassMetadata metadata = KotlinMetadataRelocator.readMetadata(
                        KlibShadeJarTask.readBounded(input, KlibShadeJarTask.MAX_ENTRY_BYTES, resource));
                if (!(metadata instanceof KotlinClassMetadata.FileFacade)) throw new GradleException("Unsupported stdlib typealias container: " + resource);
                for (KmTypeAlias alias : ((KotlinClassMetadata.FileFacade) metadata).getKmPackage().getTypeAliases()) {
                    KmClassifier classifier = alias.getExpandedType().getClassifier();
                    if (classifier instanceof KmClassifier.Class && jdkDescriptor(ClassMapperLite.mapClass(((KmClassifier.Class) classifier).getName())))
                        result.add(pkg + "/" + alias.getName());
                }
            } catch (IOException failure) { throw new GradleException("Cannot inspect stdlib JVM aliases", failure); }
        }
        return result;
    }
}
