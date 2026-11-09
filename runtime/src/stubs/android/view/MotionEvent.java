package android.view;

/** 仅供 runtime 编译，不打包到应用。 */
public class MotionEvent {
    public static final int ACTION_SCROLL = 8;
    public static final int AXIS_VSCROLL = 9;
    public static final int AXIS_SCROLL = 26;

    public static class PointerProperties {}
    public static class PointerCoords {
        public void setAxisValue(int axis, float value) { throw new IllegalStateException("stub"); }
    }

    public static MotionEvent obtain(long downTime, long eventTime, int action, int pointerCount,
            PointerProperties[] properties, PointerCoords[] coords, int metaState, int buttonState,
            float xPrecision, float yPrecision, int deviceId, int edgeFlags, int source, int flags) {
        throw new IllegalStateException("stub");
    }

    public int getActionMasked() { throw new IllegalStateException("stub"); }
    public int getAction() { throw new IllegalStateException("stub"); }
    public int getSource() { throw new IllegalStateException("stub"); }
    public boolean isFromSource(int source) { throw new IllegalStateException("stub"); }
    public int getPointerCount() { throw new IllegalStateException("stub"); }
    public InputDevice getDevice() { throw new IllegalStateException("stub"); }
    public int getDeviceId() { throw new IllegalStateException("stub"); }
    public float getAxisValue(int axis) { throw new IllegalStateException("stub"); }
    public void getPointerProperties(int index, PointerProperties out) { throw new IllegalStateException("stub"); }
    public void getPointerCoords(int index, PointerCoords out) { throw new IllegalStateException("stub"); }
    public long getDownTime() { throw new IllegalStateException("stub"); }
    public long getEventTime() { throw new IllegalStateException("stub"); }
    public int getMetaState() { throw new IllegalStateException("stub"); }
    public int getButtonState() { throw new IllegalStateException("stub"); }
    public float getXPrecision() { throw new IllegalStateException("stub"); }
    public float getYPrecision() { throw new IllegalStateException("stub"); }
    public int getEdgeFlags() { throw new IllegalStateException("stub"); }
    public int getFlags() { throw new IllegalStateException("stub"); }
    public void recycle() { throw new IllegalStateException("stub"); }
}
