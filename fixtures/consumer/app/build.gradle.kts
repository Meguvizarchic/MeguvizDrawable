import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.security.MessageDigest

plugins { id("com.android.application") }

android {
    namespace = "io.github.jichuo1.meguviz.drawable.consumer"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.jichuo1.meguviz.drawable.consumer"
        minSdk = 21
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
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
    implementation("io.github.jichuo1.meguviz:meguviz-drawable-android:1.2.0")
}

tasks.register("verifyCandidateResolution") {
    doLast {
        val configuration = configurations.getByName("releaseRuntimeClasspath")
        val artifacts = configuration.resolvedConfiguration.resolvedArtifacts
        val actual = artifacts.map { artifact ->
            val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                ?: error("A project or non-module artifact was substituted: ${artifact.id}")
            val candidate = rootProject.file("../../build/staging-maven/${id.group.replace('.', '/')}/${id.module}/${id.version}/${artifact.file.name}")
            check(candidate.isFile && candidate.readBytes().contentEquals(artifact.file.readBytes())) {
                "Resolved bytes do not match the staged candidate: ${artifact.id}"
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes())
                .joinToString("") { "%02x".format(it) }
            println("candidate ${id.group}:${id.module}:${id.version} ${artifact.file.name} sha256=$hash")
            "${id.group}:${id.module}:${id.version}"
        }.toSet()
        val expected = setOf(
            "io.github.jichuo1.meguviz:meguviz-drawable-core:1.2.0",
            "io.github.jichuo1.meguviz:meguviz-drawable-android:1.2.0"
        )
        check(actual == expected) { "Unexpected runtime artifacts: $actual" }
        check(artifacts.size == expected.size) { "Unexpected duplicate runtime artifacts" }
    }
}
