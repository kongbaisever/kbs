package kbs.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kbs.core.codec.PulseCodec
import kbs.ui.theme.LocalAccent

// ============================================================
// 可折叠卡片
// ============================================================

/**
 * 可折叠卡片。
 *
 * 每个模块都能独立收起，收起状态持久化 —— 老手收起「新手指南」后
 * 下次打开保持收起，不必每次重复操作。
 */
@Composable
fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                    content()
                }
            }
        }
    }
}

// ============================================================
// 输入字段
// ============================================================

/**
 * 带标签的数字输入框。
 *
 * ★ on 是最后一个参数，可用尾随 lambda 调用：
 *     Field("目标 X", value, Modifier.weight(1f)) { vm.update { copy(destX = it) } }
 *
 *   若把 on 放在中间，调用时 `Field(..., Modifier.weight(1f)) { }`
 *   的 Modifier 会被绑定到 on 上，报 "Modifier but (String) -> Unit" 类型错误。
 */
@Composable
fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
    onIme: (() -> Unit)? = null,
    on: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = on,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onAny = { onIme?.invoke() },
        ),
        shape = RoundedCornerShape(8.dp),
    )
}

// ============================================================
// 炮码阵列图
// ============================================================

/**
 * 权重槽阵列图。
 *
 * 玩家无需去数 18 位 0/1，直接看哪几个权重位要摆即可。
 *
 * @param group 一组（主向或副向）的槽位状态
 */
@Composable
fun SlotRow(group: PulseCodec.SlotGroup) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                group.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "需摆 ${group.litCount} 处",
                style = MaterialTheme.typography.labelSmall,
                color = if (group.litCount > 0) LocalAccent.current
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            group.slots.forEach { slot ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(width = 34.dp, height = 30.dp)
                            .background(
                                color = if (slot.lit) LocalAccent.current
                                else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(5.dp),
                            )
                            .then(
                                if (slot.lit) Modifier.border(
                                    width = 1.dp,
                                    color = LocalAccent.current,
                                    shape = RoundedCornerShape(5.dp),
                                ) else Modifier
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (slot.lit) "■" else "□",
                            color = if (slot.lit) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        slot.weight.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (slot.lit) LocalAccent.current
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// ============================================================
// 炮码整行（可点击复制）
// ============================================================

/**
 * 炮码显示行。整行可点击复制 —— 复制是最高频操作，
 * 不需要用户精确瞄准文字。
 */
@Composable
fun CodeLine(
    code: String,
    copied: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = if (copied) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            code,
            style = MaterialTheme.typography.titleSmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            color = if (copied) MaterialTheme.colorScheme.onPrimaryContainer
            else LocalAccent.current,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (copied) "已复制 ✓" else "复制",
            style = MaterialTheme.typography.labelSmall,
            color = if (copied) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================================================
// 键值行
// ============================================================

/** 左标签右数值的一行，用于展示只读信息 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

// ============================================================
// 分段选择
// ============================================================

/**
 * 简单的分段选择器，用于二选一/三选一。
 */
@Composable
fun SegmentedChoice(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (id, label) ->
            val isSel = id == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = if (isSel) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(7.dp),
                    )
                    .clickable { onSelect(id) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 带说明文字的分段选择 */
@Composable
fun LabeledChoice(
    title: String,
    description: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        SegmentedChoice(options, selected, onSelect)
        Spacer(Modifier.height(4.dp))
        Text(
            description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
