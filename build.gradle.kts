import com.gradle.publish.PublishTask
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.WriteProperties
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.1.1"
}

group = "me.kzheart.klib"

val semanticVersion = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")
fun requiredVersionProperty(name: String): String {
    val value = providers.gradleProperty(name).orNull?.trim().orEmpty()
    if (!semanticVersion.matches(value)) {
        throw GradleException("$name must be a three-part semantic version")
    }
    return value
}

val pluginVersion = requiredVersionProperty("pluginVersion")
val klibVersion = requiredVersionProperty("klibVersion")
version = pluginVersion

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

val generateBundledKlibVersion = tasks.register<WriteProperties>("generateBundledKlibVersion") {
    destinationFile.set(layout.buildDirectory.file(
        "generated/klib-plugin-version/klib-gradle-plugin.properties"))
    property("libraryVersion", klibVersion)
}

tasks.processResources {
    from(generateBundledKlibVersion) {
        into("META-INF")
    }
}

tasks.withType<Jar>().configureEach {
    from(layout.projectDirectory.file("LICENSE")) {
        into("META-INF")
    }
    from(layout.projectDirectory.file("NOTICE")) {
        into("META-INF")
    }
}

gradlePlugin {
    website.set("https://github.com/kzheart/klib-gradle-plugin")
    vcsUrl.set("https://github.com/kzheart/klib-gradle-plugin")
    plugins {
        create("klib") {
            id = "me.kzheart.klib"
            implementationClass = "me.kzheart.klib.gradle.KlibPlugin"
            displayName = "Klib Minecraft Plugin Build Integration"
            description = "Builds Java 8 Bukkit and Paper plugins with generated metadata, " +
                "type-safe Klib module selection, dependency shading, and relocation."
            tags.set(listOf("minecraft", "bukkit", "paper", "java"))
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
    }
}

dependencies {
    implementation(gradleApi())
    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Klib Gradle Plugin")
            description.set("Gradle build integration for Klib Bukkit and Paper plugins.")
            url.set("https://github.com/kzheart/klib-gradle-plugin")
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("kzheart")
                    name.set("kzheart")
                    email.set("kzheartgyf@gmail.com")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/kzheart/klib-gradle-plugin.git")
                developerConnection.set("scm:git:ssh://git@github.com/kzheart/klib-gradle-plugin.git")
                url.set("https://github.com/kzheart/klib-gradle-plugin")
            }
        }
    }
}

tasks.register("validateGradlePluginPortal") {
    group = "verification"
    description = "Runs local plugin metadata, implementation, and TestKit validation."
    dependsOn("validatePlugins", "test")
}

val releaseTag = providers.gradleProperty("releaseTag")
    .orElse(providers.environmentVariable("GITHUB_REF_NAME"))
    .orElse("")
val portalApproved = providers.gradleProperty("pluginPortalPublicationApproved")
    .orElse(providers.environmentVariable("PLUGIN_PORTAL_PUBLICATION_APPROVED"))
val portalKey = providers.environmentVariable("GRADLE_PUBLISH_KEY")
val portalSecret = providers.environmentVariable("GRADLE_PUBLISH_SECRET")

tasks.named<PublishTask>("publishPlugins") {
    dependsOn("validateGradlePluginPortal")
    doFirst {
        if (validateOnly.get()) {
            logger.lifecycle("Plugin Portal validate-only mode: no version will be published.")
            return@doFirst
        }

        val expectedTag = "v$pluginVersion"
        val actualTag = releaseTag.get().trim()
        if (actualTag != expectedTag) {
            throw GradleException(
                "Gradle Plugin Portal release tag must be $expectedTag, " +
                    "got ${actualTag.ifEmpty { "<empty>" }}")
        }
        if (!portalApproved.map(String::toBoolean).getOrElse(false)) {
            throw GradleException(
                "Set PLUGIN_PORTAL_PUBLICATION_APPROVED=true to confirm a public release.")
        }
        if (!portalKey.isPresent || !portalSecret.isPresent) {
            throw GradleException(
                "GRADLE_PUBLISH_KEY and GRADLE_PUBLISH_SECRET are required for publication.")
        }
    }
}
