package me.kzheart.klib.gradle;

import org.gradle.api.GradleException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 原始输入和最终制品共用的路径、宿主与描述文件边界。 */
final class JarBoundary {
    private JarBoundary() { }
    static void packages(List<String> packages) {
        for (String name : packages) if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
            throw new GradleException("Invalid boundary package: " + name);
    }
    static boolean within(String name, String prefix) { return name.equals(prefix) || name.startsWith(prefix + "/"); }
    static boolean overlaps(String first, String second) { return within(first, second) || within(second, first); }
    static String logicalPath(String path) {
        if (path.startsWith("/") || path.contains("\\") || path.contains("//")) throw new GradleException("Unsafe JAR entry: " + path);
        for (String part : path.split("/")) if (part.equals("..") || part.equals(".")) throw new GradleException("Unsafe JAR entry: " + path);
        if (path.startsWith("META-INF/versions/")) {
            String tail = path.substring("META-INF/versions/".length());
            int slash = tail.indexOf('/');
            if (slash < 0 || !tail.substring(0, slash).matches("[1-9][0-9]*")) throw new GradleException("Invalid multi-release entry: " + path);
            try { if (Integer.parseInt(tail.substring(0, slash)) < 9) throw new NumberFormatException(); }
            catch (NumberFormatException failure) { throw new GradleException("Invalid multi-release version: " + path); }
            return tail.substring(slash + 1);
        }
        return path;
    }
    static void entry(String path, byte[] bytes, List<String> hosts, boolean paper) {
        String logical = logicalPath(path);
        String lower = logical.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar") || lower.endsWith(".zip")) throw new GradleException("Nested archives are not supported: " + path);
        if (paper) {
            host(logical, hosts);
            if (logical.endsWith(".class")) host(new ClassReader(bytes).getClassName() + ".class", hosts);
        }
    }
    private static void host(String path, List<String> hosts) {
        for (String pkg : hosts) if (within(path, pkg.replace('.', '/')))
            throw new GradleException("Paper host package must not be embedded: " + path);
    }
    static void relocation(String source, String target, List<String> hosts) {
        for (String host : hosts) if (overlaps(source.replace('.', '/'), host.replace('.', '/'))
                || overlaps(target.replace('.', '/'), host.replace('.', '/')))
            throw new GradleException("Paper host package must not be relocated or covered by a parent: " + source + " -> " + target);
    }
    static String strictUtf8(byte[] bytes) { return strictUtf8(bytes, "plugin.yml"); }
    static String strictUtf8(byte[] bytes, String descriptor) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException failure) { throw new GradleException(descriptor + " must be strict UTF-8", failure); }
    }
    static boolean velocityDescriptor(String path) {
        String logical = logicalPath(path);
        return "velocity-plugin.json".equalsIgnoreCase(logical.substring(logical.lastIndexOf('/') + 1));
    }
    static void velocityJson(byte[] bytes, String version) {
        String json = strictUtf8(bytes, "velocity-plugin.json");
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT)
                throw new GradleException("velocity-plugin.json must be a JSON object");
            Map<String, String> fields = jsonObject(reader, true);
            if (reader.peek() != JsonToken.END_DOCUMENT)
                throw new GradleException("velocity-plugin.json must contain one JSON object");
            for (String field : new String[] {"id", "main", "version"})
                if (!fields.containsKey(field) || fields.get(field).trim().isEmpty())
                    throw new GradleException("velocity-plugin.json requires a nonempty string " + field);
            if (!version.equals(fields.get("version")))
                throw new GradleException("velocity-plugin.json version must equal project.version: expected "
                        + version + ", got " + fields.get("version"));
        } catch (IOException | IllegalStateException failure) {
            throw new GradleException("velocity-plugin.json must be strict JSON", failure);
        }
    }
    private static Map<String, String> jsonObject(JsonReader reader, boolean root) throws IOException {
        Map<String, String> strings = new LinkedHashMap<String, String>();
        Set<String> keys = new HashSet<String>();
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            jsonString(key);
            if (!keys.add(key)) throw new GradleException("Duplicate velocity-plugin.json key: " + key);
            if (reader.peek() == JsonToken.STRING) {
                String value = reader.nextString(); jsonString(value);
                if (root) strings.put(key, value);
            } else jsonValue(reader);
        }
        reader.endObject();
        return strings;
    }
    private static void jsonValue(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT: jsonObject(reader, false); break;
            case BEGIN_ARRAY:
                reader.beginArray();
                while (reader.hasNext()) jsonValue(reader);
                reader.endArray(); break;
            case STRING: jsonString(reader.nextString()); break;
            case NUMBER: reader.nextString(); break;
            case BOOLEAN: reader.nextBoolean(); break;
            case NULL: reader.nextNull(); break;
            default: throw new GradleException("Invalid velocity-plugin.json value");
        }
    }
    private static void jsonString(String value) {
        if (value.indexOf('\0') >= 0 || value.contains("${")
                || Pattern.compile("@[A-Za-z_][A-Za-z0-9_.-]*@").matcher(value).find())
            throw new GradleException("velocity-plugin.json contains an unexpanded placeholder or NUL");
    }
    static void pluginYaml(byte[] bytes, String version) {
        String yaml = strictUtf8(bytes);
        if (yaml.indexOf('\0') >= 0 || yaml.contains("${") || Pattern.compile("@[A-Za-z_][A-Za-z0-9_.-]*@").matcher(yaml).find())
            throw new GradleException("plugin.yml contains an unexpanded placeholder or NUL");
        Set<String> keys = new HashSet<String>();
        String actual = null;
        Pattern scalar = Pattern.compile("^([A-Za-z][A-Za-z0-9_-]*):(?:[ \\t]*(.*))?$");
        for (String line : yaml.split("\\r?\\n")) {
            if (line.isEmpty() || line.trim().startsWith("#")) continue;
            Matcher match = scalar.matcher(line);
            if (match.matches()) {
                if (!keys.add(match.group(1))) throw new GradleException("Duplicate plugin.yml key: " + match.group(1));
                if (match.group(1).equals("version")) {
                    actual = match.group(2).trim();
                    if (actual.startsWith("'") && actual.endsWith("'")) actual = actual.substring(1, actual.length() - 1).replace("''", "'");
                    else if (actual.startsWith("\"") && actual.endsWith("\"")) actual = actual.substring(1, actual.length() - 1);
                }
            } else if (!Character.isWhitespace(line.charAt(0))) {
                throw new GradleException("plugin.yml requires unquoted top-level keys in generated YAML: " + line);
            }
        }
        if (!version.equals(actual)) throw new GradleException("plugin.yml version must equal project.version: expected " + version + ", got " + actual);
        if (!keys.contains("name") || !keys.contains("main")) throw new GradleException("plugin.yml requires name and main");
    }
}
