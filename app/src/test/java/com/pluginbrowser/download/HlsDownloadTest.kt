package com.pluginbrowser.download

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 起一个本地 HTTP 服务，跑完整的「拉播放列表 → 拉分片 → 拼文件」流程。
 *
 * 只测解析是不够的：拼接顺序、AES 解密、master 选路这几步错了，
 * 解析测试全绿、真机上却是一个放不了的文件。这里连字节内容一起验。
 */
class HlsDownloadTest {

    private lateinit var server: TinyHttp
    private lateinit var base: String
    private lateinit var workDir: File

    private val segA = ByteArray(1024) { (it * 7).toByte() }
    private val segB = ByteArray(2048) { (it * 3 + 1).toByte() }
    private val segC = ByteArray(512) { (it + 9).toByte() }

    private val key = ByteArray(16) { (0x40 + it).toByte() }
    private val ivHex = "000102030405060708090A0B0C0D0E0F"

    @Before
    fun setUp() {
        workDir = File(System.getProperty("java.io.tmpdir"), "hls-test-${System.nanoTime()}")
        workDir.mkdirs()
        server = TinyHttp()
        base = "http://127.0.0.1:${server.port}"

        text("/master.m3u8", """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=400000
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
            high/index.m3u8
        """.trimIndent())
        text("/low/index.m3u8", """
            #EXTM3U
            #EXTINF:2.0,
            only.log
        """.trimIndent())
        text("/high/index.m3u8", """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4.0,
            a.ts
            #EXTINF:4.0,
            b.ts
            #EXTINF:4.0,
            c.ts
            #EXT-X-ENDLIST
        """.trimIndent())
        bytes("/high/a.ts", segA)
        bytes("/high/b.ts", segB)
        bytes("/high/c.ts", segC)
        bytes("/low/only.log", "LOW".toByteArray())

        text("/enc/index.m3u8", """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="k.bin",IV=0x$ivHex
            #EXTINF:4.0,
            e1.ts
            #EXTINF:4.0,
            e2.ts
            #EXT-X-ENDLIST
        """.trimIndent())
        bytes("/enc/k.bin", key)
        bytes("/enc/e1.ts", encrypt(segA))
        bytes("/enc/e2.ts", encrypt(segC))

        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        workDir.deleteRecursively()
    }

    @Test
    fun `master 列表会挑码率最高的一路并拼成完整文件`() = runBlocking {
        val out = File(workDir, "out.ts")
        var lastTotal = 0L
        val progressCalls = AtomicLong(0)
        val result = Hls.download(
            client = OkHttpClient(),
            playlistUrl = "$base/master.m3u8",
            userAgent = "test",
            out = out,
            onCall = {},
            onProgress = { _, total, _ ->
                progressCalls.incrementAndGet()
                if (total > 0) lastTotal = total
            },
        )

        val expected = segA + segB + segC
        assertArrayEquals("分片必须按顺序首尾相接", expected, out.readBytes())
        assertEquals(expected.size.toLong(), result.bytes)
        assertEquals("不能选到 low 那一路", 3, progressCalls.get())
        assertTrue("下完第一片后应给出总量估算", lastTotal > 0)
        assertTrue("不是 fMP4，扩展名该保持 ts", !result.fmp4)
    }

    @Test
    fun `加密流按 IV 解密后内容与明文一致`() = runBlocking {
        val out = File(workDir, "enc.ts")
        Hls.download(
            client = OkHttpClient(),
            playlistUrl = "$base/enc/index.m3u8",
            userAgent = "test",
            out = out,
            onCall = {},
            onProgress = { _, _, _ -> },
        )
        assertArrayEquals(segA + segC, out.readBytes())
    }

    @Test
    fun `播放列表 404 时直接失败而不是写出空文件`() {
        val out = File(workDir, "fail.ts")
        val ok = runCatching {
            runBlocking {
                Hls.download(OkHttpClient(), "$base/missing.m3u8", "test", out, {}, { _, _, _ -> })
            }
        }.isSuccess
        assertTrue("404 应该抛异常", !ok)
    }

    /* ---------------- 本地服务 ---------------- */

    private fun text(path: String, body: String) = bytes(path, body.toByteArray())

    private fun bytes(path: String, body: ByteArray) {
        server.route(path, body)
    }

    /**
     * 极小的 HTTP 服务。
     *
     * 不用 com.sun.net.httpserver：Android 单元测试编译在 android.jar 上，
     * 那个包压根不在类路径里。这里只需要「按路径回字节」，ServerSocket 足够。
     */
    private class TinyHttp {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val routes = HashMap<String, ByteArray>()
        private var thread: Thread? = null

        val port: Int get() = socket.localPort

        fun route(path: String, body: ByteArray) {
            routes[path] = body
        }

        fun start() {
            thread = Thread {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: return@Thread
                    runCatching { handle(client) }
                }
            }.apply {
                isDaemon = true
                name = "tiny-http"
                start()
            }
        }

        fun stop() {
            runCatching { socket.close() }
            thread?.interrupt()
        }

        private fun handle(client: java.net.Socket) {
            client.use { c ->
                val reader = c.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val body = routes[path]
                val out = c.getOutputStream()
                if (body == null) {
                    out.write(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(),
                    )
                } else {
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream" +
                        "\r\nContent-Length: \${body.size}\r\nConnection: close\r\n\r\n"
                    out.write(header.toByteArray())
                    out.write(body)
                }
                out.flush()
            }
        }
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val iv = ByteArray(16) { i -> ivHex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }
}
