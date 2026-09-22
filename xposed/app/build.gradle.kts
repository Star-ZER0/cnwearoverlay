plugins {
    id("com.android.application")
}

android {
    namespace = "cc.star0.wear.xposed.cnwearoverlay"
    compileSdk = 37

    defaultConfig {
        applicationId = "cc.star0.wear.xposed.cnwearoverlay"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
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
    // libxposed API 1.0.2（= 构件 102.0.0）：compileOnly，绝不打包进 APK
    compileOnly("io.github.libxposed:api:102.0.0")
}
