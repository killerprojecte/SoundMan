/** 仿 Miuix 样式的原生 EditText，使用系统原生选择控件 */
@file:Suppress("DEPRECATION")

package hk.uwu.soundman.miuix.basic

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun NativeMiuixTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    insideMargin: DpSize = DpSize(16.dp, 16.dp),
    cornerRadius: Dp = 20.dp,
    label: String = "",
    useLabelAsPlaceholder: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MiuixTheme.textStyles.main,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    requestFocus: Boolean = false,
) {
    val isFocused = remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val view = LocalView.current
    val editTextHolder = remember { mutableStateOf<EditText?>(null) }
    val lastRequestFocus = remember { mutableStateOf(requestFocus) }

    // 请求焦点并显示键盘
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
            // 延迟显示键盘，确保 EditText 已创建
            delay(150.milliseconds)
            editTextHolder.value?.let { editText ->
                val imm =
                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }
        } else if (lastRequestFocus.value) {
            // requestFocus 从 true 变为 false，说明弹窗正在关闭，隐藏键盘
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            focusManager.clearFocus()
        }
        lastRequestFocus.value = requestFocus
    }

    // 组件被移除时（弹窗关闭）隐藏键盘并清除焦点
    DisposableEffect(Unit) {
        onDispose {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            focusManager.clearFocus()
        }
    }

    val labelState = remember(value, label, useLabelAsPlaceholder) {
        when {
            label.isEmpty() -> LabelAnimState.Hidden
            useLabelAsPlaceholder && value.isNotEmpty() -> LabelAnimState.Placeholder
            value.isNotEmpty() -> LabelAnimState.Floating
            else -> LabelAnimState.Normal
        }
    }

    val themeColor = MiuixTheme.colorScheme.primary
    val backgroundColor = MiuixTheme.colorScheme.secondaryContainer
    val labelColor = MiuixTheme.colorScheme.onSurfaceVariantActions

    val borderColor by animateColorAsState(
        if (isFocused.value) themeColor else Color.Transparent
    )
    val borderWidth by animateDpAsState(
        if (isFocused.value) 2.dp else 0.dp
    )

    val labelAnim by animateDpAsState(
        when (labelState) {
            LabelAnimState.Floating -> -insideMargin.height / 2
            LabelAnimState.Placeholder, LabelAnimState.Normal -> 0.dp
            LabelAnimState.Hidden -> 0.dp
        },
    )
    val labelFontSize by animateDpAsState(
        when (labelState) {
            LabelAnimState.Floating -> 10.dp
            else -> 17.dp
        },
    )

    val textWatcher = remember(onValueChange) {
        object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                s?.toString()?.let { onValueChange(it) }
            }
        }
    }

    val textColor = textStyle.color.takeIf { it != Color.Unspecified }
        ?: MiuixTheme.colorScheme.onSurface
    val hintColor = MiuixTheme.colorScheme.onSurfaceVariantActions
    val weight = textStyle.fontWeight?.weight ?: FontWeight.Medium.weight

    // 请求焦点并显示键盘
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
            // 延迟显示键盘，确保 EditText 已创建
            delay(150.milliseconds)
            editTextHolder.value?.let { editText ->
                val imm =
                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    // 组件被移除时（弹窗关闭）隐藏键盘并清除焦点
    DisposableEffect(Unit) {
        onDispose {
            editTextHolder.value?.let { editText ->
                val imm =
                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(editText.windowToken, 0)
            }
            focusManager.clearFocus()
        }
    }

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .squircleBackground(color = backgroundColor, cornerRadius = cornerRadius)
            .squircleBorder(
                width = borderWidth,
                color = borderColor,
                cornerRadius = cornerRadius
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.invoke()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = insideMargin.width,
                        end = insideMargin.width,
                        top = insideMargin.height,
                        bottom = insideMargin.height
                    ),
                contentAlignment = Alignment.TopStart,
            ) {
                if (labelState == LabelAnimState.Floating || (labelState == LabelAnimState.Normal && !useLabelAsPlaceholder)) {
                    Text(
                        text = label,
                        fontSize = labelFontSize.value.sp,
                        color = labelColor,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.offset { IntOffset(0, labelAnim.roundToPx()) },
                        textAlign = TextAlign.Start,
                    )
                }
                Box(
                    modifier = Modifier.offset(
                        y = if (labelState == LabelAnimState.Floating) insideMargin.height / 2 else 0.dp
                    ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    AndroidView(
                        factory = { ctx ->
                            EditText(ctx).apply {
                                setText(value)
                                this.hint = if (useLabelAsPlaceholder) label else ""
                                this.isSingleLine = singleLine
                                this.maxLines = maxLines
                                this.minLines = minLines
                                setTextColor(textColor.toArgb())
                                setHintTextColor(hintColor.toArgb())
                                textSize = textStyle.fontSize.value
                                typeface = Typeface.create(typeface, weight, false)

                                inputType = InputType.TYPE_CLASS_TEXT
                                imeOptions = EditorInfo.IME_ACTION_DONE

                                background = null
                                setPadding(0, 0, 0, 0)

                                // 光标颜色
                                textCursorDrawable?.setTint(themeColor.toArgb())
                                // 选中文本的背景高亮颜色
                                highlightColor = themeColor.copy(alpha = 0.2f).toArgb()
                                // 选择手柄颜色
                                textSelectHandle?.setTint(themeColor.toArgb())
                                textSelectHandleLeft?.setTint(themeColor.toArgb())
                                textSelectHandleRight?.setTint(themeColor.toArgb())



                                setOnFocusChangeListener { _, focused ->
                                    isFocused.value = focused
                                }

                                addTextChangedListener(textWatcher)

                                // 保存 EditText 引用
                                editTextHolder.value = this
                            }
                        },
                        update = { editText ->
                            if (editText.text.toString() != value) {
                                editText.removeTextChangedListener(textWatcher)
                                editText.setText(value)
                                editText.setSelection(editText.text.length)
                                editText.addTextChangedListener(textWatcher)
                            }
                            editText.isEnabled = enabled
                            editText.isFocusable = !readOnly
                            // 确保高亮颜色始终生效
                            editText.highlightColor = themeColor.copy(alpha = 0.2f).toArgb()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            trailingIcon?.invoke()
        }
    }
}

/**
 * TextFieldValue 版本，用于需要访问光标位置等场景
 */
@Composable
fun NativeMiuixTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    insideMargin: DpSize = DpSize(16.dp, 16.dp),
    cornerRadius: Dp = 20.dp,
    label: String = "",
    useLabelAsPlaceholder: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MiuixTheme.textStyles.main,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    requestFocus: Boolean = false,
) {
    val isFocused = remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val view = LocalView.current
    val editTextHolder = remember { mutableStateOf<EditText?>(null) }
    val lastRequestFocus = remember { mutableStateOf(requestFocus) }

    // 请求焦点并显示键盘
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
            // 延迟显示键盘，确保 EditText 已创建
            delay(150.milliseconds)
            editTextHolder.value?.let { editText ->
                val imm =
                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }
        } else if (lastRequestFocus.value) {
            // requestFocus 从 true 变为 false，说明弹窗正在关闭，隐藏键盘
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            focusManager.clearFocus()
        }
        lastRequestFocus.value = requestFocus
    }

    // 组件被移除时（弹窗关闭）隐藏键盘并清除焦点
    DisposableEffect(Unit) {
        onDispose {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            focusManager.clearFocus()
        }
    }

    val labelState = remember(value.text, label, useLabelAsPlaceholder) {
        when {
            label.isEmpty() -> LabelAnimState.Hidden
            useLabelAsPlaceholder && value.text.isNotEmpty() -> LabelAnimState.Placeholder
            value.text.isNotEmpty() -> LabelAnimState.Floating
            else -> LabelAnimState.Normal
        }
    }

    val themeColor = MiuixTheme.colorScheme.primary
    val backgroundColor = MiuixTheme.colorScheme.secondaryContainer
    val labelColor = MiuixTheme.colorScheme.onSurfaceVariantActions

    val borderColor by animateColorAsState(
        if (isFocused.value) themeColor else Color.Transparent
    )
    val borderWidth by animateDpAsState(
        if (isFocused.value) 2.dp else 0.dp
    )

    val labelAnim by animateDpAsState(
        when (labelState) {
            LabelAnimState.Floating -> -insideMargin.height / 2
            LabelAnimState.Placeholder, LabelAnimState.Normal -> 0.dp
            LabelAnimState.Hidden -> 0.dp
        },
    )
    val labelFontSize by animateDpAsState(
        when (labelState) {
            LabelAnimState.Floating -> 10.dp
            else -> 17.dp
        },
    )

    val textColor = textStyle.color.takeIf { it != Color.Unspecified }
        ?: MiuixTheme.colorScheme.onSurface
    val hintColor = MiuixTheme.colorScheme.onSurfaceVariantActions
    val weight = textStyle.fontWeight?.weight ?: FontWeight.Medium.weight

    // 请求焦点并显示键盘
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
            // 延迟显示键盘，确保 EditText 已创建
            delay(150.milliseconds)
            editTextHolder.value?.let { editText ->
                val imm =
                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    // 组件被移除时（弹窗关闭）隐藏键盘并清除焦点
    DisposableEffect(Unit) {
        onDispose {
            editTextHolder.value?.let { editText ->
                val imm =
                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(editText.windowToken, 0)
            }
            focusManager.clearFocus()
        }
    }

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .squircleBackground(color = backgroundColor, cornerRadius = cornerRadius)
            .squircleBorder(
                width = borderWidth,
                color = borderColor,
                cornerRadius = cornerRadius
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.invoke()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = insideMargin.width,
                        end = insideMargin.width,
                        top = insideMargin.height,
                        bottom = insideMargin.height
                    ),
                contentAlignment = Alignment.TopStart,
            ) {
                if (labelState == LabelAnimState.Floating || (labelState == LabelAnimState.Normal && !useLabelAsPlaceholder)) {
                    Text(
                        text = label,
                        fontSize = labelFontSize.value.sp,
                        color = labelColor,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.offset { IntOffset(0, labelAnim.roundToPx()) },
                        textAlign = TextAlign.Start,
                    )
                }
                Box(
                    modifier = Modifier.offset(
                        y = if (labelState == LabelAnimState.Floating) insideMargin.height / 2 else 0.dp
                    ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    AndroidView(
                        factory = { ctx ->
                            EditText(ctx).apply {
                                setText(value.text)
                                this.hint = if (useLabelAsPlaceholder) label else ""
                                this.isSingleLine = singleLine
                                this.maxLines = maxLines
                                this.minLines = minLines
                                setTextColor(textColor.toArgb())
                                setHintTextColor(hintColor.toArgb())
                                textSize = textStyle.fontSize.value
                                typeface = Typeface.create(typeface, weight, false)

                                inputType = InputType.TYPE_CLASS_TEXT
                                imeOptions = EditorInfo.IME_ACTION_DONE

                                background = null
                                setPadding(0, 0, 0, 0)

                                textCursorDrawable?.setTint(themeColor.toArgb())
                                highlightColor = themeColor.copy(alpha = 0.2f).toArgb()
                                textSelectHandle?.setTint(themeColor.toArgb())
                                textSelectHandleLeft?.setTint(themeColor.toArgb())
                                textSelectHandleRight?.setTint(themeColor.toArgb())

                                setOnFocusChangeListener { _, focused ->
                                    isFocused.value = focused
                                }

                                addTextChangedListener(object : TextWatcher {
                                    override fun beforeTextChanged(
                                        s: CharSequence?,
                                        start: Int,
                                        count: Int,
                                        after: Int
                                    ) {
                                    }

                                    override fun onTextChanged(
                                        s: CharSequence?,
                                        start: Int,
                                        before: Int,
                                        count: Int
                                    ) {
                                    }

                                    override fun afterTextChanged(s: Editable?) {
                                        s?.toString()?.let { newText ->
                                            if (newText != value.text) {
                                                onValueChange(value.copy(text = newText))
                                            }
                                        }
                                    }
                                })

                                // 保存 EditText 引用
                                editTextHolder.value = this
                            }
                        },
                        update = { editText ->
                            if (editText.text.toString() != value.text) {
                                editText.setText(value.text)
                                editText.setSelection(value.selection.start, value.selection.end)
                            }
                            editText.isEnabled = enabled
                            editText.isFocusable = !readOnly
                            // 确保高亮颜色始终生效
                            editText.highlightColor = themeColor.copy(alpha = 0.2f).toArgb()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            trailingIcon?.invoke()
        }
    }
}

private enum class LabelAnimState { Hidden, Placeholder, Normal, Floating }
