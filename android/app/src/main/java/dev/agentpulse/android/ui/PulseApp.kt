package dev.agentpulse.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.agentpulse.android.data.PulseUiState
import dev.agentpulse.android.data.model.PulseAlert
import dev.agentpulse.android.ui.common.ConnectionBanners
import dev.agentpulse.android.ui.common.ConnectionChip
import dev.agentpulse.android.ui.common.CriticalAlertBanner
import dev.agentpulse.android.ui.common.PulseClock
import dev.agentpulse.android.ui.common.PulseStatusDot
import dev.agentpulse.android.ui.screens.AlertsScreen
import dev.agentpulse.android.ui.screens.AlertSheet
import dev.agentpulse.android.ui.screens.LiveFeedScreen
import dev.agentpulse.android.ui.screens.OverviewScreen
import dev.agentpulse.android.ui.screens.SettingsDialog
import dev.agentpulse.android.ui.screens.TaskDetailOverlay
import dev.agentpulse.android.ui.screens.TasksScreen
import dev.agentpulse.android.ui.theme.PulseColors
import dev.agentpulse.android.ui.theme.PulseSpacing

private data class PulseDestination(
    val tab: PulseTab,
    val icon: ImageVector,
    val label: String,
)

private val destinations = listOf(
    PulseDestination(PulseTab.Overview, Icons.Filled.Home, "总览"),
    PulseDestination(PulseTab.Tasks, Icons.Filled.List, "任务"),
    PulseDestination(PulseTab.Live, Icons.Filled.PlayArrow, "动态"),
    PulseDestination(PulseTab.Alerts, Icons.Filled.Warning, "告警"),
)

/**
 * App scaffold: global top bar + connection banners + responsive navigation.
 *
 * Compact (phone portrait) → bottom navigation bar, single pane.
 * TwoPane (landscape / small tablet) → left navigation rail.
 * ThreePane (tablet) → navigation rail + 25% / 45% / 30% three-column board.
 */
