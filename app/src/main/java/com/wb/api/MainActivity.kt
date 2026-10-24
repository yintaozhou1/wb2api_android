package com.wb.api

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.wb.api.overlay.AddressOverlay
import com.wb.api.ui.AppRoot
import com.wb.api.ui.AppViewModel
import com.wb.api.ui.LocalWbAnimations
import com.wb.api.ui.LocalWbHaptics
import com.wb.api.ui.WbSystemBars
import com.wb.api.ui.WbTheme
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 在 Activity 作用域内取 ViewModel：既给 setContent 用，也给下面的 TTFD 上报用。
        val vm: AppViewModel by viewModels()
        setContent {
            // 主题/取色/触感/动画都从配置读，改设置后立即生效（Reactive，无需重启）。
            val cfg by vm.config.collectAsStateWithLifecycle()
            CompositionLocalProvider(
                LocalWbHaptics provides cfg.ui.haptics,
                LocalWbAnimations provides cfg.ui.animations,
            ) {
                WbTheme(cfg.ui.theme, cfg.ui.monet) {
                    // 状态栏/导航栏图标明暗跟随主题（浅色主题→深色图标，深色主题→浅色图标）。
                    WbSystemBars()
                    AppRoot(vm)
                }
            }
        }

        // TTFD（Time To Full Display）上报：Jetpack 的启动指标里 TTID 由系统自动测量，
        // 而 TTFD **只有应用自己调用 reportFullyDrawn() 才会有**；且在首帧之前调用会被
        // 记成与 TTID 同值（没有意义）。这里等「首次后台刷新把真实数据推上来」之后再报，
        // 并加 3s 兜底 —— 万一数据恰好没有变化（StateFlow 不会推送），也不能一直悬着。
        lifecycleScope.launch {
            withTimeoutOrNull(3_000) { vm.overview.drop(1).first() }
            reportFullyDrawn()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统授权页返回、或网关在别处被启动后回到应用：把浮窗状态对齐到「真实运行态」。
        runCatching {
            if (!Gateway.isInitialized()) return
            // 用 serverRunning() 而不是 isRunning()：停止过程中前者才是「端口是否真的还活着」，
            // 与 Service 侧 syncOverlay 的判定保持一致，避免两边互相 show/hide 打架。
            if (!Gateway.store.config.ui.overlay || !Gateway.serverRunning() || !AddressOverlay.canDraw(this)) {
                AddressOverlay.hide()
                return
            }
            AddressOverlay.show(this, "127.0.0.1:${Gateway.port}")
        }
    }
}
