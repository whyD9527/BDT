package com.imcys.bilibilias.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateRulesTest {

    @Test
    fun `解析 tag：容忍 v 前缀与后缀`() {
        assertEquals(GitHubUpdateRules.SemVer(3, 3, 4), GitHubUpdateRules.parseVersion("v3.3.4"))
        assertEquals(GitHubUpdateRules.SemVer(3, 3, 4), GitHubUpdateRules.parseVersion("3.3.4"))
        assertEquals(GitHubUpdateRules.SemVer(3, 3, 4), GitHubUpdateRules.parseVersion(" v3.3.4-alpha "))
        assertEquals(GitHubUpdateRules.SemVer(3, 3, 0), GitHubUpdateRules.parseVersion("v3.3"))
        // 解析不出来时宁可不提示
        assertNull(GitHubUpdateRules.parseVersion("abc"))
        assertNull(GitHubUpdateRules.parseVersion("3"))
        assertNull(GitHubUpdateRules.parseVersion(null))
    }

    @Test
    fun `新版本判断：远端更旧或相等都不算新`() {
        val local = GitHubUpdateRules.parseVersion("3.3.3")
        assertTrue(GitHubUpdateRules.isNewer(GitHubUpdateRules.parseVersion("v3.3.4"), local))
        assertTrue(GitHubUpdateRules.isNewer(GitHubUpdateRules.parseVersion("v3.4.0"), local))
        assertFalse(GitHubUpdateRules.isNewer(GitHubUpdateRules.parseVersion("v3.3.3"), local))
        assertFalse(GitHubUpdateRules.isNewer(GitHubUpdateRules.parseVersion("v3.3.2"), local))
    }

    @Test
    fun `按 ABI 选 APK：优先本机 ABI，退 universal`() {
        val assets = listOf(
            "app-alpha-arm64-v8a-release.apk",
            "app-alpha-armeabi-v7a-release.apk",
            "app-alpha-x86_64-release.apk",
            "app-alpha-universal-release.apk",
            "SHA256SUMS",
        )
        assertEquals("app-alpha-arm64-v8a-release.apk", GitHubUpdateRules.selectApkAsset(assets, "arm64-v8a"))
        assertEquals("app-alpha-armeabi-v7a-release.apk", GitHubUpdateRules.selectApkAsset(assets, "armeabi-v7a"))
        assertEquals("app-alpha-x86_64-release.apk", GitHubUpdateRules.selectApkAsset(assets, "x86_64"))
        // 认不出的 ABI → 退 universal
        assertEquals("app-alpha-universal-release.apk", GitHubUpdateRules.selectApkAsset(assets, "mips"))
        // 没有 apk → null（只提示、不给下载）
        assertNull(GitHubUpdateRules.selectApkAsset(listOf("SHA256SUMS", "src.zip"), "arm64-v8a"))
        // 只有 universal 时也能选中
        assertEquals("app-universal.apk", GitHubUpdateRules.selectApkAsset(listOf("app-universal.apk"), "arm64-v8a"))
    }

    @Test
    fun `隐私门槛：未同意隐私政策不发请求`() {
        assertTrue(GitHubUpdateRules.shouldCheck(privacyAgreed = true))
        assertFalse(GitHubUpdateRules.shouldCheck(privacyAgreed = false))
    }

    @Test
    fun `跳过版本：跳过的同版本与更旧版本都不再提示`() {
        val r334 = GitHubUpdateRules.parseVersion("v3.3.4")
        val skipped = GitHubUpdateRules.parseVersion("v3.3.4")!!.encode()
        assertTrue("跳过 3.3.4 后，3.3.4 不再提示", GitHubUpdateRules.shouldSkip(r334, skipped))
        assertTrue("跳过后，更旧的也不提示", GitHubUpdateRules.shouldSkip(GitHubUpdateRules.parseVersion("v3.3.3"), skipped))
        assertFalse("跳过后，更新的仍然提示", GitHubUpdateRules.shouldSkip(GitHubUpdateRules.parseVersion("v3.4.0"), skipped))
        assertTrue("解析不出来的远端视为跳过（宁可不提示）", GitHubUpdateRules.shouldSkip(null, 0))
    }
}
