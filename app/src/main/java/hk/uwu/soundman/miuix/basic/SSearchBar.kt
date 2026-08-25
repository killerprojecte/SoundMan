// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.basic

import android.annotation.SuppressLint
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import hk.uwu.soundman.ui.basic.rememberSafeBackdrop
import hk.uwu.soundman.ui.effects.edgelight.edgeLight
import hk.uwu.soundman.ui.effects.edgelight.rememberLiquidTopBarButtonEdgeLight
import hk.uwu.soundman.ui.utils.isAppDarkTheme
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.hasFocusReassignBug
import kotlin.time.Duration.Companion.milliseconds

/**
 * 搜索框组件（S 版本）
 *
 * @param inputField 输入框组件
 * @param onExpandedChange 展开状态变化回调
 * @param modifier 修饰符
 * @param insideMargin 内边距
 * @param expanded 是否展开显示搜索结果
 * @param actionIcon 展开时右侧显示的图标
 * @param actionIconSize 图标大小
 * @param onActionClick 图标点击回调
 * @param backdrop 液体玻璃 Backdrop
 * @param backdropAlpha Backdrop 透明度
 * @param content 展开时显示的内容
 */
@Composable
fun SSearchBar(
    inputField: @Composable () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    insideMargin: DpSize = SSearchBarDefaults.InsideMargin,
    expanded: Boolean = false,
    actionIcon: ImageVector? = null,
    actionIconSize: Dp = 23.dp,
    onActionClick: (() -> Unit)? = null,
    backdrop: Backdrop? = null,
    backdropAlpha: Float = 0f,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)
    val safeBackdrop = rememberSafeBackdrop(backdrop, "SSearchBar")
    val navigationEventState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)

    Column(
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = insideMargin.height, horizontal = insideMargin.width),
            ) {
                inputField()
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandHorizontally() + slideInHorizontally(initialOffsetX = { it }),
                exit = shrinkHorizontally() + slideOutHorizontally(targetOffsetX = { it }),
            ) {
                val scale by transition.animateFloat(
                    transitionSpec = { tween(durationMillis = 300) },
                    label = "scale"
                ) { enterExit ->
                    if (enterExit == EnterExitState.Visible) 1f else 0.6f
                }
                val blur by transition.animateDp(
                    transitionSpec = { tween(durationMillis = 300) },
                    label = "blur"
                ) { enterExit ->
                    if (enterExit == EnterExitState.Visible) 0.dp else 7.dp
                }

                if (actionIcon != null && onActionClick != null) {

                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(SSearchBarDefaults.InputFieldMinHeight)
                            .blur(blur)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                alpha = scale
                            }
                            .clip(CircleShape)
                            .clickable { onActionClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (safeBackdrop != null) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .drawBackdrop(
                                        backdrop = safeBackdrop,
                                        shape = { CircleShape },
                                        effects = {
                                            vibrancy()
                                            blur(4f.dp.toPx())
                                            lens(15f.dp.toPx(), 15f.dp.toPx())
                                        },
                                        highlight = null,
                                        shadow = null,
                                        layerBlock = { alpha = backdropAlpha },
                                        onDrawSurface = {}
                                    )
                                    .edgeLight(
                                        shape = CircleShape,
                                        edgeLight = rememberLiquidTopBarButtonEdgeLight()
                                    )
                            )
                        }
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    color = if (safeBackdrop != null) {
                                        MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
                                    } else MiuixTheme.colorScheme.surfaceContainerHigh,
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = actionIcon,
                                contentDescription = null,
                                modifier = Modifier.size(actionIconSize),
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MiuixTheme.colorScheme.surface,
                )
                .clip(ContinuousRoundedRectangle(20.dp))
        ) {
            content()
        }
    }

    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = expanded,
        onBackCompleted = {
            currentOnExpandedChange(false)
        },
    )
}

