import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------- 签名（v2.0.0）
// 从这个版本起**只出 release 签名的包**（用户决定）。
//
// 口令放在 android/keystore.properties，keystore 放在 android/keystore/ ——
// 都不写在源码里。分享源码前把这两个删掉；keystore 丢了就永远无法覆盖升级。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasSigning = keystorePropsFile.exists()

android {
    namespace = "com.airgesture.sweep"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.airgesture.sweep"
        minSdk = 29          // 需求：Android 10 及以上
        targetSdk = 36
        // v0.17.x 是功耗优化分支：跳帧 + 桌面/息屏关相机 + 接近光触发。
        // 冻结基线仍是 release/airgesture-v0.16.1-release.apk（versionCode 23）。
        // v1.0.2：接近光窗口 20 秒 → 15 秒（2026-09-24 用户要求）。
        // v2.0.0：正式发布版 —— 中文名「云枢」/ Air Gesture Hub，界面按用户定稿重排。
        // v2.0.1：界面上显示版本号（从 PackageManager 读，不写死）。
        // v2.2.0：接近光窗口 15 秒 → 8 秒（2026-09-25 用户要求，
        //         依据 7h20m 日常工况实测：相机 21m15s / 66 次会话 / 占空比 4.84%）。
        // v2.3.0：横屏支持 —— 方向映射按屏幕旋转推导（ScreenOrientation）、
        //         同步 CameraX targetRotation、横屏只保留上下（左右扫不注入）。
        //         2026-09-26 真机验收通过（竖屏 + 两个横屏方向全对），见
        //         docs/verify-v2.3.0/report.md。
        // v2.3.1：冷静期 1200ms → 1100ms（用户要求）；其余不变。
        // v2.3.2：冷静期 1100ms → 1000ms（用户要求）；参数迁移改为**按值对齐**
        //         （见 Prefs.migrateOnce：以后改定稿值不需要再加 migrated_vN 键）。
        versionCode = 49
        versionName = "2.3.2"

        // 目标机型是骁龙 888 ~ 8 Gen 3，全是 arm64。
        // 不限制的话 MediaPipe 会把 4 个 ABI 的原生库都打进去，APK 从 ~20MB 涨到 66MB。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasSigning) {
            create("yunshu") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 没有 keystore.properties 时保持未签名（build.ps1 -Release 会明确报错）
            if (hasSigning) signingConfig = signingConfigs.getByName("yunshu")
            // 默认 false（正式包不可调试）。
            // 加 -Pdebuggable 出一个"**同一个 keystore** 但可调试"的测试包：
            //   同签名 → 两个包能互相覆盖安装、prefs 不丢；
            //   可调试 → `run-as` 能读 prefs（排查方向/白名单问题主要靠它）。
            // ⚠️ 对外发布的那一份**必须**是不带 -Pdebuggable 的。
            isDebuggable = project.hasProperty("debuggable")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
    }

    // 核心判定逻辑（SweepDetector）刻意不依赖任何 Android API，
    // 所以单元测试跑在纯 JVM 上即可，与 Windows 版"状态机脱离硬件可测"是同一个思路。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.lifecycle:lifecycle-common:2.11.0")
    implementation("androidx.annotation:annotation:1.9.1")

    // 与 Windows 版同一个手部模型（hand_landmarker.task 直接复用）
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    testImplementation("junit:junit:4.13.2")
}









