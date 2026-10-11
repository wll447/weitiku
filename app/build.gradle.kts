import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.weizh.tiku"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.weizh.tiku"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3.0"
        ndk {
            // 只保留手机(arm64)与模拟器(x86_64)，避免 ONNX Runtime 四套 so 撑大包体
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
    // 签名配置从 keystore.properties 读（该文件已 gitignore，不进仓库）
    signingConfigs {
        create("release") {
            val kp = rootProject.file("keystore.properties")
            if (kp.exists()) {
                val props = Properties().apply { kp.inputStream().use { load(it) } }
                storeFile = rootProject.file(props.getProperty("storeFile") ?: "weitiku.jks")
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }
    androidResources {
        // onnx 模型不压缩，便于按文件读取
        noCompress += listOf("onnx", "txt")
    }
}

// 输出文件名改成 魏题库-版本-构建类型.apk（AGP 默认是 {模块名}-{buildType}.apk，即 app-debug.apk）
androidComponents {
    onVariants(selector().all()) { variant ->
        val buildTypeName = variant.buildType ?: "release"
        variant.outputs.forEach { output ->
            // outputFileName / versionName 只在 impl 上暴露，公开接口没有
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(
                "WeiTiku-${output.versionName.get()}-$buildTypeName.apk"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 端侧 OCR：ONNX Runtime + PP-OCRv4
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")

    // 老版 .xls（OLE2/BIFF8）读取：jxl 纯 Java、无 AWT 依赖
    implementation("net.sourceforge.jexcelapi:jxl:2.6.12")

    // 相机（扫描搜题）
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
}
