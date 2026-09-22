package cc.star0.wear.xposed.cnwearoverlay;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.InMemoryDexClassLoader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 为 native ART 类查找补充缺失的 Google SDK；无需依赖 R8 后的 Compose 类名。 */
final class WearSdkBridge {
    static final String CONSTANTS = "com.google.wear.input.WearHapticFeedbackConstants";
    static final String ASSET = "assets/cnwearoverlay/classes.dex";

    private WearSdkBridge() {}

    static void install(ClassLoader appLoader, String moduleApk) throws Exception {
        try {
            Class.forName(CONSTANTS, false, appLoader);
            return;
        } catch (ClassNotFoundException missing) {
            // 仅补缺失类；真实 SDK 或应用已有实现保持优先。
        }
        if (!(appLoader instanceof BaseDexClassLoader)) {
            throw new IllegalArgumentException("Unsupported target classloader: " + appLoader);
        }
        byte[] dex = getBridgeBytes(moduleApk);
        ClassLoader bridgeLoader = new InMemoryDexClassLoader(ByteBuffer.wrap(dex), appLoader.getParent());
        Field pathList = BaseDexClassLoader.class.getDeclaredField("pathList");
        pathList.setAccessible(true);
        Object targetPathList = pathList.get(appLoader);
        Object bridgePathList = pathList.get(bridgeLoader);
        Field elements = Objects.requireNonNull(targetPathList).getClass().getDeclaredField("dexElements");
        elements.setAccessible(true);
        synchronized (appLoader) {
            Object original = elements.get(targetPathList);
            elements.set(targetPathList, appendElements(original, elements.get(bridgePathList)));
            try {
                Class.forName(CONSTANTS, false, appLoader);
            } catch (ClassNotFoundException | LinkageError error) {
                elements.set(targetPathList, original);
                throw error;
            }
        }
    }


    private static byte[] getBridgeBytes(String moduleApk) throws IOException {
        byte[] dex;
        try (ZipFile apk = new ZipFile(moduleApk)) {
            ZipEntry entry = apk.getEntry(ASSET);
            if (entry == null) throw new IOException("Missing " + ASSET);
            try (InputStream in = apk.getInputStream(entry);
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                dex = out.toByteArray();
            }
        }
        return dex;
    }

    static Object appendElements(Object original, Object extra) {
        int length = Array.getLength(original);
        int added = Array.getLength(extra);
        Object combined = Array.newInstance(Objects.requireNonNull(original.getClass().getComponentType()), length + added);
        System.arraycopy(original, 0, combined, 0, length);
        System.arraycopy(extra, 0, combined, length, added);
        return combined;
    }
}
