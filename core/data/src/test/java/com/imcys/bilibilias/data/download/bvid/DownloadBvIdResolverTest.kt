package com.imcys.bilibilias.data.download.bvid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bvid 解析规则的测试。
 *
 * 守的是第十八轮审查里的低危项：原实现的兜底挂在"解码必然抛异常"上，
 * 而 `platformInfo` 解不出 `bvid` 时**根本不抛异常**（Page 模型没有这个字段，
 * Episode 的 bvid 可空）→ catch 里的"按 nodeId 反查"从未执行过。
 */
class DownloadBvIdResolverTest {

    private val coverJson = """{"bvid":"BV1xx411c7mD","cid":123,"title":"某视频"}"""
    private val pageJsonWithoutBvId = """{"cid":123,"page":1,"part":"P1","duration":60}"""
    private val episodeJsonWithNullBvId = """{"bvid":null,"cid":123,"title":"第1话"}"""

    @Test
    fun `子任务的 platformId 优先`() {
        assertEquals(
            "BV1xx411c7mD",
            DownloadBvIdResolver.resolve(
                taskPlatformId = "BV1xx411c7mD",
                platformInfoJson = """{"bvid":"BV1yy411c7mE"}""",
                nodePlatformId = "BV1zz411c7mF",
            ),
        )
    }

    @Test
    fun `子任务的 platformId 不是 BV 号时跳过它（合集章节的节点存的是章节 ID）`() {
        assertEquals(
            "BV1yy411c7mE",
            DownloadBvIdResolver.resolve(
                taskPlatformId = "987654",
                platformInfoJson = """{"bvid":"BV1yy411c7mE"}""",
                nodePlatformId = "123",
            ),
        )
    }

    @Test
    fun `平台 JSON 里没有 bvid 字段时要落到节点上（原来这条兜底从未执行）`() {
        assertEquals(
            "BV1xx411c7mD",
            DownloadBvIdResolver.resolve(
                taskPlatformId = null,
                platformInfoJson = pageJsonWithoutBvId,
                nodePlatformId = "BV1xx411c7mD",
            ),
        )
    }

    @Test
    fun `平台 JSON 有 bvid 字段且为空时是明确没有，不再往下兜底`() {
        // 关键是"不落到节点"：节点明明有个合法 BV 号，但平台数据显式说了 bvid=null，
        // 那就按"这条没有 bvid"处理，别拿节点上的 BV 号去猜（原来这两种语义混在一个 catch 里）。
        assertNull(
            DownloadBvIdResolver.resolve(
                taskPlatformId = null,
                platformInfoJson = episodeJsonWithNullBvId,
                nodePlatformId = "BV1xx411c7mD",
            ),
        )
    }

    @Test
    fun `平台 JSON 非法时按没有字段处理（不再靠异常改分支）`() {
        assertEquals(
            DownloadBvIdResolver.PlatformInfoBvId.Absent,
            DownloadBvIdResolver.bvidFromPlatformInfo("not json at all"),
        )
        assertEquals(
            DownloadBvIdResolver.PlatformInfoBvId.Absent,
            DownloadBvIdResolver.bvidFromPlatformInfo("[1,2,3]"),
        )
        assertEquals(
            DownloadBvIdResolver.PlatformInfoBvId.Absent,
            DownloadBvIdResolver.bvidFromPlatformInfo(null),
        )
        assertEquals(
            DownloadBvIdResolver.PlatformInfoBvId.Absent,
            DownloadBvIdResolver.bvidFromPlatformInfo(""),
        )
    }

    @Test
    fun `平台 JSON 的三种状态要分得清`() {
        assertEquals(
            DownloadBvIdResolver.PlatformInfoBvId.Value("BV1xx411c7mD"),
            DownloadBvIdResolver.bvidFromPlatformInfo(coverJson),
        )
        assertEquals(
            DownloadBvIdResolver.PlatformInfoBvId.Present,
            DownloadBvIdResolver.bvidFromPlatformInfo("""{"bvid":""}"""),
        )
        assertEquals(
            DownloadBvIdResolver.PlatformInfoBvId.Absent,
            DownloadBvIdResolver.bvidFromPlatformInfo(pageJsonWithoutBvId),
        )
    }

    @Test
    fun `什么都取不到时返回 null`() {
        assertNull(
            DownloadBvIdResolver.resolve(
                taskPlatformId = null,
                platformInfoJson = pageJsonWithoutBvId,
                nodePlatformId = "556677",
            ),
        )
        assertNull(
            DownloadBvIdResolver.resolve(
                taskPlatformId = null,
                platformInfoJson = null,
                nodePlatformId = null,
            ),
        )
    }

    @Test
    fun `只认 BV 号形状的 platformId`() {
        assertTrue(DownloadBvIdResolver.isValidBvId("BV1xx411c7mD"))
        assertTrue(DownloadBvIdResolver.isValidBvId("BV1AVw1eE7Xs"))
        assertFalse(DownloadBvIdResolver.isValidBvId("bv1xx411c7mD"))   // 小写前缀
        assertFalse(DownloadBvIdResolver.isValidBvId("BV1xx411c7m"))    // 少一位
        assertFalse(DownloadBvIdResolver.isValidBvId("BV1xx411c7mDD"))  // 多一位
        assertFalse(DownloadBvIdResolver.isValidBvId("123456789"))      // 纯数字 CID
        assertFalse(DownloadBvIdResolver.isValidBvId("av170001"))
        assertFalse(DownloadBvIdResolver.isValidBvId(null))
        assertFalse(DownloadBvIdResolver.isValidBvId(""))
    }
}
