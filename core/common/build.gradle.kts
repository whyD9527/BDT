plugins {
    alias(libs.plugins.bilibilias.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.plugin.serialization)
}

android {
    namespace = "com.imcys.bilibilias.common"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":core:ui"))
    api(libs.androidx.core.ktx)
    api(libs.androidx.lifecycle.runtime.ktx)

    // 本地 JVM 单元测试（`sh gradlew :core:common:testDebugUnitTest`）。
    // 目前覆盖 AsRegexUtil —— 它处理的是"用户复制的文本"这种不可信输入：
    // 既出过 toLong() 溢出崩溃，也出过把非 B 站文本误认成 B 站链接的问题。
    testImplementation(libs.junit)
}