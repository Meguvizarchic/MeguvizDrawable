plugins {
    id("com.android.library")
    `maven-publish`
    signing
}

version = "1.2.0"
description = "MeguvizUI property adapter that gives a drawing recipe exclusive ownership of a View background."

android {
    namespace = "io.github.jichuo1.meguviz.drawable.ui"
    compileSdk = 37
    defaultConfig {
        // meguviz-ui-core declares API 27.
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
        testApplicationId = "io.github.jichuo1.meguviz.drawable.uitest"
        testInstrumentationRunner = "io.github.jichuo1.meguviz.drawable.ui.test.UiInstrumentation"
    }
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
    api(libs.meguviz.ui.core)
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
                artifactId = "meguviz-drawable-meguviz-ui"
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
