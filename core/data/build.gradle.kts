plugins {
    alias(libs.plugins.bilibilias.android.library)
    alias(libs.plugins.bilibilias.android.koin)
    // 本模块有 @Serializable 类（BILILoginUserModel / BILISpaceArchiveModel / 分片边车元数据）。
    // 缺这个插件时**能编译、但运行时才炸**：
    // "Serializer for class '…' is not found … serialization compiler plugin is applied"。
    // 2026-09-12 由 SegmentDownloadMetaTest 抓到（那两个既有类恰好从未被真正序列化，所以一直没暴露）。
    alias(libs.plugins.kotlin.plugin.serialization)

}
android {
    namespace = "com.imcys.bilibilias.data"
}
dependencies {
    implementation(project(":core:common"))
    api(project(":core:database"))
    api(project(":core:datastore"))
    api(project(":core:network"))

    // 本地 JVM 单元测试：`sh gradlew :core:data:testDebugUnitTest` 即可跑。
    // 注意：**纯逻辑放在库模块而不是 :app** —— :app 的单测在本机跑不了
    // （编译依赖 AAR 资源需要 x86_64 的 aapt2，而设备是 arm64）。
    testImplementation(libs.junit)
    // 用内存假服务器（MockEngine）对分片下载做**字节级**验证：
    // 每个片是否落在正确偏移、Range 是否真被遵守、失败时会不会写出错位文件。
    // 这些是"编译通过但运行时写坏文件"的那类问题，只有真跑一遍才拦得住。
    testImplementation(libs.ktor.client.mock)
    // 用**虚拟时间**测"防抖/超时"这类真实时间行为：固定 delay + await 的写法会随机器负载
    // 随机变红（第二十六轮撞过两次），而虚拟时间下 advanceTimeBy 是确定性的、还快得多。
    testImplementation(libs.kotlinx.coroutines.test)
}