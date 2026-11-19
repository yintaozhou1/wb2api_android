package com.wb.api.upstream

import com.wb.api.Gateway
import com.wb.api.core.LogBus
import com.wb.api.model.AuthEntry
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * 凭证续期 / 求证的**唯一入口**（单飞 + 落盘）。
 *
 * 为什么必须收口（1.7.4 修）：
 *  - 上游会**轮换 refresh_token**。同一 uid 只要有两处并发拿同一份旧 RT 去刷，先到的一次
 *    会让旧 RT 失效，后到的那次收到 4xx → 被判 DEAD → 把一个完全健康的号永久禁用。
 *  - 1.7.3 之前只有「请求路径」做了按 uid 单飞（`ChatHandler.sessionLocks`），而 1.7.4 新增的
 *    「保活巡检」「详情页立即续期」「网络自检」「面板批量保活」都是**新的续期入口**——
 *    各写一把锁等于没有锁。所以这里下沉成一份：所有续期/求证都走 [withUidLock]。
 *  - 落盘也收口：只改内存不落盘，重启会拿旧 RT 去刷新，反而变成真死。
 *
 * 注意：本对象只做「凭证层」的事，不碰池状态（禁用/冷却由调用方按结论决定）。
 */
object CredentialService {

    private val locks = ConcurrentHashMap<String, Any>()

    /** 同一 uid 的续期/求证串行化（JVM 监视器可重入，可在内部再次进入）。 */
    fun <T> withUidLock(uid: String, block: () -> T): T =
        synchronized(locks.computeIfAbsent(uid) { Any() }, block)

    /** 读最新凭证（别人可能刚续期过）；读不到时退回传入值。 */
    fun latestOf(auth: AuthEntry): AuthEntry =
        runCatching { Gateway.store.getAuth(auth.uid) }.getOrNull() ?: auth

    /**
     * 主动续期（单飞）。
     *
     * @param minRemainingSec 剩余有效期大于该值就认为「不需要续」，直接返回最新凭证。
     * @return 最新凭证（可能是别人刚刷好的）；**真的刷不动**时返回 null。
     *
     * 失败语义：这里不做 DEAD 判定（传输抖动不能定罪），由调用方按需要再走 [prove]。
     */
    fun renew(auth: AuthEntry, minRemainingSec: Long = 120, force: Boolean = false): AuthEntry? =
        withUidLock(auth.uid) {
        val latest = latestOf(auth)
        if (latest.refresh_token.isBlank()) return@withUidLock null
        val now = System.currentTimeMillis() / 1000
        // force=true 表示「用户明确要求现在就续」（详情页按钮 / 面板保活）：跳过「还够久」的短路。
        // RT 的 refresh_expires_at 是**本地估算**（30 天），这里不据它拦 —— 估算过期时更该
        // 真试一次：成功会把估算值一并刷新，失败会被上游明确拒绝并由调用方判死。
        if (!force && latest.access_expires_at > 0 && latest.access_expires_at - now > minRemainingSec) {
            return@withUidLock latest
        }
        val renewed = runCatching { runBlocking { Gateway.upstream.refresh(latest) } }.getOrNull()
            ?: return@withUidLock null
        persist(renewed)
        LogBus.log(
            "| token 已续期 uid=${renewed.uid.take(8)}（原剩余 " +
                "${latest.access_expires_at - now}s → 新剩余 ${renewed.access_expires_at - now}s）",
        )
        renewed
    }

    /**
     * 401 求证（单飞；总是用**最新**凭证）。
     *
     * 若最新凭证的 access_token 已经变了，说明别的线程刚续期过 —— 本次 401 是旧 token
     * 造成的，直接判 ALIVE（不再重复打 refresh）。
     */
    fun prove(auth: AuthEntry): SessionProof = withUidLock(auth.uid) {
        val latest = latestOf(auth)
        if (latest.access_token.isNotBlank() && latest.access_token != auth.access_token) {
            return@withUidLock SessionProof(SessionVerdict.ALIVE, null)
        }
        val proof = runCatching { runBlocking { Gateway.upstream.verifySession(latest) } }
            .getOrDefault(SessionProof(SessionVerdict.UNKNOWN))
        // 求证成功时上游已经给出了新凭证：在这里统一落盘，调用方不必各写一遍。
        proof.renewed?.let { persist(it) }
        proof
    }

    /** 落盘（失败只记日志：内存里的新凭证仍可用，下次成功时会再落一次）。 */
    fun persist(e: AuthEntry) {
        runCatching { Gateway.store.saveAuth(e) }
            .onFailure { LogBus.log("[cred] 保存续期凭证失败 uid=${e.uid.take(8)}：${it.message}") }
    }
}
