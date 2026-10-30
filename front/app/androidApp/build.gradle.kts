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
    // 서버 주소 · HTTP 로그는 빌드 타입이 정한다. shared 의 coreModule(BuildConfig.API_BASE_URL, BuildConfig.HTTP_LOG) 로 넘긴다
    buildTypes {
        debug {
            if (teamKey != null) signingConfig = teamKey
            buildConfigField("String", "API_BASE_URL", "\"https://k15e106.p.ssafy.io/dev/api/\"")
            buildConfigField("boolean", "HTTP_LOG", "true")
        }
        release {
            signingConfig = teamKey ?: signingConfigs.getByName("debug")
            buildConfigField("String", "API_BASE_URL", "\"https://k15e106.p.ssafy.io/api/\"")
            buildConfigField("boolean", "HTTP_LOG", "false")   // release 는 URL 도 남기지 않는다
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(libs.koin.android)   // TingleApplication 의 startKoin
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
