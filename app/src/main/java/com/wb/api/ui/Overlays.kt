package com.wb.api.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 轻量 Toast：底部居中的半透明胶囊，2.2s 自动消失。
 *
 * Miuix 没有 Toast 组件（只有 Snackbar），但两者语义不同：
 *  - **Toast** 用于「操作已发生」的瞬时回执（签到成功 / 已复制 / 已重载），不打断、不带操作；
 *  - **Snackbar** 用于需要用户注意的消息（启动失败、登录失败），带更强的视觉与停留。
 * 本文件补上 Toast，AppRoot 按消息性质分流到二者。
 */
@Composable
fun WbToastHost(text: String?, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 3 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(220)) { it / 3 },
        modifier = modifier,
    ) {
        val current = text ?: return@AnimatedVisibility
        LaunchedEffect(current) {
            delay(2200)
            onDismiss()
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .wbGlass(
                        shape = com.kyant.shapes.Capsule(com.kyant.shapes.RoundedCornerStyle.Continuous),
                        tint = WbGlass.neutralTint(),
                        enableShadow = true,
                    )
                    .padding(horizontal = WbD.cardPad, vertical = WbD.gap + 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    current,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * 键值行（值可换行）：用于「接口」这类**长文本**，标签在上、值在下。
 * 之前把它塞进单行 `label + value` 的 [WbRow]，长值会把右侧挤压并与标签重叠。
 */
@Composable
fun WbKeyValueRow(
    label: String,
    value: String,
    showDivider: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = WbD.tapMin)
                .padding(horizontal = WbD.rowPadH, vertical = WbD.rowPadV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                WbHSpace(WbD.gap)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WbD.gapXs)) {
                Text(
                    label,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    value,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = WbD.rowPadH),
                color = MiuixTheme.colorScheme.dividerLine,
            )
        }
    }
}

/** 带图标的提示块（比 WbBanner 更强调，用于配置说明/风险提示）。 */
@Composable
fun WbHintBlock(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WbD.radiusS))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(WbD.gap),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        WbHSpace(WbD.gapS)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WbD.gapXs)) {
            Text(title, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            Text(body, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
