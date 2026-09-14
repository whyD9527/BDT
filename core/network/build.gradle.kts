plugins {
    alias(libs.plugins.bilibilias.android.library)
    alias(libs.plugins.bilibilias.android.koin)
    alias(libs.plugins.kotlin.plugin.serialization)
}

android {
    namespace = "com.imcys.bilibilias.network"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    api(libs.ktor.client.core)
    // api(libs.ktor.client.okhttp)
    api(libs.ktor.client.cio)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.serialization.kotlinx.json)
    api(libs.ktor.serialization.kotlinx.protobuf)
    api(libs.ktor.client.logging)

    // 本地 JVM 单元测试：不需要设备，`sh gradlew :core:network:testDebugUnitTest` 就能跑。
    // 目前覆盖签名相关的纯函数 —— 2026-09 的 wbi 回归事故正是这类测试该拦住的。
    testImplementation(libs.junit)
}