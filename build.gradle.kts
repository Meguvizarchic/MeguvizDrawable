plugins {
    base
    // On the build classpath only, so AGP's built-in Kotlin uses this compiler for the bridge modules.
    // The Java-only modules compile no Kotlin and are unaffected.
    // AGP must be loaded in the same class loader as the Kotlin plugin it integrates with.
    id("com.android.library") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
}

allprojects {
    group = "io.github.jichuo1.meguviz"
}

tasks.named("check") {
    // Every module the selected profile includes: core always, plus the Android and bridge modules.
    dependsOn(provider { subprojects.map { "${it.path}:check" } })
}

// Central Portal upload bundle.
// The zip is a Maven repository layout of the Central-ready modules with signatures and checksums.
// Run: gradlew -Pmeguviz.profile=bridges -Pmeguviz.sign=true -Pmeguviz.againstPublished=true centralBundle
// :lumen is deliberately absent: lumen-engine is published only through JitPack, and a Central
// artifact must not depend on one that Central consumers cannot resolve.
// :core and :android are absent too: 1.0.0 of both is already on Central and a release cannot be
// replaced. Their working-tree code moves on as 1.2.0 (BindingScope.claim, FlightCache, poster); 1.1.0 was never published.
val centralModules = listOf(":compose", ":meguviz-android", ":meguviz-ui")

val cleanCentralMaven = tasks.register<Delete>("cleanCentralMaven") {
    delete(layout.buildDirectory.dir("central-maven"))
}

tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Builds the Maven Central upload bundle for the Central-ready modules."
    dependsOn(cleanCentralMaven)
    centralModules.forEach { dependsOn("$it:publishCandidatePublicationToCentralBundleRepository") }
    subprojects.forEach { module ->
        module.tasks.matching { it.name == "publishCandidatePublicationToCentralBundleRepository" }
            .configureEach { mustRunAfter(cleanCentralMaven) }
    }
    doFirst {
        check(providers.gradleProperty("meguviz.sign").orNull == "true") { "centralBundle requires -Pmeguviz.sign=true" }
        check(providers.gradleProperty("meguviz.againstPublished").orNull == "true") {
            "centralBundle requires -Pmeguviz.againstPublished=true so the bridges are built against the released base library"
        }
    }
    from(layout.buildDirectory.dir("central-maven")) { exclude("**/maven-metadata.xml*") }
    destinationDirectory.set(layout.buildDirectory.dir("central"))
    archiveFileName.set("meguviz-drawable-bridges-${project(":compose").version}-central-bundle.zip")
}