/**
 * 搜索输入框组件（S 版本）
 *
 * @param query 当前查询文本
 * @param onQueryChange 查询文本变化回调
 * @param onSearch 搜索触发回调
 * @param expanded 是否展开
 * @param onExpandedChange 展开状态变化回调
 * @param modifier 修饰符
 * @param label 输入框未聚焦时显示的提示文本
 * @param enabled 是否启用
 * @param textStyle 文本样式
 * @param leadingIcon 前置图标
 * @param trailingIcon 后置图标
 * @param interactionSource 交互源
 * @param backdrop 液体玻璃 Backdrop
 * @param backdropAlpha Backdrop 透明度
 * @param shadowAlpha 阴影透明度
 */
@SuppressLint("UseKtx")
@Composable
fun SInputField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    enabled: Boolean = true,
    textStyle: TextStyle? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    backdrop: Backdrop? = null,
    backdropAlpha: Float = 1f,
    shadowAlpha: Float = 0f,
) {
    val currentOnQueryChange by rememberUpdatedState(onQueryChange)
    val currentOnSearch by rememberUpdatedState(onSearch)
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)
    val internalInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val safeBackdrop = rememberSafeBackdrop(backdrop, "SInputField")
    val capsuleShape = ContinuousCapsule()

    val actualLeadingIcon = leadingIcon ?: {
        Box(
            modifier = Modifier.padding(
                start = SSearchBarDefaults.LeadingIconStartPadding,
                end = SSearchBarDefaults.LeadingIconEndPadding
            )
        ) {
            Icon(
                modifier = Modifier.size(24.dp),
                imageVector = MiuixIcons.Basic.Search,
                tint = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                contentDescription = "搜索",
            )
        }
    }

    val actualTrailingIcon = trailingIcon ?: {
        AnimatedVisibility(
            visible = query.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier.padding(
                    start = SSearchBarDefaults.TrailingIconStartPadding,
                    end = SSearchBarDefaults.TrailingIconEndPadding
                ),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    modifier = Modifier
                        .clip(capsuleShape)
                        .clickable { currentOnQueryChange("") },
                    imageVector = MiuixIcons.Basic.SearchCleanup,
                    tint = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                    contentDescription = "清除",
                )
            }
        }
    }

    val focused = internalInteractionSource.collectIsFocusedAsState().value
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val textAlpha = remember { Animatable(1f) }

    val textColor = LocalContentColor.current
    val inputTextStyle = MiuixTheme.textStyles.main
        .copy(fontWeight = FontWeight.Medium)
        .merge(textStyle)
        .copy(color = textColor)

    val cursorBrush = SolidColor(MiuixTheme.colorScheme.primary)
    val labelText by remember(query, expanded, label) {
        derivedStateOf { if (!(query.isNotEmpty() || expanded)) label else "" }
    }

    // API 26-27 的 bug 修复：收起时禁用 TextField 防止焦点错误重分配
    val workaroundEnabled = !hasFocusReassignBug || expanded
    val expandOnTapModifier = if (workaroundEnabled || !enabled) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) { detectTapGestures { currentOnExpandedChange(true) } }
    }

    BasicTextField(
        value = query,
        onValueChange = currentOnQueryChange,
        modifier = modifier
            .then(expandOnTapModifier)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) currentOnExpandedChange(true) }
            .semantics {
                onClick {
                    focusRequester.requestFocus()
                    true
                }
            },
        enabled = enabled && workaroundEnabled,
        singleLine = true,
        textStyle = inputTextStyle,
        cursorBrush = cursorBrush,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { currentOnSearch(query) }),
        interactionSource = internalInteractionSource,
        decorationBox = { innerTextField ->
            val isLightTheme = !isAppDarkTheme()
            val containerColor = if (isLightTheme) Color(0xFFFFFFFF).copy(0.76f)
            else Color(0xFF242424).copy(0.84f)
            val shadowColor = if (isLightTheme) android.graphics.Color.parseColor("#12000000")
            else android.graphics.Color.parseColor("#20000000")

            Box(
                modifier = Modifier
                    .then(
                        if (safeBackdrop != null) {
                            Modifier.drawBehind {
                                if (shadowAlpha > 0.01f) {
                                    val maxBlurRadius = 10f * density
                                    val blurRadius = maxBlurRadius * shadowAlpha
                                    val composePath = Path().apply {
                                        val outline = capsuleShape.createOutline(
                                            size,
                                            layoutDirection,
                                            this@drawBehind
                                        )
                                        when (outline) {
                                            is Outline.Rounded -> addRoundRect(outline.roundRect)
                                            is Outline.Generic -> addPath(outline.path)
                                            is Outline.Rectangle -> addRect(outline.rect)
                                        }
                                    }
                                    val path = composePath.asAndroidPath()
                                    val paint = Paint().apply {
                                        color = android.graphics.Color.argb(
                                            (android.graphics.Color.alpha(shadowColor) * 1f).coerceAtMost(
                                                255f
                                            ).toInt(),
                                            android.graphics.Color.red(shadowColor),
                                            android.graphics.Color.green(shadowColor),
                                            android.graphics.Color.blue(shadowColor)
                                        )
                                        maskFilter = BlurMaskFilter(
                                            blurRadius.coerceAtLeast(0.1f),
                                            BlurMaskFilter.Blur.NORMAL
                                        )
                                    }
                                    drawIntoCanvas { canvas ->
                                        canvas.nativeCanvas.drawPath(path, paint)
                                    }
                                }
                            }
                        } else Modifier
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (safeBackdrop != null) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .drawBackdrop(
                                backdrop = safeBackdrop,
                                shape = { capsuleShape },
                                effects = {
                                    vibrancy()
                                    blur(4f.dp.toPx())
                                    lens(8f.dp.toPx(), 24f.dp.toPx())
                                },
                                highlight = null,
                                shadow = null,
                                layerBlock = {
                                    alpha = backdropAlpha
                                },
                                onDrawSurface = {}
                            )
                            .edgeLight(
                                shape = capsuleShape,
                                edgeLight = rememberLiquidTopBarButtonEdgeLight()
                            )
                    )
                }
                Row(
                    modifier = Modifier
                        .background(
                            color = if (safeBackdrop != null) {
                                lerp(
                                    MiuixTheme.colorScheme.surfaceContainerHigh,
                                    containerColor,
                                    backdropAlpha
                                )
                            } else MiuixTheme.colorScheme.surfaceContainerHigh,
                            shape = capsuleShape,
                        )
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actualLeadingIcon()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = SSearchBarDefaults.InputFieldMinHeight),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        val mergedLabelStyle = remember(textStyle) {
                            TextStyle(
                                fontSize = SSearchBarDefaults.InputFieldFontSize,
                                fontWeight = FontWeight.Medium
                            ).merge(textStyle)
                        }
                        Text(
                            text = labelText,
                            style = mergedLabelStyle,
                            color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                        )
                        Box(modifier = Modifier.graphicsLayer { alpha = textAlpha.value }) {
                            innerTextField()
                        }
                    }
                    actualTrailingIcon()
                }
            }
        },
    )

    LaunchedEffect(expanded) {
        if (expanded) {
            focusRequester.requestFocus()
        } else if (focused) {
            delay(100.milliseconds)
            if (query.isNotEmpty()) {
                textAlpha.animateTo(0f)
                currentOnQueryChange("")
                textAlpha.snapTo(1f)
            }
            focusManager.clearFocus()
        }
    }
}

/** 默认值配置（S 版本） */
object SSearchBarDefaults {
    val InsideMargin = DpSize(10.dp, 0.dp)
    val InputFieldMinHeight = 45.dp
    val InputFieldFontSize = 16.sp
    val LeadingIconStartPadding = 12.dp
    val LeadingIconEndPadding = 8.dp
    val TrailingIconStartPadding = 8.dp
    val TrailingIconEndPadding = 16.dp
}
