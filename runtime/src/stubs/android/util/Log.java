package android.util;

/** 仅供 runtime 编译，不打包到应用。 */
public final class Log {
    public static final int DEBUG = 3;

    public static boolean isLoggable(String tag, int level) { throw new IllegalStateException("stub"); }
    public static int d(String tag, String message) { throw new IllegalStateException("stub"); }
    public static int d(String tag, String message, Throwable error) { throw new IllegalStateException("stub"); }
}
