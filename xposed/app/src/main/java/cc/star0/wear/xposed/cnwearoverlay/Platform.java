package cc.star0.wear.xposed.cnwearoverlay;

/**
 * 目标平台识别与触觉常量（与 Gradle 插件注入的运行时保持同一套语义）。
 * 判定依据：
 *   - 只探测 boot classpath 的小米 framework / SDK 类或 OPPO 线性马达类，不依赖厂商名称。
 *   - 小米手表：wear-compose 走 Wear4 常量 (19,18,20) 即 SCROLL_ITEM_FOCUS/SCROLL_TICK/
 *       SCROLL_LIMIT —— 小米注入将 18/20 映射为 210 表冠旋转、19 映射为 211 键盘反馈；
 *       CLOCK_TICK(4)/SEGMENT_FREQUENT_TICK(27) 被系统映射到 TEXTURE_TICK(21)，
 *       为修复这些常量的震感异常，重映射为 SEGMENT_TICK(26)（EFFECT_TICK -> 210）。
 *   - OPPO 手表：framework 删除了 SCROLL_*，官方表冠触觉统一用 GESTURE_START(12)
 *       （WearPhoneWindowManager 映射为线性马达波形 302，且绕过睡眠/勿扰门禁）。
 *   - 真 Wear OS：boot classpath 存在 com.google.wear.input.WearHapticFeedbackConstants，
 *       一律不介入（含 OPPO/OnePlus 的 Wear OS 版手表）。
 */
final class Platform {

    // android.view.HapticFeedbackConstants（公开 SDK 与设备端编号一致，已实证）
    public static final int CLOCK_TICK = 4;
    public static final int SEGMENT_FREQUENT_TICK = 27;
    public static final int SEGMENT_TICK = 26;

    private static final int XIAOMI_FOCUS = 19;
    private static final int XIAOMI_TICK = 18;
    private static final int XIAOMI_LIMIT = 20;
    private static final int OPPO_FOCUS = 12;
    private static final int OPPO_TICK = 12;
    private static final int OPPO_LIMIT = 12;

    private static final String GOOGLE_WEAR_CONSTANTS =
            "com.google.wear.input.WearHapticFeedbackConstants";
    private static final String XIAOMI_WEAR_CONSTANTS =
            "com.xiaomi.miwear.input.WearHapticFeedbackConstants";
    // framework.jar 中的稳定标记，不依赖可选 wear-sdk.jar 的加载方式。
    private static final String XIAOMI_FRAMEWORK = "miwear.os.VibrationEffectId";
    // OPPO framework.jar 中的震动服务客户端，WearPhoneWindowManager 通过它播放波形 302。
    private static final String OPPO_VIBRATOR =
            "android.os.linearmotorvibrator.LinearmotorVibrator";

    static final int STATE_INACTIVE = 0;
    static final int STATE_XIAOMI = 1;
    static final int STATE_OPPO = 2;

    private final int state;
    private Platform(int state) {
        this.state = state;
    }

    static Platform detect() {
        boolean google = hasSystemClass(GOOGLE_WEAR_CONSTANTS);
        boolean xiaomiFramework = hasSystemClass(XIAOMI_FRAMEWORK);
        boolean xiaomiSdk = hasSystemClass(XIAOMI_WEAR_CONSTANTS);
        boolean oppo = hasSystemClass(OPPO_VIBRATOR);
        // 真 Wear OS（三星、TicWatch、OPPO Watch Wear OS 版等）不介入
        if (google) {
            return new Platform(STATE_INACTIVE);
        }
        if (xiaomiFramework || xiaomiSdk) {
            return new Platform(STATE_XIAOMI);
        }
        if (oppo) {
            return new Platform(STATE_OPPO);
        }
        return new Platform(STATE_INACTIVE);
    }

    /** 只探测系统类且不初始化，避免命中目标 app / 模块内的 Google 桩类或厂商 SDK。 */
    private static boolean hasSystemClass(String name) {
        try {
            ClassLoader boot = Platform.class.getClassLoader();
            while (boot != null && boot.getParent() != null) {
                boot = boot.getParent();
            }
            Class.forName(name, false, boot);
            return true;
        } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
            return false;
        }
    }

    boolean isActive() {
        return state != STATE_INACTIVE;
    }

    boolean isXiaomi() {
        return state == STATE_XIAOMI;
    }

    boolean isOppo() {
        return state == STATE_OPPO;
    }

    /** 目标 2：仅小米上把 4/27 重映射为 26；其余原样透传。 */
    int remapFeedbackConstant(int feedbackConstant) {
        if (isXiaomi()
                && (feedbackConstant == CLOCK_TICK
                        || feedbackConstant == SEGMENT_FREQUENT_TICK)) {
            return SEGMENT_TICK;
        }
        return feedbackConstant;
    }

    /**
     * 目标 1：构造 androidx.wear.compose.foundation.rotary.HapticConstants 实例。
     *
     * @return 当前 wear-compose 结构的平台常量实例
     */
    Object rotaryConstants(ClassLoader appClassLoader) throws ReflectiveOperationException {
        Class<?> hc =
                appClassLoader.loadClass(
                        "androidx.wear.compose.foundation.rotary.HapticConstants");
        int focus = isOppo() ? OPPO_FOCUS : XIAOMI_FOCUS;
        int tick = isOppo() ? OPPO_TICK : XIAOMI_TICK;
        int limit = isOppo() ? OPPO_LIMIT : XIAOMI_LIMIT;
        return newHapticConstants(hc, focus, tick, limit);
    }

    private static Object newHapticConstants(Class<?> hc, int focus, int tick, int limit)
            throws ReflectiveOperationException {
        // 1.6.2 的基类是 abstract；创建具体子类的新实例，不修改 Kotlin 全局单例。
        Class<?> concrete = Class.forName(
                hc.getName() + "$Wear4RotaryHapticConstants", true, hc.getClassLoader());
        java.lang.reflect.Constructor<?> constructor = concrete.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object constants = constructor.newInstance();
        setConstant(hc, constants, "scrollFocus", focus);
        setConstant(hc, constants, "scrollTick", tick);
        setConstant(hc, constants, "scrollLimit", limit);
        return constants;
    }

    private static void setConstant(Class<?> hc, Object instance, String name, int value)
            throws ReflectiveOperationException {
        java.lang.reflect.Field field = hc.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }
}
