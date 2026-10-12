package com.aimini.statusbarmon.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aimini.statusbarmon.BuildConfig
import com.aimini.statusbarmon.R
import com.aimini.statusbarmon.core.Formatter
import com.aimini.statusbarmon.core.MetricsProvider
import com.aimini.statusbarmon.core.SystemUiRestarter
import com.aimini.statusbarmon.settings.MonitorSettings
import com.aimini.statusbarmon.settings.SettingsStore
import kotlinx.coroutines.delay

private data class GaugeState(
    val cpuPercent: Float = 0f,
    val freqLabel: String = "--",
    val memPercent: Float = 0f,
    val tempLabel: String = "--",
    val tempLabelC: String = "--℃",
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = SettingsStore.prefs(this)
        setContent {
            MaterialTheme {
                SettingsScreen(
                    initial = MonitorSettings.from(prefs),
                    onSave = { settings -> MonitorSettings.write(prefs, settings) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(initial: MonitorSettings, onSave: (MonitorSettings) -> Unit) {
    var settings by remember { mutableStateOf(initial) }
    val latest by rememberUpdatedState(settings)
    val context = LocalContext.current
    var showRestartDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // 拖动滑块时变化频繁，防抖后再落盘；离开页面时兜底保存一次。
    LaunchedEffect(settings) {
        delay(250)
        onSave(settings)
    }
    DisposableEffect(Unit) { onDispose { onSave(latest) } }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text("重启 SystemUI") },
            text = { Text("即将重启 SystemUI，以使设置生效，是否重启？") },
            confirmButton = {
                TextButton(onClick = {
                    showRestartDialog = false
                    SystemUiRestarter.restart(context)
                }) { Text("重启") }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) { Text("取消") }
            },
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("关于") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("状态栏监控 v${BuildConfig.VERSION_NAME}")
                    Text("GitHub：https://github.com/1103945342/StatusBarMon")
                    Text("Powered by: Fluoxetine")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("确定") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("状态栏监控") },
                actions = {
                    IconButton(onClick = { showRestartDialog = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_restart),
                            contentDescription = "重启 SystemUI",
                        )
                    }
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_info),
                            contentDescription = "关于",
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MasterSwitch(settings) { settings = settings.copy(enabled = it) }
            PreviewCard(settings)
            ModeCard(settings) { settings = it }
            DisplayCard(settings) { settings = it }
            AppearanceCard(settings) { settings = it }
            IntervalCard(settings) { settings = it }
        }
    }
}

