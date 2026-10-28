import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.googleServices)
}

android {
    namespace = "com.ssafy.e106"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ssafy.tingle"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    // 팀 서명 키는 Jenkins Credentials(tingle-release-key · tingle-release-password)가 환경변수로 넘긴다 — 공개 APK 는 debug·release 모두 이 키로 찍는다.
    // 키가 없는 PC 에서는 debug·release 모두 디버그 키로 서명한다.
    val releaseKeystore = System.getenv("TINGLE_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("TINGLE_KEYSTORE_PASSWORD")
                keyAlias = "tingle"
                keyPassword = storePassword
            }
        }
    }
    val teamKey = signingConfigs.findByName("release")
    buildTypes {
        debug {
            if (teamKey != null) signingConfig = teamKey
        }
        release {
            signingConfig = teamKey ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
