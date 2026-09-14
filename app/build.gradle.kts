plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    jacoco
}

android {
    namespace = "com.cvar1984.megaverse"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cvar1984.megaverse"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.navigation)
    implementation(libs.core.splashscreen)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    testImplementation(libs.junit)
}

/**
 * Coverage over the code a JVM test can actually reach: the sky maths, the
 * catalogue and the settings ring. The Compose screens and the canvas drawing are
 * left out because a unit test cannot exercise them, and counting them would only
 * dilute the number that says whether the maths is tested.
 */
tasks.register<JacocoReport>("coverage") {
    dependsOn("testDebugUnitTest")
    reports {
        html.required = true
        xml.required = true
    }
    sourceDirectories.setFrom(files("src/main/java"))

    // Taken from the compile task's own outputs rather than a hardcoded path:
    // where the compiler drops its classes is the Android plugin's business, and a
    // bare fileTree over build/intermediates makes Gradle see an undeclared
    // dependency on every other task that writes in there.
    classDirectories.setFrom(
        tasks.named("compileDebugKotlin").map { compile ->
            compile.outputs.files.asFileTree.matching {
                include("com/cvar1984/megaverse/sky/**")
                include("com/cvar1984/megaverse/presentation/Settings*")
            }
        }
    )
    executionData.setFrom(
        layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    )
}
