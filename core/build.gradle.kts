plugins {
    `java-library`
    `maven-publish`
    signing
}

version = "1.2.0"
description = "Immutable drawing recipes, state compilation and slot ownership for Android drawables (pure Java core)."

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
    options.encoding = "UTF-8"
}

tasks.named<Jar>("jar") {
    from(rootProject.file("LICENSE")) { into("META-INF") }
}

tasks.named<Jar>("sourcesJar") {
    from(rootProject.file("LICENSE")) { into("META-INF") }
}

val contractTest = tasks.register<JavaExec>("contractTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.github.jichuo1.meguviz.drawable.core.CoreContractTest"
}

// Broad randomized and repeated concurrency corpora are explicit, never part of the default check.
tasks.register<JavaExec>("contractStress") {
    group = "verification"
    description = "Runs the opt-in broad state, geometry and concurrency corpora."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.github.jichuo1.meguviz.drawable.core.CoreContractTest"
    systemProperty("meguviz.test.stress", "true")
}

// The current suite is an executable Java program, not a test-framework suite.
tasks.test {
    enabled = false
}

tasks.check {
    dependsOn(contractTest)
}

publishing {
    publications {
        create<MavenPublication>("candidate") {
            artifactId = "meguviz-drawable-core"
            from(components["java"])
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

apply(from = rootProject.file("gradle/central-publishing.gradle.kts"))
