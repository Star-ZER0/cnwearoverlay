package cc.star0.wear.xposed.cnwearoverlay;

import android.view.MotionEvent;
import cc.star0.wear.lib.cnwearoverlay.runtime.CnWearOverlay;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** 仅由 OPPO 平台安装，独立于 Wear SDK bridge 和触觉常量 Hook。 */
final class CrownMouseHook {
    private static final String COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView";
    private static final String DISPATCH = "dispatchGenericMotionEvent";

    private CrownMouseHook() {}

    static void install(XposedInterface xp, ClassLoader appClassLoader)
            throws NoSuchMethodException {
        Class<?> composeView;
        try {
            composeView = appClassLoader.loadClass(COMPOSE_VIEW);
        } catch (ClassNotFoundException e) {
            // 未使用 Compose 或类名已被混淆；不拦截其他 View 的普通鼠标事件。
            return;
        }
        try {
            composeView.getDeclaredMethod(DISPATCH + "$cnwear$orig", MotionEvent.class);
            // 已由 Gradle 插桩时不叠加包装，避免未消费后的原事件再次转换。
            return;
        } catch (NoSuchMethodException ignored) {
            // 未插桩的应用需要运行期 Hook。
        }
        Method dispatch = composeView.getDeclaredMethod(DISPATCH, MotionEvent.class);
        if (dispatch.getReturnType() != boolean.class
                || Modifier.isStatic(dispatch.getModifiers())
                || Modifier.isAbstract(dispatch.getModifiers())
                || Modifier.isNative(dispatch.getModifiers())) {
            throw new NoSuchMethodException("Unsupported Compose input signature: " + dispatch);
        }
        xp.hook(dispatch)
                .setExceptionMode(ExceptionMode.PASSTHROUGH)
                .intercept(CrownMouseHook::intercept);
        Hooks.deoptimize(xp, dispatch);
    }

    static Object intercept(Chain chain) throws Throwable {
        MotionEvent original = (MotionEvent) chain.getArg(0);
        MotionEvent mapped = CnWearOverlay.rotaryEvent(original);
        if (mapped == original) {
            return chain.proceed();
        }
        try {
            if ((Boolean) chain.proceed(new Object[] {mapped})) {
                return true;
            }
            return chain.proceed(new Object[] {original});
        } finally {
            // 不吞分发异常，也不在异常后重试；只回收本次创建的副本。
            CnWearOverlay.recycleRotaryEvent(original, mapped);
        }
    }
}
