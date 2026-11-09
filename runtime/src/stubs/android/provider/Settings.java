package android.provider;

import android.content.ContentResolver;

/** 仅供编译 runtime，不随 JAR 分发。 */
public final class Settings {
    public static final class System {
        public static int getInt(ContentResolver resolver, String name, int defaultValue) {
            throw new IllegalStateException("stub");
        }
    }
}
