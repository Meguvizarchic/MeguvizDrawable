plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    `maven-publish`
    signing
}

version = "1.1.0"
description = "Compose Painter and state helpers that render Meguviz drawing recipes through the Android drawable backend."

android {
    namespace = "io.github.jichuo1.meguviz.drawable.compose"
    compileSdk = 37
    defaultConfig {
        // Compose UI's own floor; the rendering backend (meguviz-drawable-android) supports API 21.
        minSdk = 23
        testApplicationId = "io.github.jichuo1.meguviz.drawable.composetest"
        testInstrumentationRunner = "io.github.jichuo1.meguviz.drawable.compose.test.ComposeInstrumentation"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar(); withJavadocJar() } }
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

dependencies {
    // -Pmeguviz.againstPublished=true builds against the base library as released on Maven Central,
    // so a bridge cannot use API that exists only in this working tree (required for the Central bundle).
    if (providers.gradleProperty("meguviz.againstPublished").orNull == "true") {
        api(libs.drawable.android.released)
    } else {
        api(project(":android"))
    }
    implementation(platform(libs.compose.bom))
    api(libs.compose.runtime)
    api(libs.compose.ui.graphics)
    implementation(libs.compose.ui)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.foundation)
    androidTestImplementation(libs.androidx.activity)
}

// Bundle the license at the archive root, not as a runtime dependency.
tasks.withType<Zip>().matching { it.name == "bundleReleaseAar" || it.name == "sourceReleaseJar" }
    .configureEach {
        from(rootProject.file("LICENSE")) { into("META-INF") }
    }

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("candidate") {
                artifactId = "meguviz-drawable-compose"
                from(components["release"])
                pom {
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "staging"
                url = rootProject.layout.buildDirectory.dir("staging-maven").get().asFile.toURI()
            }
        }
    }
}

apply(from = rootProject.file("gradle/central-publishing.gradle.kts"))
