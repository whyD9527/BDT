package com.imcys.bilibilias.data.download.segmented

import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CyclicBarrier

/**
 * 分片下载器的**字节级**测试。
 *
 * 这里守的是最坏的一类 bug：**文件长度对、内容错位**。
 * 进度条照走、大小照对、合并也不报错 —— 只有用户播到一半才发现画面/声音坏了，
 * 而且会被记成"下载器有 bug"而不是"分片写歪了"。
 * 编译、diff、CI 构建全都不可能发现它，所以必须真跑一遍并逐字节比对。
 */
class SegmentedDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 用略大于阈值、且**不是并发数整数倍**的长度，保证尾片/非整除两条边界都被走到 */
    private val bigSize: Long = SegmentedDownloadPlan.MIN_SIZE_FOR_SEGMENTED + 12345L

    private fun downloaderOf(server: FakeRangeServer, maxPartAttempts: Int = 3) =
        SegmentedDownloader(
            httpClient = HttpClient(server.engine),
            maxPartAttempts = maxPartAttempts,
            retryDelayMs = 0,
        )

    private fun newTempFile(name: String = "video.m4s.downloading"): File =
        File(temp.newFolder(), name)

    private fun download(
        server: FakeRangeServer,
        file: File,
        totalLength: Long = server.payload.size.toLong(),
        concurrency: Int = SegmentedDownloadPlan.DEFAULT_CONCURRENCY,
        enabled: Boolean = true,
        acceptRanges: Boolean? = true,
        progress: MutableList<Float> = mutableListOf(),
    ): SegmentedDownloadResult = runBlocking {
        downloaderOf(server).download(
            url = "https://upos-sz-mirrorzos.bilivideo.com/upgcxcode/x.m4s",
            tempFile = file,
            referer = "https://www.bilibili.com/video/BV1xx411c7mD",
            totalLength = totalLength,
            acceptRanges = acceptRanges,
            concurrency = concurrency,
            enabled = enabled,
            onProgress = { progress += it },
        )
    }

    private fun writeMeta(file: File, plan: List<Segment>, downloaded: (Segment) -> Long) {
        val meta = SegmentDownloadMeta(
            totalLength = plan.sumOf { it.length },
            parts = plan.map {
                SegmentDownloadMeta.PartProgress(
                    index = it.index,
                    start = it.start,
                    endInclusive = it.endInclusive,
                    downloaded = downloaded(it),
                )
            },
        )
        val metaFile = SegmentedDownloader.metaFileFor(file)
        metaFile.parentFile?.mkdirs()
        metaFile.writeText(SegmentDownloadMetaCodec.encode(meta))
    }

    private fun assertCoversExactly(ranges: List<Pair<Long, Long>>, total: Long) {
        val sorted = ranges.sortedBy { it.first }
        assertEquals("必须从 0 开始", 0L, sorted.first().first)
        assertEquals("必须覆盖到最后一个字节", total - 1, sorted.last().second)
        sorted.zipWithNext { a, b ->
            assertEquals("片与片之间不能有空隙/重叠（$sorted）", a.second + 1, b.first)
        }
        assertEquals("总覆盖量必须等于文件长度", total,
            sorted.sumOf { it.second - it.first + 1 })
    }

    // ------------------------------------------------------------ 正常路径

    @Test
    fun `分片下载后文件与远端逐字节一致，且请求区间严丝合缝`() {
        val payload = FakeRangeServer.payload(bigSize)
        val server = FakeRangeServer(payload)
        val file = newTempFile()
        val progress = mutableListOf<Float>()

        val result = download(server, file, progress = progress)

        assertEquals(SegmentedDownloadResult.Success, result)
        assertArrayEquals("文件内容必须与远端逐字节一致", payload, file.readBytes())
        assertEquals("应恰好发起 4 个片请求", 4, server.rangeHeaders.size)
        assertCoversExactly(server.requestedRanges(), payload.size.toLong())
        assertEquals("完成后进度必须到 1", 1f, progress.last(), 0.0001f)
        progress.zipWithNext { a, b ->
            assertTrue("进度不能回退（$progress）", b >= a)
        }
        // 注意：最后那个 1f 是成功时**无条件补报**的，不能拿它证明聚合算对了。
        // 真正能证明"总进度 = 所有片之和 / 总长"的是中途取值：只按某一片算的话，
        // 4 片里任何一片最多只贡献 1/4，永远扫不过 0.5。
        val midFlight = progress.dropLast(1)
        assertTrue(
            "中途进度必须按所有片聚合（实际最大值=${midFlight.maxOrNull()}，$midFlight）",
            midFlight.maxOrNull() != null && midFlight.maxOrNull()!! >= 0.5f,
        )
        assertFalse("成功后边车必须清理掉", SegmentedDownloader.metaFileFor(file).exists())
    }

    @Test
    fun `确实并发发起各片，而不是排队串行`() {
        val payload = FakeRangeServer.payload(bigSize)
        // 4 个片请求必须**同时**到达才放行；串行实现会在这里超时失败
        val server = FakeRangeServer(payload, rendezvous = CyclicBarrier(4))
        val file = newTempFile()

        val result = download(server, file)

        assertEquals(SegmentedDownloadResult.Success, result)
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun `并发数可调，片数随之变化`() {
        val payload = FakeRangeServer.payload(bigSize)
        val server = FakeRangeServer(payload)
        val file = newTempFile()

        val result = download(server, file, concurrency = 2)

        assertEquals(SegmentedDownloadResult.Success, result)
        assertEquals(2, server.rangeHeaders.size)
        assertCoversExactly(server.requestedRanges(), payload.size.toLong())
        assertArrayEquals(payload, file.readBytes())
    }

    // ------------------------------------------------------------ 不该分片

    @Test
    fun `小于阈值或未开启时不分片，且不动任何文件`() {
        val server = FakeRangeServer(FakeRangeServer.payload(100_000))
        val file = newTempFile()

        val result = download(server, file, totalLength = 100_000L)

        assertTrue("实际=$result", result is SegmentedDownloadResult.NotApplicable)
        assertTrue("不该发任何请求", server.rangeHeaders.isEmpty())
        assertFalse("不该创建临时文件", file.exists())
        assertFalse("不该创建边车", SegmentedDownloader.metaFileFor(file).exists())
    }

    @Test
    fun `服务端声明 Accept-Ranges none 时不分片`() {
        val server = FakeRangeServer(FakeRangeServer.payload(bigSize), acceptRangesHeader = "none")
        val file = newTempFile()

        val result = download(server, file, acceptRanges = false)

        assertTrue("实际=$result", result is SegmentedDownloadResult.NotApplicable)
        assertTrue(server.rangeHeaders.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `长度未知时不分片`() {
        val server = FakeRangeServer(FakeRangeServer.payload(bigSize))
        val file = newTempFile()

        val result = download(server, file, totalLength = -1L, acceptRanges = null)

        assertTrue(result is SegmentedDownloadResult.NotApplicable)
        assertTrue(server.rangeHeaders.isEmpty())
    }

    @Test
    fun `开关关闭时不分片`() {
        val server = FakeRangeServer(FakeRangeServer.payload(bigSize))
        val file = newTempFile()

        val result = download(server, file, enabled = false)

        assertTrue(result is SegmentedDownloadResult.NotApplicable)
        assertTrue(server.rangeHeaders.isEmpty())
    }

    // ------------------------------------------------------------ 危险路径

    @Test
    fun `服务端忽略 Range 返回 200 时放弃分片，绝不按偏移写入`() {
        val payload = FakeRangeServer.payload(bigSize)
        val server = FakeRangeServer(payload, ignoreRange = true)
        val file = newTempFile()

        val result = download(server, file)

        assertTrue("必须判定为需要回落单连接，实际=$result",
            result is SegmentedDownloadResult.Failed)
        assertFalse("绝不能留下被写坏的文件", file.exists() && file.length() > 0)
    }

    @Test
    fun `Content-Range 起点与请求不符时放弃分片，绝不写入`() {
        val payload = FakeRangeServer.payload(bigSize)
        val server = FakeRangeServer(payload, contentRangeStartOverride = 4096L)
        val file = newTempFile()

        val result = download(server, file)

        assertTrue("起点对不上就不能写，实际=$result",
            result is SegmentedDownloadResult.Failed)
        assertFalse("绝不能留下被写坏的文件", file.exists() && file.length() > 0)
    }

    @Test
    fun `服务端多发字节时严格截断在片尾，绝不污染下一片`() {
        val payload = FakeRangeServer.payload(bigSize)
        // 每个 206 都多送 4096 个 0xFF
        val server = FakeRangeServer(payload, overshootRangeEndBy = 4096)
        val file = newTempFile()
        val plan = SegmentedDownloadPlan.plan(payload.size.toLong(), 2)

        // 片 1 已经完整落盘且被边车认可 → 本次只下片 0。
        // 这样"片 0 多发的那 4096 字节"会直接落在片 1 的地盘上，且没有任何后续写入能掩盖它。
        file.parentFile?.mkdirs()
        file.writeBytes(payload)
        writeMeta(file, plan) { if (it.index == 1) it.length else 0L }

        val result = download(server, file, concurrency = 2)

        assertEquals(SegmentedDownloadResult.Success, result)
        assertEquals("只该重下片 0", 1, server.rangeHeaders.size)
        assertArrayEquals(
            "多发字节属于下一片，必须被丢弃（否则片 1 已被写坏，而文件长度完全正常）",
            payload,
            file.readBytes(),
        )
    }

    @Test
    fun `body 被截断时不算成功，也不留下看起来完整的文件`() {
        val payload = FakeRangeServer.payload(bigSize)
        val server = FakeRangeServer(payload, truncateEachRangeBy = 1000)
        val file = newTempFile()

        val result = download(server, file)

        assertTrue("截断必须判为失败，实际=$result", result is SegmentedDownloadResult.Failed)
        // 关键：单连接会按"文件长度"判断已下载量，所以这里长度必须**明显小于**总长，
        // 否则回落之后会以为已经下完了
        assertTrue(
            "文件长度(${file.length()}) 不能达到总长(${payload.size})",
            file.length() < payload.size,
        )
        assertTrue("失败要留下边车供续传", SegmentedDownloader.metaFileFor(file).exists())
    }

    // ------------------------------------------------------------ 续传

    @Test
    fun `续传只重下未完成的片，最终内容仍逐字节一致`() {
        val payload = FakeRangeServer.payload(bigSize)
        val server = FakeRangeServer(payload)
        val file = newTempFile()
        val plan = SegmentedDownloadPlan.plan(payload.size.toLong(), 4)

        // 造现场：片 0 已完整落盘，其余未下
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.write(payload, 0, plan[0].length.toInt()) }
        writeMeta(file, plan) { if (it.index == 0) it.length else 0L }

        val result = download(server, file)

        assertEquals(SegmentedDownloadResult.Success, result)
        assertArrayEquals("续传拼接后必须仍然逐字节一致", payload, file.readBytes())
        assertEquals("片 0 已下完，不该再请求", 3, server.rangeHeaders.size)
        assertTrue("不该重下片 0", server.requestedRanges().all { it.first > 0 })
    }

    @Test
    fun `所有片都已完成时不发任何请求`() {
        val payload = FakeRangeServer.payload(bigSize)
        val server = FakeRangeServer(payload)
        val file = newTempFile()
        val plan = SegmentedDownloadPlan.plan(payload.size.toLong(), 4)

        file.parentFile?.mkdirs()
        file.writeBytes(payload)
        writeMeta(file, plan) { it.length }

        val result = download(server, file)

        assertEquals(SegmentedDownloadResult.Success, result)
        assertTrue("已下完不该再发请求", server.rangeHeaders.isEmpty())
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun `边车是坏数据时当作从未下过，全量重下且内容正确`() {
        val payload = FakeRangeServer.payload(bigSize)
        val server = FakeRangeServer(payload)
        val file = newTempFile()

        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(1024))   // 里面是垃圾
        SegmentedDownloader.metaFileFor(file).writeText("{ 这不是 JSON")

        val result = download(server, file)

        assertEquals(SegmentedDownloadResult.Success, result)
        assertArrayEquals("垃圾文件必须被完整覆盖", payload, file.readBytes())
        assertEquals(4, server.rangeHeaders.size)
    }

    @Test
    fun `边车进度超出文件实际长度时，只信文件里真实存在的那一段`() {
        val payload = FakeRangeServer.payload(bigSize)
        val server = FakeRangeServer(payload)
        val file = newTempFile()
        val plan = SegmentedDownloadPlan.plan(payload.size.toLong(), 4)

        // 边车声称全部下完，但文件里只有 100 字节 → 不能相信边车
        file.parentFile?.mkdirs()
        file.writeBytes(payload.copyOf(100))
        writeMeta(file, plan) { it.length }

        val result = download(server, file)

        assertEquals(SegmentedDownloadResult.Success, result)
        assertArrayEquals("必须重下并覆盖，内容逐字节一致", payload, file.readBytes())
    }

    @Test
    fun `边车与本次计划不符时不可信，全部重下`() {
        val payload = FakeRangeServer.payload(bigSize)
        val server = FakeRangeServer(payload)
        val file = newTempFile()
        // 用 2 片的计划写边车，本次按 4 片下 → 计划不符
        val foreignPlan = SegmentedDownloadPlan.plan(payload.size.toLong(), 2)

        file.parentFile?.mkdirs()
        file.writeBytes(payload.copyOf(1000))
        writeMeta(file, foreignPlan) { it.length }

        val result = download(server, file)

        assertEquals(SegmentedDownloadResult.Success, result)
        assertArrayEquals(payload, file.readBytes())
        assertEquals(4, server.rangeHeaders.size)
    }
}
