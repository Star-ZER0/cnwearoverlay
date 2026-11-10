import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseProperties = Properties().apply {
    rootProject.file("../version.properties").inputStream().use { load(it) }
}
val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull

android {
    namespace = "cc.star0.wear.xposed.cnwearoverlay"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "cc.star0.wear.xposed.cnwearoverlay"
        minSdk = 26
        targetSdk = 37
        versionCode = releaseProperties.getProperty("versionCode").toInt()
        versionName = releaseProperties.getProperty("version")
        ndk {
            // 只保留 arm64-v8a 和 armeabi-v7a 架构
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 单独生成兼容 DEX，供 ART 的 native 类查找直接使用。
val sdkComponents = androidComponents.sdkComponents
val bootClasspathFiles = sdkComponents.bootClasspath.map { classpath -> classpath.map { it.asFile } }
val d8Jar = sdkComponents.sdkDirectory.map { sdkDir ->
    sdkDir.file("build-tools/${android.buildToolsVersion}/lib/d8.jar").asFile
}
val bridgeClasses = layout.buildDirectory.dir("wear-sdk-bridge/classes")
val bridgeAssets = layout.buildDirectory.dir("generated/wear-sdk-bridge/assets")
val compileWearSdkBridge = tasks.register<JavaCompile>("compileWearSdkBridge") {
    description = "Compile CN Wear Overlay WearSDK Bridge"
    source(fileTree("../../runtime/src/main/java"))
    classpath = files(bootClasspathFiles)
    destinationDirectory.set(bridgeClasses)
    options.release.set(17)
    options.encoding = "UTF-8"
}
// 模块使用的辅助类从同一次 runtime 编译中提取；Google 桩由独立 bridge 提供。
val moduleRuntimeJar = tasks.register<Jar>("moduleRuntimeJar") {
    description = "Packages CN Wear Overlay runtime for the Xposed module"
    dependsOn(compileWearSdkBridge)
    from(bridgeClasses) {
        include("cc/star0/wear/lib/cnwearoverlay/runtime/**")
    }
    archiveFileName.set("cnwearoverlay-runtime.jar")
    destinationDirectory.set(layout.buildDirectory.dir("wear-sdk-bridge"))
}
val wearSdkBridgeJar = tasks.register<Jar>("wearSdkBridgeJar") {
    description = "Packages CN Wear Overlay WearSDK Bridge into a JAR"
    dependsOn(compileWearSdkBridge)
    from(bridgeClasses)
    archiveFileName.set("wear-sdk-bridge.jar")
    destinationDirectory.set(layout.buildDirectory.dir("wear-sdk-bridge"))
}
val dexWearSdkBridge = tasks.register<JavaExec>("dexWearSdkBridge") {
    description = "Converts  CN Wear Overlay Bridge to DEX"
    dependsOn(wearSdkBridgeJar)
    classpath = files(d8Jar)
    mainClass.set("com.android.tools.r8.D8")
    inputs.file(wearSdkBridgeJar.flatMap { it.archiveFile })
    inputs.files(bootClasspathFiles)
    outputs.dir(bridgeAssets)
    doFirst {
        val out = bridgeAssets.get().dir("cnwearoverlay").asFile
        out.mkdirs()
        args("--min-api", "29", "--lib", bootClasspathFiles.get().first().absolutePath,
            "--output", out.absolutePath, wearSdkBridgeJar.get().archiveFile.get().asFile.absolutePath)
    }
}
android.sourceSets.getByName("main").assets.directories.add(bridgeAssets.get().asFile.absolutePath)
tasks.named("preBuild") { dependsOn(dexWearSdkBridge) }

dependencies {
    implementation(files(moduleRuntimeJar))
    // libxposed API 1.0.2（= 构件 102.0.0）：compileOnly，绝不打包进 APK
    compileOnly("io.github.libxposed:api:102.0.0")
}