@Composable
fun PulseApp(viewModel: AppViewModel, initialTab: String? = null) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val window = rememberPulseWindowClass()

    val alertCount = uiState.pendingAlertCount

    LaunchedEffect(initialTab) {
        if (initialTab == dev.agentpulse.android.MainActivity.TAB_ALERTS) {
            viewModel.selectTab(PulseTab.Alerts)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(PulseColors.Background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            GlobalTopBar(
                state = uiState,
                selectedTab = selectedTab,
                alertCount = alertCount,
                onRefresh = viewModel::refresh,
                onOpenSettings = viewModel::openSettings,
            )
            ConnectionBanners(uiState.connection.state)
            CriticalAlertBanner(
                alerts = uiState.criticalAlerts,
                onOpenAlerts = { viewModel.selectTab(PulseTab.Alerts) },
            )

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (window != PulseWindowClass.Compact) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = PulseColors.Surface,
                    ) {
                        Spacer(Modifier.weight(1f))
                        destinations.forEach { dest ->
                            NavigationRailItem(
                                selected = selectedTab == dest.tab,
                                onClick = { viewModel.selectTab(dest.tab) },
                                icon = {
                                    TabIcon(
                                        dest = dest,
                                        badgeCount = if (dest.tab == PulseTab.Alerts) alertCount else 0,
                                        isCritical = uiState.criticalAlertCount > 0,
                                    )
                                },
                                label = { Text(dest.label) },
                                alwaysShowLabel = true,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }

                when (window) {
                    PulseWindowClass.Compact -> ScreenBody(
                        modifier = Modifier.weight(1f),
                        tab = selectedTab,
                        state = uiState,
                        viewModel = viewModel,
                    )

                    PulseWindowClass.TwoPane -> ScreenBody(
                        modifier = Modifier.weight(1f),
                        tab = selectedTab,
                        state = uiState,
                        viewModel = viewModel,
                    )

                    PulseWindowClass.ThreePane -> ThreePaneBoard(
                        modifier = Modifier.weight(1f),
                        state = uiState,
                        viewModel = viewModel,
                    )
                }
            }

            if (window == PulseWindowClass.Compact) {
                NavigationBar(containerColor = PulseColors.Surface) {
                    destinations.forEach { dest ->
                        NavigationBarItem(
                            selected = selectedTab == dest.tab,
                            onClick = { viewModel.selectTab(dest.tab) },
                            icon = {
                                TabIcon(
                                    dest = dest,
                                    badgeCount = if (dest.tab == PulseTab.Alerts) alertCount else 0,
                                    isCritical = uiState.criticalAlertCount > 0,
                                )
                            },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        }

        // ---- overlays ---------------------------------------------------
        if (viewModel.showSettings.collectAsState().value) {
            SettingsDialog(viewModel = viewModel)
        }
        val detailTaskId = viewModel.detailTaskId.collectAsState().value
        if (detailTaskId != null && window == PulseWindowClass.Compact) {
            TaskDetailOverlay(
                taskId = detailTaskId,
                state = uiState,
                viewModel = viewModel,
                onBack = viewModel::closeDetail,
            )
        }
        val alertSheetId = viewModel.alertSheetId.collectAsState().value
        if (alertSheetId != null) {
            val alert = uiState.alerts.firstOrNull { it.alertId == alertSheetId }
            if (alert != null) {
                AlertSheet(alert = alert, onDismiss = viewModel::closeAlertSheet)
            }
        }
    }
}

@Composable
private fun TabIcon(dest: PulseDestination, badgeCount: Int, isCritical: Boolean = false) {
    Box {
        Icon(dest.icon, contentDescription = dest.label)
        if (badgeCount > 0) {
            val text = if (badgeCount > 99) "99+" else badgeCount.toString()
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                color = if (isCritical) PulseColors.ErrorRose else PulseColors.WarningAmber,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text(
                    text = text,
                    color = PulseColors.Background,
                    style = dev.agentpulse.android.ui.theme.PulseMonoText.chip,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun ScreenBody(
    modifier: Modifier,
    tab: PulseTab,
    state: PulseUiState,
    viewModel: AppViewModel,
) {
    Box(modifier = modifier) {
        when (tab) {
            PulseTab.Overview -> OverviewScreen(
                state = state,
                onRefresh = viewModel::refresh,
                onOpenTask = viewModel::openTaskDetail,
                onOpenSettings = viewModel::openSettings,
            )

            PulseTab.Tasks -> TasksScreen(
                state = state,
                onOpenTask = viewModel::openTaskDetail,
                onRefresh = viewModel::refresh,
            )

            PulseTab.Live -> LiveFeedScreen(
                state = state,
                onRefresh = viewModel::refresh,
            )

            PulseTab.Alerts -> AlertsScreen(
                state = state,
                onOpenAlert = viewModel::openAlertSheet,
                onClearRead = viewModel::clearReadAlerts,
            )
        }
    }
}

@Composable
private fun ThreePaneBoard(
    modifier: Modifier,
    state: PulseUiState,
    viewModel: AppViewModel,
) {
    Row(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(0.25f)
                .fillMaxHeight()
                .background(PulseColors.Surface),
        ) {
            OverviewScreen(
                state = state,
                onRefresh = viewModel::refresh,
                onOpenTask = viewModel::openTaskDetail,
                onOpenSettings = viewModel::openSettings,
            )
        }
        VerticalDivider()
        Column(modifier = Modifier.weight(0.45f).fillMaxHeight()) {
            TasksScreen(
                state = state,
                onOpenTask = viewModel::openTaskDetail,
                onRefresh = viewModel::refresh,
            )
        }
        VerticalDivider()
        Column(modifier = Modifier.weight(0.30f).fillMaxHeight()) {
            LiveFeedScreen(state = state, onRefresh = viewModel::refresh)
        }
    }
}

@Composable
private fun VerticalDivider() {
    Spacer(Modifier.width(1.dp).fillMaxHeight().background(PulseColors.Outline))
}

// ---------------------------------------------------------------- top bar

@Composable
private fun GlobalTopBar(
    state: PulseUiState,
    selectedTab: PulseTab,
    alertCount: Int,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PulseColors.Background)
            .padding(horizontal = PulseSpacing.md, vertical = PulseSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseStatusDot(
                    color = dev.agentpulse.android.ui.common.connectionColor(state.connection.state),
                    animated = state.connection.state != dev.agentpulse.android.data.ConnectionState.Disconnected,
                    size = 8.dp,
                )
                Spacer(Modifier.width(PulseSpacing.sm))
                Text(
                    text = "AgentPulse · ${selectedTab.label}",
                    style = dev.agentpulse.android.ui.theme.PulseTypography.titleMedium,
                    color = PulseColors.TextPrimary,
                )
            }
            Text(
                text = state.transport.endpointLabel,
                style = dev.agentpulse.android.ui.theme.PulseMonoText.chip,
                color = PulseColors.TextMuted,
            )
        }
        ConnectionChip(state.connection.state)
        PulseClock(modifier = Modifier.padding(horizontal = PulseSpacing.sm))
        androidx.compose.material3.IconButton(onClick = onRefresh) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "刷新",
                tint = PulseColors.TextSecondary,
            )
        }
        androidx.compose.material3.IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "连接设置",
                tint = PulseColors.TextSecondary,
            )
        }
    }
}
