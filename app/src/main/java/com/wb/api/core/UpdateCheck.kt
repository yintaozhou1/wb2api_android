package com.wb.api.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 远程版本检测（「最新版x.x.x」文本驱动）。
 *
 * 为什么用「远程文本 + 正则」而不是固定 JSON 接口：
 *  - 版本号需要能**随时改**（不用发版就能公告新版本），远程页面是用户自己维护的分享链接；
 *  - 文本形态会变（后面可能跟群号、链接、说明），所以只锚定 `最新版` + 语义化版本号这一段，
 *    其余内容一概忽略；页面若被富文本编辑插了标签，先剥标签再匹配。
 *
 * 失败语义分三类，调用方据此给不同提示：
 *  - [Result.Latest]   —— 解析到版本号（是否「更新」由 [compareVersions] 判断）；
 *  - [Result.NoMarker] —— 页面拿到了、但**没有**「最新版x.x.x」（配置问题，不是网络问题）；
 *  - [Result.Failed]   —— 网络异常 / 超时 / 非 200（用户可见的「网络异常」提示）。
 *
 * 线程：只在 [fetch] 内部切 IO；解析函数是纯函数，可单测。
 */
object UpdateCheck {

    /**
     * 独立的长期 client：每次检查都新建 OkHttpClient 会连带新建连接池与 Dispatcher
     * 线程池，闲置约 60s 才回收（冷启动 + 手动检查各一次 = 两套线程）。
     * 单例复用即可 —— 超时参数与网关主 client 完全不同，不会互相影响。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 版本公告页（微云分享）。文本形如「最新版1.6.9https://…群号…」，用户会远程改成新版本。 */
    const val SOURCE_URL = "https://sharechain.qq.com/42e2768703ac7fd23d461423fa493198"

    /**
     * 「最新版」+ 版本号。容忍：中英文冒号/空格、`v` 前缀、1~4 段数字（1.7 / 1.7.0 / 1.7.0.1）。
     * 不用 `\b` 收尾：远程文本里版本号后面常**直接**跟 URL 或中文（"最新版1.6.9https://…"），
     * 而 `9h`/`9企` 都不构成 `\b`，会把本该匹配的情况漏掉。
     */
    private val MARK = Regex("""最新版\s*[vV]?\s*(\d+(?:\.\d+){1,3})""")

    /** 粗剥 HTML 标签（只为「标签把版本号劈成两半」这种远程编辑意外兜底）。 */
    private val TAG = Regex("""<[^>]{1,200}>""")

    /** 网络失败/超时的统一可读文案（toast 用）。 */
    sealed class Result {
        /** 解析到的最新版本（原样字符串，如 "1.7.0"）。 */
        data class Latest(val version: String) : Result()

        /** 页面可访问，但文本里没有「最新版x.x.x」。 */
        object NoMarker : Result()

        /** 拿不到页面：网络异常 / 超时 / HTTP 非 200。 */
        data class Failed(val reason: String) : Result()
    }

    /** 从任意文本里抽版本号（先原文匹配，再剥标签匹配一次）。 */
    fun extractVersion(raw: String): String? =
        MARK.find(raw)?.groupValues?.get(1)
            ?: MARK.find(TAG.replace(raw, " "))?.groupValues?.get(1)

    /**
     * 语义化版本比较：返回 >0 表示 a 比 b 新，<0 反之，0 相等。
     *
     * **不能直接比字符串**：`"1.10.0" < "1.9.9"` 在字典序下成立，会把新版本判成旧版本。
     * 段数不同按 0 补齐（`1.7` == `1.7.0`）。
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.trim().split('.')
        val pb = b.trim().split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /**
     * 拉取并解析。
     *
     * 独立 OkHttp 实例（不复用网关主 client）：① 主 client 的 `callTimeout` 是 0/120s，用于
     * 上游对话，用在「启动时检查版本」上会长时间占住一个线程；② 这里的失败不应对网关的
     * 连接池产生任何影响。
     */
    suspend fun fetch(): Result = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(SOURCE_URL)
            // 用浏览器 UA：分享页是普通 Web 页面，个别 CDN 对非浏览器 UA 直接拒（403/挑战页）。
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/141.0.0.0 Mobile Safari/537.36",
            )
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.Failed("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@withContext Result.Failed("响应为空")
                val v = extractVersion(body) ?: return@withContext Result.NoMarker
                Result.Latest(v)
            }
        } catch (e: java.net.SocketTimeoutException) {
            Result.Failed("网络超时（${e.message ?: "timeout"}）")
        } catch (e: java.net.UnknownHostException) {
            Result.Failed("网络异常（无法解析域名）")
        } catch (e: java.io.IOException) {
            Result.Failed("网络异常（${e.message ?: e.javaClass.simpleName}）")
        } catch (e: Throwable) {
            Result.Failed("检查失败（${e.message ?: e.javaClass.simpleName}）")
        }
    }
}
