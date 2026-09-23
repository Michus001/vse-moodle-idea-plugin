import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    // ./gradlew verifyPlugin: checks the plugin against real IDE builds (catches classes that moved between
    // releases, e.g. JCEF split out of the core in 2026.2). Keep the newest release students use in the list.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion"))
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
}

// ./gradlew runIde262: sandbox with the newest IDE line (2026.2 moved JCEF into a separate plugin).
intellijPlatformTesting {
    runIde {
        register("runIde262") {
            type = IntelliJPlatformType.IntellijIdea
            version = "2026.2.3"
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
