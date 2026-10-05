package com.btmouse.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.btmouse.core.state.SettingsStore
import com.btmouse.ui.MainViewModel

/**
 * SettingsScreen —— 设置页
 *
 * 本批次已可用：灵敏度、平滑下限、滚轮手感（每格像素）。
 * 滑块改动**实时生效**并立即持久化 —— 拖动时可以直接在电脑上感受光标变化。
 *
 * 注意：这些参数只注入到 TouchInputHandler 的公开属性上，**不触碰**
 * BluetoothHidManager / SendQueue 的核心逻辑。
 *
 * @param onBack 返回模式选择页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val settings = vm.settings

    // 本地滑块状态：拖动时立即反映到 UI，同时写入 ViewModel（实时生效 + 持久化）
    var sensitivity by remember { mutableFloatStateOf(settings.sensitivity) }
    var smoothing by remember { mutableFloatStateOf(settings.smoothing) }
    var pxPerClick by remember { mutableFloatStateOf(settings.pixelsPerScrollClick) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = ArrowBackIcon,
                            contentDescription = "返回",
                            tint = scheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface)
            )
        },
        containerColor = scheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------------- 手感参数 ----------------
            SectionTitle("手感参数", "拖动滑块会立即生效，可边拖边看电脑端光标变化")

            SettingsCard {
                SliderRow(
                    title = "灵敏度",
                    hint = "越大光标移动越快",
                    valueText = String.format("%.2f×", sensitivity),
                    value = sensitivity,
                    range = SettingsStore.SENSITIVITY_RANGE,
                    onChange = {
                        sensitivity = it
                        vm.updateSensitivity(it)
                    }
                )

                Spacer(Modifier.height(18.dp))

                SliderRow(
                    title = "平滑下限",
                    hint = "越大越跟手；高速移动时自动不做平滑",
                    valueText = String.format("%.2f", smoothing),
                    value = smoothing,
                    range = SettingsStore.SMOOTHING_RANGE,
                    onChange = {
                        smoothing = it
                        vm.updateSmoothing(it)
                    }
                )

                Spacer(Modifier.height(18.dp))

                SliderRow(
                    title = "滚轮速度",
                    hint = "每格滚动所需的滑动像素，越小滚得越快",
                    valueText = "${pxPerClick.toInt()} px/格",
                    value = pxPerClick,
                    range = SettingsStore.PX_PER_CLICK_RANGE,
                    onChange = {
                        pxPerClick = it
                        vm.updatePixelsPerScrollClick(it)
                    }
                )
            }

            // ---------------- 复位 ----------------
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        val reset = vm.resetSettings()
                        sensitivity = reset.sensitivity
                        smoothing = reset.smoothing
                        pxPerClick = reset.pixelsPerScrollClick
                    },
                shape = RoundedCornerShape(16.dp),
                color = scheme.surfaceVariant,
                tonalElevation = 1.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "恢复默认手感",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // ---------------- 说明 ----------------
            SectionTitle("参数说明", null)
            SettingsCard {
                HintLine("灵敏度", "整体位移倍率。偏慢就调大，过冲就调小。")
                Spacer(Modifier.height(10.dp))
                HintLine("平滑下限", "低速时抑制手抖的程度。调大更跟手，调小更稳但有粘滞感。")
                Spacer(Modifier.height(10.dp))
                HintLine("滚轮速度", "仅影响滚轮（双指滑动 / 体感模式的滚动条）。")
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "滚轮方向如需反转，将在后续版本提供开关（当前由代码常量控制）。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String?) {
    Column(modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SliderRow(
    title: String,
    hint: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.primaryContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range
        )
    }
}

@Composable
private fun HintLine(label: String, text: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------- 内置矢量图标 ----------------

private fun settingsIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            pathBuilder = block
        )
    }.build()

private val ArrowBackIcon: ImageVector = settingsIcon("ArrowBack") {
    moveTo(19f, 12f); lineTo(5f, 12f)
    moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
}

