/** 原生 EditText 包装，使用系统原生长按菜单样式 */
package hk.uwu.soundman.miuix.basic

import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun NativeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    textStyle: TextStyle = TextStyle.Default,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    maxLength: Int = Int.MAX_VALUE,
    textAlign: TextAlign = TextAlign.Start,
    enabled: Boolean = true
) {
    val textColor = textStyle.color.takeIf { it != Color.Unspecified }
        ?: MiuixTheme.colorScheme.onSurface
    val hintColor = MiuixTheme.colorScheme.onSurfaceVariantActions
    val themeColor = MiuixTheme.colorScheme.primary

    val textWatcher = remember(onValueChange) {
        object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                s?.toString()?.let { onValueChange(it) }
            }
        }
    }

    AndroidView(
        factory = { context ->
            EditText(context).apply {
                setText(value)
                this.hint = hint
                this.isSingleLine = singleLine
                this.maxLines = maxLines
                setTextColor(textColor.toArgb())
                setHintTextColor(hintColor.toArgb())
                textSize = textStyle.fontSize.value

                // 设置字体粗细，默认 Medium
                val weight = textStyle.fontWeight?.weight ?: FontWeight.Medium.weight
                typeface = Typeface.create(typeface, weight, false)

                // 设置输入类型
                inputType = InputType.TYPE_CLASS_TEXT

                // 设置文本对齐
                textAlignment = when (textAlign) {
                    TextAlign.Center -> EditText.TEXT_ALIGNMENT_CENTER
                    TextAlign.End, TextAlign.Right -> EditText.TEXT_ALIGNMENT_VIEW_END
                    else -> EditText.TEXT_ALIGNMENT_VIEW_START
                }

                // 设置最大长度
                if (maxLength < Int.MAX_VALUE) {
                    filters = arrayOf(InputFilter.LengthFilter(maxLength))
                }

                // 设置 imeOptions
                imeOptions = EditorInfo.IME_ACTION_DONE

                // 设置背景为透明，去掉默认内边距
                background = null
                setPadding(0, 0, 0, 0)

                // 设置光标颜色 (API 29+)
                textCursorDrawable?.setTint(themeColor.toArgb())

                // 设置选中文本的背景高亮颜色
                highlightColor = themeColor.copy(alpha = 0.2f).toArgb()

                // 设置长按选择手柄（水滴形）颜色 (API 29+)
                textSelectHandle?.setTint(themeColor.toArgb())
                textSelectHandleLeft?.setTint(themeColor.toArgb())
                textSelectHandleRight?.setTint(themeColor.toArgb())

                // 添加 TextWatcher
                addTextChangedListener(textWatcher)
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
            // 确保高亮颜色始终生效
            editText.highlightColor = themeColor.copy(alpha = 0.2f).toArgb()
        },
        modifier = modifier
    )
}
