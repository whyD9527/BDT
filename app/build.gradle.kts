import com.imcys.bilibilias.buildlogic.BILIBILIASBuildType

plugins {
    alias(libs.plugins.bilibilias.android.application)
    alias(libs.plugins.bilibilias.android.koin)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias { libs.plugins.kotlin.parcelize }
}
val enabledPlayAppMode: String by project
val enabledAnalytics: String by project

android {
    namespace = "com.imcys.bilibilias"

    defaultConfig {
        targetSdk = 36
        applicationId = "com.whyd9527.bilibilias"
        versionCode = 324
        versionName = "3.2.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a","x86_64")
        }
    }
    signingConfigs {
        create("BILIBILIASSigningConfig") {
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    flavorDimensions += listOf("version")
    productFlavors {

        create("official") {
            dimension = "version"
            buildConfigField("boolean", "ENABLED_PLAY_APP_MODE", enabledPlayAppMode)
            signingConfig = signingConfigs.getByName("BILIBILIASSigningConfig")
            resValue("string", "app_channel", "Official")
        }

        create("alpha") {
            dimension = "version"
            // 本变体用于对外发布，因此**不再追加 .alpha 后缀**：
            // 包名为 com.whyd9527.bilibilias，版本号为 3.1.9（不带 -Alpha）。
            // 注意：这与历史构建（com.whyd9527.bilibilias.alpha）是不同的包，
            // 老版本需要卸载后才能安装；此后所有发布版都可直接覆盖升级。
            buildConfigField("boolean", "ENABLED_PLAY_APP_MODE", "false")
            resValue("string", "app_channel", "Release")
            // 动态签名配置
            val runnerTemp = System.getenv("RUNNER_TEMP")
            signingConfig = if (runnerTemp != null && file("$runnerTemp/mxjs-debug.jks").exists()) {
                // CI 环境
                signingConfigs.create("ci-alpha").apply {
                    storeFile = file("$runnerTemp/mxjs-debug.jks")
                    storePassword = System.getenv("ALPHA_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("ALPHA_KEY_ALIAS")
                    keyPassword = System.getenv("ALPHA_KEY_PASSWORD")
                    enableV3Signing = true
                    enableV4Signing = true
                }
            } else {
                // 本地环境
                signingConfigs.getByName("debug")
            }

        }

        // 提交Google Play使用
        create("beta") {
            dimension = "version"
            applicationIdSuffix = BILIBILIASBuildType.BETA.applicationIdSuffix
            versionNameSuffix = BILIBILIASBuildType.BETA.versionNameSuffix
            buildConfigField("boolean", "ENABLED_PLAY_APP_MODE", enabledPlayAppMode)
            signingConfig = signingConfigs.getByName("BILIBILIASSigningConfig")
            resValue("string", "app_channel", "Beta")

        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "ENABLED_PLAY_APP_MODE", enabledPlayAppMode)
            buildConfigField("boolean", "ENABLED_ANALYTICS", enabledAnalytics)
        }

        debug {
            buildConfigField("boolean", "ENABLED_PLAY_APP_MODE", enabledPlayAppMode)
            buildConfigField("boolean", "ENABLED_ANALYTICS", enabledAnalytics)
        }

    }

    val isDebug = gradle.startParameter.taskNames.any { it.contains("debug", true) }
    splits {
        abi {
            isEnable = !isDebug  // debug 时禁用，release 时启用
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }

    kotlin {
        compilerOptions {
            freeCompilerArgs.add("-XXLanguage:+ExplicitBackingFields")
        }
    }

}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))

    implementation(libs.ffmpeg.kit.x6kb)

    // 彩带
    implementation(libs.konfetti.compose)
    // 高斯模糊
    implementation(libs.compose.cloudy)

    // 分页
    implementation(libs.paging.compose)

    implementation(libs.device.compat)
    implementation(libs.androidx.documentfile)

    // 二维码本地生成（替代原先把二维码内容发给第三方服务器换图片的做法）
    implementation(libs.zxing.core)

    // Google Play 选配
    googlePlayDependencies(enabledPlayAppMode.toBoolean())

    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // xposed
    //    compileOnly(libs.xposed.api)


    // 预览工具
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)


}

// Google Play 依赖配置
fun DependencyHandlerScope.googlePlayDependencies(enabled: Boolean) {
    val googlePlayLibs = listOf(
        libs.play.app.update.kts,
        libs.play.app.review.kts
    )
    googlePlayLibs.forEach {
        if (enabled) {
            implementation(it)
        } else {
            compileOnly(it)
        }
    }
}


// Firebase 依赖配置已移除：本项目不再接入 Firebase / Google Analytics / Crashlytics
