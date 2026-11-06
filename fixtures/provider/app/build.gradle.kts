import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import java.security.MessageDigest

plugins { id("com.android.application") }

android {
    namespace = "example.provider.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.jichuo1.meguviz.drawable.providerfixture"
        minSdk = 21
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "example.provider.app.ProviderInstrumentation"
        testApplicationId = "io.github.jichuo1.meguviz.drawable.providerfixture.test"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
dependencies {
    implementation(project(":provider"))
    implementation("io.github.jichuo1.meguviz:meguviz-drawable-android:1.2.0")
}

tasks.register("verifyCandidateResolution") {
    doLast {
        val artifacts = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
        val modules = mutableSetOf<String>()
        var providers = 0
        for (artifact in artifacts) {
            when (val id = artifact.id.componentIdentifier) {
                is ProjectComponentIdentifier -> {
                    check(id.projectPath == ":provider") { "Unexpected project substitution: $id" }
                    providers++
                }
                is ModuleComponentIdentifier -> {
                    val candidate = rootProject.file("../../build/staging-maven/${id.group.replace('.', '/')}/${id.module}/${id.version}/${artifact.file.name}")
                    check(candidate.isFile && candidate.readBytes().contentEquals(artifact.file.readBytes())) {
                        "Resolved bytes differ from staged candidate: $id"
                    }
                    val hash = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes())
                        .joinToString("") { "%02x".format(it) }
                    println("candidate ${id.group}:${id.module}:${id.version} ${artifact.file.name} sha256=$hash")
                    modules.add("${id.group}:${id.module}:${id.version}")
                }
                else -> error("Unsupported dependency source: $id")
            }
        }
        check(providers == 1 && modules == setOf(
            "io.github.jichuo1.meguviz:meguviz-drawable-core:1.2.0",
            "io.github.jichuo1.meguviz:meguviz-drawable-android:1.2.0"
        ) && artifacts.size == 3) { "Unexpected provider dependency graph: $modules, providers=$providers" }
    }
}
