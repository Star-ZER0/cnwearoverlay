package android.view;

/** 仅供 runtime 编译，不打包到应用。 */
public class InputDevice {
    public static final int SOURCE_MOUSE = 0x2002;
    public static final int SOURCE_ROTARY_ENCODER = 0x400000;

    public String getName() { throw new IllegalStateException("stub"); }
}
