plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 版本号默认给本地调试构建用；发布构建由 CI 按 git tag 通过 -P 覆盖，例如：
// ./gradlew :app:assembleRelease -PbilimuseVersionName=0.2.1 -PbilimuseVersionCode=201
val appVersionName = providers.gradleProperty("bilimuseVersionName").getOrElse("0.2.1")
val appVersionCodeRaw = providers.gradleProperty("bilimuseVersionCode").getOrElse("1")
val appVersionCode = appVersionCodeRaw.toIntOrNull()
    ?: error("bilimuseVersionCode 必须是整数（当前：$appVersionCodeRaw）")
val updateRepo = providers.gradleProperty("bilimuseUpdateRepo")
    .getOrElse("Jackiechen259/bilimuse")

// 正式发布签名通过环境变量注入（CI 从 Secrets 解码出临时 keystore）。
// 未配置时回退到仓库内的公开调试密钥，保证 sideload 和自动更新链路开箱可用；
// 但公开密钥意味着任何人都能伪造同包名 APK，正式分发请配置这四个环境变量。
val releaseKeystore = System.getenv("BILIMUSE_ANDROID_KEYSTORE")
val releaseStorePassword = System.getenv("BILIMUSE_ANDROID_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("BILIMUSE_ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("BILIMUSE_ANDROID_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeystore,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.bilimuse.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bilimuse.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/bilimuse-debug.jks")
            storePassword = "android"
            keyAlias = "bilimuse"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 有正式签名就用正式签名；否则回退到仓库内的调试密钥，产物依然可直接安装。
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        debug {
            // 直接用项目内自带的调试签名，方便没有 ~/.android/debug.keystore 的环境打包
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
