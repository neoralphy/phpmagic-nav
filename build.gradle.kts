import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("java")
    // 2.2.x is required to read the Kotlin 2.2 metadata in the 2025.3 platform jars.
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "eu.neoralphy"
version = "0.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Target PhpStorm directly: it BUNDLES the PHP plugin (com.jetbrains.php), so we get PHP PSI
        // on the compile/test classpath without the marketplace-plugin version-pinning pain that a
        // non-PHP base (e.g. IDEA Ultimate) would force. PHP-only spike, so this is all we need.
        create(IntelliJPlatformType.PhpStorm, "2025.3")
        bundledPlugin("com.jetbrains.php")

        pluginVerifier()
        zipSigner()

        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
    // The platform test framework's assertions are built on opentest4j.
    testRuntimeOnly("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // Minimum 2025.3 (build 253). No upper bound: a provider yielding null OMITS the
            // until-build attribute entirely, so the plugin installs on 2025.3 and every future
            // build (an empty string would instead write an invalid until-build="").
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // PhpStorm is the only product this plugin targets (it depends on the bundled PHP
            // plugin). Verify the pinned 2025.3 GA build...
            ide(IntelliJPlatformType.PhpStorm, "2025.3")
            // ...plus the newest RELEASE and EAP of PhpStorm, because the Marketplace's server-side
            // verifier checks the plugin's ENTIRE compatibility range (sinceBuild=253, no upper
            // bound → every newer release AND current EAPs), not just the pinned build. Catching an
            // API that went @ApiStatus.Internal in a newer SDK here avoids a server-side rejection.
            select {
                types = listOf(IntelliJPlatformType.PhpStorm)
                channels = listOf(ProductRelease.Channel.RELEASE, ProductRelease.Channel.EAP)
                sinceBuild = "253"
                untilBuild = "999.*"
            }
        }
    }
}

// `./gw runPhpStorm` launches a PhpStorm sandbox with the plugin loaded for eyeballing — open a PHP
// file with a Stringable class and a (string) cast / echo to see the gutter markers.
intellijPlatformTesting {
    runIde {
        register("runPhpStorm") {
            type = IntelliJPlatformType.PhpStorm
            version = "2025.3"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

tasks {
    // Spins up a headless IDE and is unnecessary here; disable to keep the build lean.
    buildSearchableOptions {
        enabled = false
    }
}
