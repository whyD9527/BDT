import com.google.protobuf.gradle.proto

plugins {
    alias(libs.plugins.bilibilias.android.library)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.bilibilias.android.koin)
}


android {
    namespace = "com.imcys.bilibilias.datastore"

    sourceSets {
        getByName("main") {
            val buildDir = layout.buildDirectory.get().asFile
            proto {
                srcDir("src/main/proto")
            }
            java {
                srcDir(buildDir.resolve("generated/source/proto/main/java"))
            }
            kotlin {
                srcDir(buildDir.resolve("generated/source/proto/main/kotlin"))
            }
        }
    }

}

// Setup protobuf configuration, generating lite Java and Kotlin classes
protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }

    generateProtoTasks {

        all().forEach { task ->
            task.builtins {
                register("java") {
                    option("lite")
                }
                register("kotlin") {
                    option("lite")
                }
            }
        }
    }

}

androidComponents.beforeVariants {
    android.sourceSets.findByName(it.name)?.let { sourceSet ->
        val buildDir = layout.buildDirectory.get().asFile
        sourceSet.java.srcDir(buildDir.resolve("generated/source/proto/${it.name}/java"))
        sourceSet.kotlin.srcDir(buildDir.resolve("generated/source/proto/${it.name}/kotlin"))
    }
}


dependencies {
    api(libs.protobuf.kotlin.lite)
    api(libs.androidx.datastore)
    api(libs.androidx.datastore.core)

    // 本地 JVM 单测（`sh gradlew :core:datastore:testDebugUnitTest`）：
    // 这个模块以前没有单测，于是 `AppSettingsSerializer` 里"兜底写错字段"这种
    // 复制粘贴级错误一直没人拦（2026-09-14 全量审计 H7）。
    testImplementation(libs.junit)
}