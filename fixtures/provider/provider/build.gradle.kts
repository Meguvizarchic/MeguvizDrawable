plugins { `java-library` }

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
tasks.withType<JavaCompile>().configureEach { options.release = 8 }
dependencies { api("io.github.jichuo1.meguviz:meguviz-drawable-core:1.2.0") }

val contractTest = tasks.register<JavaExec>("contractTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "example.provider.ProviderContract"
}
tasks.test { enabled = false }
tasks.check { dependsOn(contractTest) }
