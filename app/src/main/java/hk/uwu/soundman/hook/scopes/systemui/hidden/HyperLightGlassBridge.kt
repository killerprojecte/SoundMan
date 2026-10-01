package hk.uwu.soundman.hook.scopes.systemui.hidden

import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import java.lang.reflect.Method

/**
 * 复用 HyperLight 的液态玻璃渲染器，让入口圆钮用上和它同一套实现与参数。
 *
 * 为什么不能自己 `new PathClassLoader` 去加载它的 APK：那样拿到的是**冷**的一份，
 * 静态配置没初始化，读出来的参数全是默认值（实测 `isHookProcess=false`、
 * 模糊度/饱和度全是我传入的兜底值）。它自己的类只活在 LSPosed 给模块创建的
 * `LspModuleClassLoader` 里，那份才是热的——`isHookProcess=true`，能读到真实设置。
 *
 * 那份 ClassLoader 拿不到枚举入口，只能等它自己往 View 上挂玻璃时从 Drawable 反查，
 * 所以调用方需要把 `View.setBackground` 的调用转给 [noteBackground]。
 *
 * 渲染走它的 `zf0.l(view, radius, flag, refreshLevel, scene, false, false, null, -1)`：
 * 这一个静态方法会自己取背景源、构造渲染器、注册到它的 WeakHashMap、
 * `setBackground` 并恢复 padding，view 还没布局时还会挂 `OnPreDrawListener` 延迟执行。
 * 比手动 `zf0.w(view)` + `new of0(...)` 稳妥得多。
 *
 * 全部反射且逐处 try/catch：HyperLight 升级后混淆类名一变，这里必须安静地失败，
 * 让调用方降级到自研玻璃，绝不能把 SystemUI 带崩。
 */
