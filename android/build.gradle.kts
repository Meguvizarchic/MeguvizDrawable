plugins {
    id("com.android.library")
    `maven-publish`
    signing
}

version = "1.2.0"
description = "Android Drawable implementation and View binding adapters built on meguviz-drawable-core."

android {
    namespace = "io.github.jichuo1.meguviz.drawable.android"
    compileSdk = 37
    defaultConfig {
        minSdk = 21
        testApplicationId = "io.github.jichuo1.meguviz.drawable.contracttest"
        testInstrumentationRunner = "io.github.jichuo1.meguviz.drawable.android.test.ContractInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    publishing { singleVariant("release") { withSourcesJar(); withJavadocJar() } }
}

dependencies {
    api(project(":core"))
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
                artifactId = "meguviz-drawable-android"
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
