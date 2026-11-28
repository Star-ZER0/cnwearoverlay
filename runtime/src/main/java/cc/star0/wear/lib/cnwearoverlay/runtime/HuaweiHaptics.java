package cc.star0.wear.lib.cnwearoverlay.runtime;

import android.view.View;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/** 华为组件：通过系统 VibratorEx 播放表冠命名波形，不回退到 AOSP 常量。 */
final class HuaweiHaptics extends BaseOverlay {
    private static final String VIBRATOR_EX_CLASS = "com.huawei.android.os.VibratorEx";
    private static final int SCROLL_ITEM_FOCUS = 19;
    private static final int SCROLL_TICK = 18;
    private static final int SCROLL_LIMIT = 20;
    private static final String EFFECT_CROWN_TICK = "watchhaptic.crown.strength1";
    private static final String EFFECT_CROWN_FOCUS = "watchhaptic.crown.strength2";
    private static final String EFFECT_CROWN_LIMIT = "watchhaptic.crown.strength3";

    private static final ConcurrentHashMap<String, Boolean> SUPPORTED_EFFECTS =
            new ConcurrentHashMap<>();

    HuaweiHaptics() {
        super(SCROLL_ITEM_FOCUS, SCROLL_TICK, SCROLL_LIMIT);
    }

    static boolean isAvailable() {
        return hasSystemClass(VIBRATOR_EX_CLASS);
    }

    static boolean isRotaryConstant(int feedbackConstant) {
        return feedbackConstant == SCROLL_ITEM_FOCUS
                || feedbackConstant == SCROLL_TICK
                || feedbackConstant == SCROLL_LIMIT;
    }

    @Override
    boolean performRotaryHapticFeedback(View view, int feedbackConstant) {
        return isRotaryConstant(feedbackConstant)
                ? perform(view, feedbackConstant, 0)
                : super.performRotaryHapticFeedback(view, feedbackConstant);
    }

    @Override
    boolean performRotaryHapticFeedback(View view, int feedbackConstant, int flags) {
        return isRotaryConstant(feedbackConstant)
                ? perform(view, feedbackConstant, flags)
                : super.performRotaryHapticFeedback(view, feedbackConstant, flags);
    }

    static boolean perform(View view, int feedbackConstant, int flags) {
        if (!isRotaryConstant(feedbackConstant)) {
            return false;
        }
        if (OverlayLog.enabled()) {
            OverlayLog.debug("haptic huawei request constant=" + feedbackConstant
                    + " flags=" + flags);
        }
        if (!canPerformDirectHaptic(view, flags, "haptic huawei")) {
            return false;
        }
        String stage = "resolve_api";
        try {
            Api api = Api.INSTANCE;
            if (api == null) {
                OverlayLog.debug("haptic huawei skipped: reflection API unavailable (cached)");
                return false;
            }
            stage = "new_instance";
            Object vibrator = api.constructor.newInstance();
            stage = "check_support";
            String effect = resolveEffect(api, vibrator, feedbackConstant);
            if (effect == null) {
                OverlayLog.debug("haptic huawei skipped: crown effect unsupported");
                return false;
            }
            stage = "vibrate";
            api.setHwVibrator.invoke(vibrator, effect);
            if (OverlayLog.enabled()) {
                OverlayLog.debug("haptic huawei invoked effect=" + effect);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            OverlayLog.failure("haptic huawei failed stage=" + stage, e);
            // 隐藏 API、VIBRATE 权限或服务不可用时直接结束；不回退到 VIRTUAL_KEY(1)。
            return false;
        }
    }

    private static String resolveEffect(Api api, Object vibrator, int feedbackConstant)
            throws ReflectiveOperationException {
        String effect = switch (feedbackConstant) {
            case SCROLL_TICK -> EFFECT_CROWN_TICK;
            case SCROLL_ITEM_FOCUS -> EFFECT_CROWN_FOCUS;
            case SCROLL_LIMIT -> EFFECT_CROWN_LIMIT;
            default -> null;
        };
        return effect != null && isSupported(api, vibrator, effect) ? effect : null;
    }

    private static boolean isSupported(Api api, Object vibrator, String effect)
            throws ReflectiveOperationException {
        Boolean cached = SUPPORTED_EFFECTS.get(effect);
        if (cached != null) {
            return cached;
        }
        boolean supported = Boolean.TRUE.equals(api.isSupportHwVibrator.invoke(vibrator, effect));
        SUPPORTED_EFFECTS.putIfAbsent(effect, supported);
        if (OverlayLog.enabled()) {
            OverlayLog.debug("haptic huawei support effect=" + effect + " supported=" + supported);
        }
        return supported;
    }

    /** 只缓存 boot classpath 反射入口，不持有 View、Context 或 VibratorEx 实例。 */
    private static final class Api {
        static final Api INSTANCE = create();

        final Constructor<?> constructor;
        final Method isSupportHwVibrator;
        final Method setHwVibrator;

        private Api() throws ReflectiveOperationException {
            ClassLoader boot = View.class.getClassLoader();
            Class<?> vibratorEx = Class.forName(VIBRATOR_EX_CLASS, false, boot);
            constructor = vibratorEx.getConstructor();
            isSupportHwVibrator = vibratorEx.getMethod("isSupportHwVibrator", String.class);
            setHwVibrator = vibratorEx.getMethod("setHwVibrator", String.class);
        }

        private static Api create() {
            try {
                Api api = new Api();
                OverlayLog.debug("haptic huawei reflection API ready");
                return api;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                OverlayLog.failure("haptic huawei reflection API init failed", e);
                return null;
            }
        }
    }
}
