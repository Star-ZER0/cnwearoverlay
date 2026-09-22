package cc.star0.wear.xposed.cnwearoverlay;

import android.util.Log;
import io.github.libxposed.api.XposedModule;

/**
 * libxposed API 1.0.2（102.0.0）入口。
 * 规范：无参构造 + 框架自动调用 attachFramework()；初始化放在生命周期回调内。
 * 清单：META-INF/xposed/java_init.list + module.prop。
 * 约束：不 hook 系统框架进程（system_server/services），仅在应用进程内生效，
 * 因此兼容 LSPatch（JingMatrix）这类把 Xposed 核心（Vector）内嵌进目标 APK 的免 root 方案。
 */
public final class CnWearOverlayModule extends XposedModule {

    @Override
    public void onPackageReady(PackageReadyParam param) {
        super.onPackageReady(param);
        if (!param.isFirstPackage()) {
            return;
        }
        String pkg = param.getPackageName();
        try {
            Hooks.install(this, param.getClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, Hooks.TAG, "failed to install hooks for " + pkg, t);
        }
    }
}
