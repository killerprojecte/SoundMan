package hk.uwu.soundman.hook.scopes.system.hidden

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.resolver.FieldResolver

/**
 * 从 `PlayerBase.PlayerIdCard` 取出隐藏 IPlayer 的访问器。
 *
 * 动机：Hook 回调只能拿到 card 的运行时对象，不能在编译期引用 PlayerIdCard / IPlayer。
 * 字段缺失必须立即失败；字段值为 null 表示这个 player 没有 IPlayer，保持原有 warning 语义。
 * 构造期解析 `PlayerIdCard.mIPlayer`，取出非空 IPlayer 时包装成 [HiddenPlayer]。
 */
class HiddenPlayerAccess(
    playerIdCardClass: Class<*>,
) {
    private val playerIdCardClass: Class<*> = playerIdCardClass
    private val iPlayerField: FieldResolver<Any> =
        resolveDeclaredField(playerIdCardClass, FIELD_I_PLAYER)

    /**
     * 读取 card 上的 `mIPlayer` 并包装成 [HiddenPlayer]。
     *
     * @param card `android.media.PlayerBase.PlayerIdCard` 实例
     * @return 包装后的播放器；`mIPlayer == null` 时返回 null
     */
    fun fromPlayerIdCard(card: Any): HiddenPlayer? {
        val field = resolveIPlayerField(card.javaClass)
        val player = field.copy().of(card).getQuietly() ?: return null
        return HiddenPlayer(player)
    }

    private fun resolveIPlayerField(cardClass: Class<*>): FieldResolver<Any> {
        if (cardClass == playerIdCardClass) return iPlayerField
        return resolveDeclaredField(cardClass, FIELD_I_PLAYER)
    }

    private companion object {
        const val FIELD_I_PLAYER = "mIPlayer"

        fun resolveDeclaredField(clazz: Class<*>, name: String): FieldResolver<Any> {
            @Suppress("UNCHECKED_CAST")
            val resolved = (clazz as Class<Any>).resolve().optional(silent = true)
            return resolved.firstFieldOrNull { name(name) }
                ?: error("Missing field $name on ${clazz.name}")
        }
    }
}
