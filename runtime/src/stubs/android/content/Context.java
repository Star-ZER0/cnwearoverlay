package android.content;

/** 仅供编译 runtime，不随 JAR 分发。 */
public abstract class Context {
    public abstract Object getSystemService(String name);

    public abstract ContentResolver getContentResolver();
}