@Composable
private fun MasterSwitch(settings: MonitorSettings, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("启用模块", fontWeight = FontWeight.SemiBold)
        }
        Switch(checked = settings.enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun PreviewCard(settings: MonitorSettings) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(GaugeState()) }

    // 预览直接读真实指标并即时重绘，外观改动（字号/间距/颜色/双排）实时反映。
    LaunchedEffect(settings) {
        while (true) {
            val snapshot = MetricsProvider.read(context)
            state = GaugeState(
                cpuPercent = ((snapshot.cpuPercent ?: 0.0) / 100.0).toFloat(),
                freqLabel = Formatter.plainFreq(snapshot.cpuFreqHz),
                memPercent = ((snapshot.memPercent ?: 0.0) / 100.0).toFloat(),
                tempLabel = Formatter.plainTemp(snapshot.batteryTempC),
                tempLabelC = snapshot.batteryTempC?.let { "%.0f℃".format(it) } ?: "--℃",
            )
            delay(settings.intervalMs.toLong())
        }
    }

    val diameter = (settings.textSizeSp * 2.6f).dp
    val textColor = if (settings.textColor == MonitorSettings.COLOR_AUTO) Color(0xFFB0B8C4)
    else Color(settings.textColor)
    val textTop = settings.displayMode == MonitorSettings.DisplayMode.TEXT_TOP

    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101418))) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("预览（实时读取本机）", fontSize = 12.sp, color = Color(0xFF9AA4B2))
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1C2430))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                // 顶部文字方案：水平居中贴顶显示。
                if (settings.enabled && textTop) {
                    Text(
                        text = "CPU:%.0f%% MEM:%.0f%% %s".format(
                            state.cpuPercent * 100f,
                            state.memPercent * 100f,
                            state.tempLabelC,
                        ),
                        color = textColor,
                        fontSize = settings.textSizeSp.sp,
                        letterSpacing = settings.letterSpacing.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.TopCenter),
                        maxLines = 1,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "12:00", color = Color(0xFFB0B8C4), fontSize = 12.sp)
                        Spacer(Modifier.size(6.dp))
                        if (settings.enabled) {
                            if (settings.twoLine) {
                                Column {
                                    GaugeGroup("CPU:", state.cpuPercent, state.freqLabel, diameter, textColor, settings, 0.85f)
                                    Spacer(Modifier.height(2.dp))
                                    GaugeGroup("MEM:", state.memPercent, state.tempLabel, diameter, textColor, settings, 0.85f)
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    GaugeGroup("CPU:", state.cpuPercent, state.freqLabel, diameter, textColor, settings, 0.85f)
                                    Spacer(Modifier.size(6.dp))
                                    GaugeGroup("MEM:", state.memPercent, state.tempLabel, diameter, textColor, settings, 0.85f)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GaugeGroup(
    prefix: String,
    progress: Float,
    label: String,
    diameter: Dp,
    textColor: Color,
    settings: MonitorSettings,
    prefixScale: Float = 0.85f,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = prefix,
            color = textColor,
            fontSize = (settings.textSizeSp * prefixScale).sp,
            letterSpacing = settings.letterSpacing.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Spacer(Modifier.size(2.dp))
        Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.16f
                val radius = (size.minDimension - stroke) / 2f
                val topLeft = Offset(size.width / 2f - radius, size.height / 2f - radius)
                val arcSize = Size(radius * 2, radius * 2)
                drawArc(
                    color = Color(0x33FFFFFF),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = gaugeColor(progress),
                    startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            Text(
                text = label,
                color = textColor,
                fontSize = settings.textSizeSp.sp,
                letterSpacing = settings.letterSpacing.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

private fun gaugeColor(progress: Float): Color = when {
    progress < 0.5f -> Color(0xFF4CAF50)
    progress < 0.8f -> Color(0xFFFFC107)
    else -> Color(0xFFF44336)
}

@Composable
private fun ModeCard(settings: MonitorSettings, onChange: (MonitorSettings) -> Unit) {
    SettingsCard("显示方案") {
        val modes = listOf(
            MonitorSettings.DisplayMode.RING to "圆环（时钟后）",
            MonitorSettings.DisplayMode.TEXT_TOP to "顶部文字",
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            modes.forEach { (mode, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChange(settings.copy(displayMode = mode)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.displayMode == mode,
                        onClick = { onChange(settings.copy(displayMode = mode)) },
                    )
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun DisplayCard(settings: MonitorSettings, onChange: (MonitorSettings) -> Unit) {
    SettingsCard("显示方式") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("双排显示")
                Text(
                    "开：CPU 与 MEM 上下两排；关：同一排横向并排",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(checked = settings.twoLine, onCheckedChange = { onChange(settings.copy(twoLine = it)) })
        }
    }
}

@Composable
private fun AppearanceCard(settings: MonitorSettings, onChange: (MonitorSettings) -> Unit) {
    SettingsCard("外观") {
        LabeledSlider(
            label = "字号",
            valueText = "%.1f sp".format(settings.textSizeSp),
            value = settings.textSizeSp,
            range = 6f..16f,
        ) { onChange(settings.copy(textSizeSp = it)) }

        LabeledSlider(
            label = "字间距",
            valueText = "%.1f sp".format(settings.letterSpacing),
            value = settings.letterSpacing,
            range = -0.5f..3f,
        ) { onChange(settings.copy(letterSpacing = it)) }

        ColorRow(settings, onChange)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("描边（深色描边，浅背景也能看清）")
            Checkbox(checked = settings.outline, onCheckedChange = { onChange(settings.copy(outline = it)) })
        }
    }
}

@Composable
private fun IntervalCard(settings: MonitorSettings, onChange: (MonitorSettings) -> Unit) {
    SettingsCard("刷新") {
        LabeledSlider(
            label = "刷新间隔",
            valueText = "${settings.intervalMs} ms",
            value = settings.intervalMs.toFloat(),
            range = MonitorSettings.MIN_INTERVAL_MS.toFloat()..MonitorSettings.MAX_INTERVAL_MS.toFloat(),
            steps = 18,
        ) { onChange(settings.copy(intervalMs = it.toInt())) }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label)
            Text(valueText, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun ColorRow(settings: MonitorSettings, onChange: (MonitorSettings) -> Unit) {
    val presets = listOf(
        "跟随状态栏" to MonitorSettings.COLOR_AUTO,
        "白色" to 0xFFFFFFFF.toInt(),
        "青色" to 0xFF00E5FF.toInt(),
        "绿色" to 0xFF76FF03.toInt(),
        "橙色" to 0xFFFFAB40.toInt(),
        "红色" to 0xFFFF5252.toInt(),
    )
    Column {
        Text("颜色")
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            presets.forEach { (name, color) ->
                val selected = settings.textColor == color
                val isAuto = color == MonitorSettings.COLOR_AUTO
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onChange(settings.copy(textColor = color)) },
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(if (isAuto) Color(0xFFB0B8C4) else Color(color), CircleShape)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isAuto) {
                            Text("A", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = name,
                        fontSize = 10.sp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
