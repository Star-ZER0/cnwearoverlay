package cc.star0.wear.xposed.cnwearoverlay;

import android.util.Log;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import java.lang.reflect.Method;

/**
 * Hook 安装（全部在应用进程内，不触碰 system_server / 系统服务进程）：
 *   - 目标 1：hook {@code android.view.View.performHapticFeedback(int)/(int,int)}
 *       —— 仅小米设备把 CLOCK_TICK(4)/SEGMENT_FREQUENT_TICK(27) 重映射为 SEGMENT_TICK(26)；
 *       其余设备与常量直接放行。无论目标 app 是否用 Compose 均生效。
 *   - 目标 2：hook {@code androidx...rotary.HapticsKt.getCustomRotaryConstants(View)}
 *       —— 小米返回 (19,18,20)、OPPO 返回 (12,12,12) 的 HapticConstants，
 *       提前返回从而不会触碰系统缺失的 com.google.wear.input.WearHapticFeedbackConstants
 *       （原逻辑在小米 API35 上会 NoClassDefFoundError，在 OPPO API30 上恒落禁用分支）。
 */
final class Hooks {

    static final String TAG = "CnWearOverlay";

    private static final String HAPTICS_KT =
            "androidx.wear.compose.foundation.rotary.HapticsKt";

    private Hooks() {}

    static void install(XposedInterface xp, ClassLoader appClassLoader) {
        Platform platform = Platform.detect();
        if (!platform.isActive()) {
            return;
        }
        if (platform.isXiaomi()) {
            hookViewPerformHapticFeedback(xp, platform);
        }
        installMissingWearSdk(xp, appClassLoader);
        try {
            hookHapticsKt(xp, platform, appClassLoader);
        } catch (Throwable t) {
            xp.log(Log.WARN, TAG, "wear-compose rotary hook failed", t);
        }
    }

    private static void installMissingWearSdk(XposedInterface xp, ClassLoader appClassLoader) {
        try {
            WearSdkBridge.install(appClassLoader, xp.getModuleApplicationInfo().sourceDir);
        } catch (Throwable t) {
            xp.log(Log.WARN, TAG, "missing Wear SDK bridge installation failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 目标 1：CLOCK_TICK / SEGMENT_FREQUENT_TICK -> SEGMENT_TICK（仅小米）
    // ------------------------------------------------------------------

    private static void hookViewPerformHapticFeedback(XposedInterface xp, Platform platform) {
        try {
            Method m1 = View.class.getDeclaredMethod("performHapticFeedback", int.class);
            xp.hook(m1)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(
                            chain -> {
                                int fc = (Integer) chain.getArg(0);
                                int mapped = platform.remapFeedbackConstant(fc);
                                if (mapped != fc) {
                                    return chain.proceed(new Object[] {mapped});
                                }
                                return chain.proceed();
                            });
            deoptimize(xp, m1);
        } catch (Throwable t) {
            xp.log(Log.WARN, TAG, "hook View.performHapticFeedback(I) failed", t);
        }

        try {
            Method m2 =
                    View.class.getDeclaredMethod("performHapticFeedback", int.class, int.class);
            xp.hook(m2)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(
                            chain -> {
                                int fc = (Integer) chain.getArg(0);
                                int mapped = platform.remapFeedbackConstant(fc);
                                if (mapped != fc) {
                                    int flags = (Integer) chain.getArg(1);
                                    return chain.proceed(new Object[] {mapped, flags});
                                }
                                return chain.proceed();
                            });
            deoptimize(xp, m2);
        } catch (Throwable t) {
            xp.log(Log.WARN, TAG, "hook View.performHapticFeedback(II) failed", t);
        }
    }

    private static void deoptimize(XposedInterface xp, Method method) {
        try {
            xp.deoptimize(method);
        } catch (Throwable t) {
            // 去优化失败不表示已经安装的 hook 失败。
            xp.log(Log.WARN, TAG, "deoptimize failed for " + method, t);
        }
    }

    // ------------------------------------------------------------------
    // 目标 2：wear-compose 旋转触觉常量修正
    // ------------------------------------------------------------------

    private static void hookHapticsKt(
            XposedInterface xp, Platform platform, ClassLoader appClassLoader) {
        Class<?> hapticsKt;
        try {
            hapticsKt = appClassLoader.loadClass(HAPTICS_KT);
        } catch (ClassNotFoundException e) {
            // 未使用 Compose 或类已被 R8 改名时，由 View hook 和 SDK bridge 提供兼容。
            return;
        }

        boolean any = false;
        for (Method m : hapticsKt.getDeclaredMethods()) {
            if (isGetCustomRotaryConstants(m)) {
                xp.hook(m)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(
                                chain -> platform.rotaryConstants(appClassLoader));
                any = true;
            }
        }
        if (!any) {
            xp.log(
                    Log.WARN,
                    TAG,
                    "Required getCustomRotaryConstants(View): HapticConstants signature not found");
        }
    }

    private static boolean isGetCustomRotaryConstants(Method m) {
        // 仅支持 wear-compose 1.6.2 已核实的签名。
        return m.getParameterCount() == 1
                && m.getParameterTypes()[0] == View.class
                && m.getName().equals("getCustomRotaryConstants")
                && m.getReturnType()
                        .getName()
                        .equals("androidx.wear.compose.foundation.rotary.HapticConstants");
    }

}
