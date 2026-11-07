import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名配置从 local.properties 或环境变量读取(两者都不进仓库)。
// 未配置时 release 构建产出未签名 APK,方便开源使用者直接构建。
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    localProps.getProperty(propKey) ?: System.getenv(envKey)

val releaseStoreFile = signingValue("daka.storeFile", "DAKA_STORE_FILE")
    ?.let { rootProject.file(it) }
val hasReleaseSigning = releaseStoreFile?.exists() == true

// 可选:高德「Web 服务」key。留空时地点搜索使用 OpenStreetMap(Nominatim)。
val amapKey = (localProps.getProperty("daka.amapKey") ?: System.getenv("DAKA_AMAP_KEY") ?: "")
    .trim()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.family.dakacamera"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.family.dakacamera"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"

        buildConfigField("String", "AMAP_KEY", "\"$amapKey\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("daka.storePassword", "DAKA_STORE_PASSWORD")
                keyAlias = signingValue("daka.keyAlias", "DAKA_KEY_ALIAS")
                keyPassword = signingValue("daka.keyPassword", "DAKA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    // 注意:core-ktx 必须锁 1.18.0。1.19.x 要求 compileSdk 37 + AGP 9.1,会让构建直接失败
    implementation("androidx.core:core-ktx:1.18.0")
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    testImplementation("junit:junit:4.13.2")
}
