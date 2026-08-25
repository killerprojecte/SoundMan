package hk.uwu.soundman.hook

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import hk.uwu.soundman.BuildConfig
import hk.uwu.soundman.hook.scopes.system.PreferredDeviceHooker
import hk.uwu.soundman.hook.scopes.system.SystemAudioHooker
import hk.uwu.soundman.hook.scopes.systemui.SystemUiVolumeEntryHooker

/**
 * SoundMan 的官方 YukiHookAPI 入口。
 *
 * 动机：用 [YukiHookAPI.encase] 分别装载 Zygote / system_server / SystemUI，
 * 不再经过自研 libxposed 102 运行时。
 */
@InjectYukiHookWithXposed(
    entryClassName = "HookEntrance",
    isUsingXposedModuleStatus = true,
)
class HookEntry : IYukiHookXposedInit {
    override fun onInit() = configs {
        debugLog { tag = "SoundMan" }
        isDebug = BuildConfig.DEBUG
    }

    override fun onHook() = YukiHookAPI.encase {
        loadZygote { loadHooker(PreferredDeviceHooker) }
        loadSystem { loadHooker(SystemAudioHooker) }
        loadApp(name = "com.android.systemui") { loadHooker(SystemUiVolumeEntryHooker) }
    }
}
