plugins {
    id("com.android.library")
    `maven-publish`
    signing
}

version = "1.1.0"
description = "Narrow bridge that gives Lumen surface bindings drawable-scope ownership, idempotent updates and release."

android {
    namespace = "io.github.jichuo1.meguviz.drawable.lumen"
    compileSdk = 37
    defaultConfig {
        // lumen-engine declares API 27.
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
        testApplicationId = "io.github.jichuo1.meguviz.drawable.lumentest"
        testInstrumentationRunner = "io.github.jichuo1.meguviz.drawable.lumen.test.LumenInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar(); withJavadocJar() } }
}

dependencies {
    api(project(":android"))
    // BindingScope.claim exists from core 1.1.0; the released android 1.0.0 alone would resolve core 1.0.0 without it.
    api(project(":core"))
    api(libs.lumen.engine)
    testImplementation(libs.junit)
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
                artifactId = "meguviz-drawable-lumen"
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
