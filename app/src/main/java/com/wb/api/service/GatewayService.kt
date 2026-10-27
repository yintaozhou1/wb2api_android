package com.wb.api.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wb.api.Gateway
import com.wb.api.MainActivity
import com.wb.api.R
import com.wb.api.core.LogBus
import com.wb.api.overlay.AddressOverlay
import com.wb.api.scheduler.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** 前台服务：常驻运行内嵌 HTTP 网关 + 定时任务，保证后台不被杀。 */
class GatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Scheduler.stop()
            Gateway.stopServer()
            // 先摘浮窗：停止过程中地址已经不可用了，留着只会显示一个连不上的端口。
            AddressOverlay.hide()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        Gateway.init(this)
        // 显式声明前台服务类型（Android 14+ 起「类型」是硬要求：只声明在清单里也能跑，
        // 但显式传入可避免系统在类型缺失/不匹配时抛 MissingForegroundServiceTypeException，
        // 也比依赖「清单推定」更清楚）。ServiceCompat 内部做了版本分支处理。
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        // 启动结果**必须显式判定**：端口被占用等失败若被吞掉，会出现「通知显示运行中、
        // 但 UI 显示未启动」的分裂状态（用户只能反复点按钮）。失败即结束服务并上报原因。
        Gateway.startServer()
        if (!Gateway.isRunning()) {
            val why = Gateway.lastError.value ?: "未知错误"
            LogBus.log("[sys] 网关启动失败：$why，已结束服务")
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        Scheduler.start(scope)
        LogBus.log("service started on port ${Gateway.port}")
        // 浮窗在**端口真正绑定成功之后**才显示：显示出来的地址一定是可用的
        // （启动失败的分支已经 return，不会走到这里）。
        syncOverlay()
        return START_STICKY
    }

    /**
     * Android 15+（API 35）：系统对 `dataSync` 类型的常驻服务设了「24 小时内累计 6 小时」的硬上限，
     * 到点会回调本方法。官方文档要求在这里**数秒内** stopSelf()，否则系统抛
     * `RemoteServiceException: A foreground service of type dataSync did not stop within its timeout`
     * （developer.android.com/develop/background-work/services/fg-service-timeout）。
     *
     * 本应用的定位是「常驻网关」，与这条系统限制天然冲突 —— 唯一的正解是**优雅停机**：
     * 记日志 + 发一条可点击的提醒，而不是让进程被系统杀掉（那连日志都留不下）。
     * 用户把应用切回前台会重置计时器，所以这不是「功能坏了」，而是「后台常驻 6 小时后需回前台续期」。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        onFgsTimeout()
    }

    /** Android 14（API 34）的 shortService 超时回调：同一条超时纪律，签名少一个参数。 */
    override fun onTimeout(startId: Int) {
        onFgsTimeout()
    }

    private fun onFgsTimeout() {
        LogBus.log(
            "[sys] 系统限制：前台服务已用满 6 小时配额（Android 15+ 对 dataSync 的限制），网关已自动停止；" +
                "把应用切到前台可重置计时",
        )
        runCatching { Scheduler.stop() }
        runCatching { Gateway.stopServer() }
        runCatching { AddressOverlay.hide() }
        sealArchive()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        notifyTimedOut()
        stopSelf()
    }

    /** 超时停机提醒：独立 id（不覆盖运行中那条通知）；未授予通知权限时静默失败。 */
    private fun notifyTimedOut() {
        runCatching {
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("网关已被系统限时停止")
                .setContentText("Android 15+ 对后台数据同步服务有 6 小时配额；打开应用即可重置并继续运行。")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIF_ID + 1, n)
        }
    }

    override fun onDestroy() {
        Scheduler.stop()
        Gateway.stopServer()
        runCatching { AddressOverlay.hide() }
        sealArchive()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        scope.cancel()
        LogBus.log("service stopped")
        super.onDestroy()
    }

    /** 按配置与权限同步地址浮窗（关闭开关 → 移除）。 */
    private fun syncOverlay() {
        runCatching {
            if (!Gateway.isInitialized() || !Gateway.store.config.ui.overlay) {
                AddressOverlay.hide()
                return
            }
            AddressOverlay.show(this, "127.0.0.1:${Gateway.displayPort()}")
        }.onFailure { e -> LogBus.log("[overlay] 同步浮窗失败：${e.message}") }
    }

    /**
     * 收口归档句柄：先把缓冲区落盘再关闭。
     *
     * 归档写入用了常驻 BufferedWriter（省 syscall），代价是缓冲区里可能还有几行没落盘——
     * 服务停止/进程退出前必须显式 flush + close，否则最后几条请求记录会「凭空消失」，
     * 而这恰恰是排查故障时最想看的那几条。
     */
    private fun sealArchive() {
        if (!Gateway.isInitialized()) return
        runCatching {
            Gateway.store.flushRequestLog()
            Gateway.store.closeRequestLog()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "网关运行", NotificationManager.IMPORTANCE_LOW)
            ch.description = "wb2api 网关后台运行状态"
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val count = Gateway.pool.counts()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("wb2api 网关运行中")
            .setContentText("端口 ${Gateway.displayPort()} · 账号 ${count.healthy}/${count.total} 可用 · 点击查看")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "wb2api_gateway"
        const val NOTIF_ID = 7863
        const val ACTION_STOP = "com.wb.api.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, GatewayService::class.java)
            // 两种系统拒绝都不该让「点击启动」崩掉（Android 12+ 的后台启动限制、
            // Android 15+ 的 dataSync 配额耗尽都会抛 ForegroundServiceStartNotAllowedException）：
            // 记一条可读日志即可 —— 上层会轮询 Gateway.isRunning() 并如实报「启动失败」。
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
            }.onFailure { e ->
                LogBus.log("[sys] 启动前台服务被系统拒绝：${e.javaClass.simpleName} ${e.message ?: ""}")
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GatewayService::class.java))
        }
    }
}