class HyperLightGlassBridge(
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
) {
    @Volatile
    private var loader: ClassLoader? = null

    /** 诊断采样上限，避免刷屏。 */
    @Volatile
    private var watched = 0

    /**
     * 记录一次 `View.setBackground`，抓 HyperLight 的模块 ClassLoader。
     *
     * 判断依据是 ClassLoader 的 `toString()` 里含它的包名——LSPosed 的
     * `LspModuleClassLoader` 会把模块 APK 路径打进去，这比猜类名稳定。
     */
    fun noteBackground(drawable: Drawable) {
        val candidate = drawable.javaClass.classLoader ?: return
        if (PKG !in candidate.toString()) return
        // 顺手记下它自己挂的这颗玻璃用的参数，用来核对我们给入口传的是不是同一套。
        if (watched < MAX_WATCH) {
            watched++
            log(Log.INFO, TAG, "observed[${watched}] ${describe(drawable)}", null)
        }
        if (loader != null) return
        loader = candidate
        log(Log.INFO, TAG, "HyperLight glass renderer captured", null)
    }

    /**
     * 读一颗已挂载玻璃的关键参数：类名、它挂在哪个 View 上、场景名。
     *
     * 目标 View 藏在 WeakReference 字段里；场景名是唯一的 String 字段。
     * 全部反射且容错——这纯粹是诊断，绝不能因为它把 SystemUI 带崩。
     */
    private fun describe(drawable: Drawable): String {
        val type = drawable.javaClass
        val parts = mutableListOf(type.name)
        runCatching {
            type.declaredFields.firstOrNull { it.type == java.lang.ref.WeakReference::class.java }
                ?.apply { isAccessible = true }
                ?.get(drawable)
                ?.let { (it as? java.lang.ref.WeakReference<*>)?.get() as? View }
                ?.let { view -> parts += "on=${view.javaClass.name}" }
        }
        runCatching {
            type.declaredFields.filter { it.type == String::class.java }.forEach { field ->
                field.isAccessible = true
                (field.get(drawable) as? String)?.let { parts += "scene=$it" }
            }
        }
        return parts.joinToString(" ")
    }

    /** 是否已拿到可用的 HyperLight 渲染器。 */
    fun available(): Boolean = loader != null

    /**
     * HyperLight 自己的液态玻璃总开关。
     *
     * 注意键名是 `enable_real_liquid_glass`（默认关闭），不是 `liquid_glass`——
     * 后者在它的默认值表里根本不存在，读出来永远是传入的兜底值。
     */
    fun liquidGlassEnabled(): Boolean = readBoolean(KEY_ENABLE, false)

    /**
     * 给入口挂上 HyperLight 的液态玻璃。
     *
     * 必须**先**给 view 设好底层背景：它的渲染器会把 `view.getBackground()`
     * 当作 base 叠在自己下面，所以调用顺序错了底图就没了。
     *
     * @param view 入口 chrome
     * @param radiusPx 圆角半径（像素）
     * @return 挂载成功；失败时调用方应降级到自研玻璃
     */
    fun attach(view: View, radiusPx: Float): Boolean {
        val current = loader ?: return false
        return try {
            val factory = Class.forName(CLS_FACTORY, false, current)
            val method = resolveApply(factory) ?: run {
                log(Log.INFO, TAG, "HyperLight apply entry missing; keep own glass", null)
                return false
            }
            method.invoke(
                null,
                view,
                radiusPx,
                false,
                SIZE_TIER,
                SCENE,
                false,
                false,
                null,
                -1,
            )
            true
        } catch (error: Throwable) {
            log(
                Log.WARN,
                TAG,
                "HyperLight glass attach failed: ${error.javaClass.simpleName}: ${error.message}",
                error,
            )
            false
        }
    }

    /**
     * 给自建面板挂上和**系统音量条展开面板**完全同一条链路的液态玻璃。
     *
     * 官方路径（反编译实锤）：HyperLight hook 住
     * `MiuiVolumeDialogMotion.updateExpandBgState`，从它的 `mExpandBgView` 字段取出
     * 展开面板容器（`RoundRectFrameLayout`），调 `ht.a(view, 0f, "components_no_shadow", 1)`，
     * 内部再依次 `ag0.n(view, radius, scene, flag, -1)` → `zf0.m(...)` → `zf0.l(...)`，
     * 最后 post 一个挂载 job。所以直接调 `ht.a` 就等于让面板走官方展开面板的同一入口：
     * 场景名、flag、后续 job 全部与系统一致，不用我们自己拼 `zf0.l` 的参数。
     *
     * 与 [attach]（入口圆钮用）的区别就在这里：入口没有官方可挂钩子的宿主方法，
     * 只能自己拼 `zf0.l`；而展开面板有官方对应的 hook 入口函数 `ht.a`。
     *
     * @param view 面板容器，调用前应已套好官方展开材质（玻璃拿不到时它作兜底）
     * @param radiusPx 圆角半径（像素），取官方 `MiuiVolumeDialogRes.getBgRadius`
     * @return 挂载成功；失败时调用方应保留官方材质或降级到自研玻璃
     */
    fun attachExpandedPanel(view: View, radiusPx: Float): Boolean {
        val current = loader ?: return false
        return try {
            val hook = Class.forName(CLS_PANEL_HOOK, false, current)
            val method = hook.declaredMethods.firstOrNull { candidate ->
                candidate.name == METHOD_PANEL_APPLY &&
                    candidate.parameterTypes.size == PANEL_APPLY_ARGC &&
                    candidate.parameterTypes[0] == View::class.java &&
                    candidate.parameterTypes[1] == java.lang.Float.TYPE &&
                    candidate.parameterTypes[2] == String::class.java &&
                    candidate.parameterTypes[3] == java.lang.Integer.TYPE
            } ?: run {
                log(Log.INFO, TAG, "HyperLight expanded panel entry missing; keep official material", null)
                return false
            }
            method.isAccessible = true
            method.invoke(null, view, radiusPx, SCENE_PANEL, PANEL_FLAG)
            true
        } catch (error: Throwable) {
            log(
                Log.WARN,
                TAG,
                "HyperLight expanded panel glass failed: ${error.javaClass.simpleName}: ${error.message}",
                error,
            )
            false
        }
    }

    private fun resolveApply(factory: Class<*>): Method? {
        val method = factory.declaredMethods.firstOrNull { candidate ->
            candidate.name == METHOD_APPLY && candidate.parameterTypes.size == APPLY_ARGC
        } ?: return null
        method.isAccessible = true
        return method
    }

    private fun prefsBridge(): Class<*>? {
        val current = loader ?: return null
        return runCatching { Class.forName(CLS_PREFS_BRIDGE, false, current) }.getOrNull()
    }

    /**
     * 通过 HyperLight 自己的设置桥接读布尔值。
     *
     * 只有热 ClassLoader 才读得到真实值，冷的那份会原样返回 [fallback]。
     */
    private fun readBoolean(key: String, fallback: Boolean): Boolean {
        val bridge = prefsBridge() ?: return fallback
        return runCatching {
            val method = bridge.declaredMethods.firstOrNull { candidate ->
                candidate.name == "getBoolean" && candidate.parameterTypes.size == 2
            } ?: return fallback
            method.isAccessible = true
            method.invoke(null, key, fallback) as? Boolean ?: fallback
        }.getOrDefault(fallback)
    }

    private companion object {
        const val TAG = "HyperLightGlass"
        const val PKG = "com.kiminonawa.HyperLight"
        const val CLS_FACTORY = "zf0"
        const val CLS_PREFS_BRIDGE =
            "com.kiminonawa.HyperLight.utils.datamanagement.PrefsBridge"
        const val METHOD_APPLY = "l"
        const val APPLY_ARGC = 9

        /**
         * 液态玻璃场景名。
         *
         * 取 `components`：用户实测确认音量侧栏的静音/免打扰按钮（铃铛/月亮）就是
         * HyperLight 的液态玻璃，它们和入口同在音量条上，同场景才可能同观感。
         * 第一版传这个却画成纯色，根因其实是尺寸档位传错（拿刷新档位当档位传了），
         * 不是场景名——见 [SIZE_TIER]。
         */
        const val SCENE = "components"

        /**
         * 尺寸档位（对应它的 `liquid_glass_optical_padding_small/medium/...`）。
         *
         * 入口是小控件，取 small=0。⚠️ 别拿 `liquid_glass_non_desktop_refresh_level`
         * 当这个参数传——第一版就这么干的，渲染器把它算进颜色，直接画成纯色块。
         * 刷新档位渲染器内部自己会读，不需要我们传。
         */
        const val SIZE_TIER = 0

        /** 展开面板的挂载入口类 `ht`（`ht.a(View, F, String, I)`）。 */
        const val CLS_PANEL_HOOK = "ht"
        const val METHOD_PANEL_APPLY = "a"
        const val PANEL_APPLY_ARGC = 4

        /**
         * 系统展开面板用的场景名：`components_no_shadow`。
         *
         * 实锤来自它自己挂的那颗玻璃——`of0 on=...RoundRectFrameLayout
         * scene=components_no_shadow`，与 `ht.a` 调用点里的常量一致。
         * 它只认 `nc_ / cc_ / components / components_no_shadow` 这几组混色，
         * 传别的会画成纯色块（第一版就踩过）。
         */
        const val SCENE_PANEL = "components_no_shadow"

        /** `ht.a` 的第 4 个 int：官方展开面板传 1（入口圆钮那一路传的是 0）。 */
        const val PANEL_FLAG = 1

        const val KEY_ENABLE = "enable_real_liquid_glass"
        const val MAX_WATCH = 12
    }
}
