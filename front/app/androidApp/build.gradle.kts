import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.googleServices)
}

// 소셜 로그인 키. Jenkins 는 tingle-app-env 에서 환경변수로, 로컬은 ~/.gradle/gradle.properties 에 둔다(저장소에 넣지 않는다). 없으면 그 공급자 버튼이 숨는다
fun key(name: String) = providers.gradleProperty(name).orNull ?: System.getenv(name) ?: ""

// 네이버 로그인 콜백(App Link). 매니페스트 App Link 와 앱이 보내는 redirect_uri 가 한 값에서 나온다.
// 네이버 개발자센터(모바일 웹 환경)의 Callback URL · 서버 nginx 대체 페이지와 맞춘다. 운영 · 개발 앱이 주소를 나눠 한 폰에 둘 다 깔아도 서로 받지 않는다
fun com.android.build.api.dsl.VariantDimension.naverCallback(path: String) {
    val host = "k15e106.p.ssafy.io"
    manifestPlaceholders["naverCallbackHost"] = host
    manifestPlaceholders["naverCallbackPath"] = path
    buildConfigField("String", "NAVER_REDIRECT_URI", "\"https://$host$path\"")
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
        // 네이버는 Client ID 만. 비밀값(NAVER_CLIENT_SECRET)은 APK 에 넣지 않는다 — 네이버 OIDC 를 PKCE 로 쓴다
        listOf("GOOGLE_WEB_CLIENT_ID", "KAKAO_NATIVE_APP_KEY", "NAVER_CLIENT_ID").forEach { buildConfigField("String", it, "\"${key(it)}\"") }
        manifestPlaceholders["kakaoScheme"] = "kakao" + key("KAKAO_NATIVE_APP_KEY").ifEmpty { "unconfigured" }   // 카카오 로그인 콜백 주소
        manifestPlaceholders["appLabel"] = "Tingle"
        naverCallback("/auth/naver/callback")
    }

    // 팀 서명 키는 Jenkins Credentials(tingle-release-key · tingle-release-password)가 환경변수로 넘긴다 — 공개 APK 는 debug·release 모두 이 키로 찍는다.
    // 키가 없는 PC 에서는 debug·release 모두 로컬 Android 디버그 키로 서명한다.
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
            // 개발용은 식별자·표시 이름을 나눠 운영 APK 와 한 폰에 같이 깔리게 한다
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "Tingle 개발"
            // 카카오 콜백 스킴은 앱 키에서 나온다. 운영과 같은 키를 쓰면 두 앱이 같은 스킴을 등록해 콜백이 엉뚱한 쪽으로 간다
            val devKakao = key("KAKAO_NATIVE_APP_KEY_DEV").ifEmpty { key("KAKAO_NATIVE_APP_KEY") }
            buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"$devKakao\"")
            manifestPlaceholders["kakaoScheme"] = "kakao" + devKakao.ifEmpty { "unconfigured" }
            buildConfigField("String", "API_BASE_URL", "\"https://k15e106.p.ssafy.io/dev/api/\"")
            buildConfigField("boolean", "HTTP_LOG", "true")
            naverCallback("/dev/auth/naver/callback")
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
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)
    implementation(project(":shared"))
    implementation(libs.koin.android)   // TingleApplication 의 startKoin
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
