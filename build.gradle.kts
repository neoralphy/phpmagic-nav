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
version = "0.2.1"

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

    // --- Plugin signing (JetBrains Marketplace requires signed uploads) --------------------------
    // `signPlugin` signs build/distributions/<version>.zip with the vendor's certificate before
    // `publishPlugin` uploads it. The `zipSigner()` dependency (declared above) provides the signer.
    // Credentials are read from the environment so nothing secret ever lives in the repo - set these
    // three before running `./gw signPlugin` / `./gw publishPlugin`:
    //   CERTIFICATE_CHAIN      the vendor certificate chain (PEM, e.g. `cat chain.crt`)
    //   PRIVATE_KEY            the matching private key (PEM)
    //   PRIVATE_KEY_PASSWORD   the private key's passphrase
    // Generate a chain/key once per vendor per the JetBrains guide:
    //   https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // `./gw publishPlugin` uploads the signed build/distributions/<version>.zip to the JetBrains
    // Marketplace. The token is read from the PUBLISH_TOKEN env var (a Marketplace Hub permanent
    // token, get one at https://plugins.jetbrains.com/author/me/tokens) so it never lives in the
    // repo - do NOT paste a token value here.
    //
    // Channel "beta" (matching hotpath/callscape's first-upload posture): a non-default channel is
    // invisible to normal users (installing needs a custom repo URL), and a brand-new plugin is
    // additionally unlisted until JetBrains approves the first submission. For the real public
    // launch, drop `channels` (or set it to listOf("")/listOf("default")) so it publishes to the
    // default channel that ordinary Marketplace search and one-click install use.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf("beta")
    }
}

// `./gw runPhpStorm` launches a PhpStorm sandbox with the plugin loaded for eyeballing - open a PHP
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
