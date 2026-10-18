package dev.agentpulse.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic palette of design report 4.2.A (OLED dark-first). Hex values are reproduced exactly
 * from the spec; the comments carry the report's WCAG notes.
 */
object PulseColors {
    val Background = Color(0xFF0D1117)      // 深度暗黑背景，降低 OLED 功耗
    val Surface = Color(0xFF161B22)         // 卡片容器表面色
    val SurfaceVariant = Color(0xFF21262D)  // 次级容器 / 输入底色
    val Outline = Color(0xFF30363D)         // 1dp 容器分割线

    val PrimaryCyan = Color(0xFF38BDF8)     // 活跃进行中强调色 (对比度 8.2:1)
    val SuccessGreen = Color(0xFF34D399)    // 成功/完成色 (对比度 9.5:1)
    val WarningAmber = Color(0xFFFBBF24)    // 阻塞/决策中色 (对比度 10.1:1)
    val ErrorRose = Color(0xFFF43F5E)       // 失败/异常中断色 (对比度 6.8:1)

    val TextPrimary = Color(0xFFF0F6FC)     // 一级主要文本 (对比度 13.5:1)
    val TextSecondary = Color(0xFF8B949E)   // 二级辅助注释 (对比度 5.8:1)
    val TextMuted = Color(0xFF484F58)       // 占位符/极弱文本
    val CodeTerminalBg = Color(0xFF080B0F)  // 终端代码区域黑底

    /** Terminal focus highlight from the TUI palette (report 5.3), reused for selection accents. */
    val FocusPurple = Color(0xFFA78BFA)
}
