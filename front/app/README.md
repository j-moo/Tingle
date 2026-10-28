# Android · iOS 앱

Kotlin Multiplatform으로 UI와 로직을 공유합니다. UI는 Compose Multiplatform으로 작성합니다.
Android 실행 진입점은 `androidApp`, iOS는 `iosApp`, 공통 코드는 `shared`입니다.

## 개발 환경

- JDK 17 이상 (현재 개발 PC의 JDK 21 사용 가능)
- 최신 Android Studio 및 Kotlin Multiplatform 플러그인
- Android SDK Platform 37, SDK Build-Tools, Platform-Tools, 에뮬레이터 또는 실기기
- iOS: macOS, 설치한 Kotlin 버전과 호환되는 Xcode, Apple Silicon Mac

Kotlin 2.4.10, Compose Multiplatform 1.11.1, AGP 9.1.1, Gradle 9.6.1로 고정했습니다.
버전은 `gradle/libs.versions.toml`에서 관리합니다.

## Android 실행 (Windows)

1. Android Studio에서 이 디렉터리(`front/app`)를 엽니다.
2. SDK Manager에서 Android SDK Platform 37과 필요한 Build-Tools를 설치합니다.
3. Gradle Sync 후 `androidApp` 실행 구성과 디바이스를 선택합니다.

SDK 경로는 Android Studio가 생성하는 `local.properties` 또는 `ANDROID_HOME`으로 지정합니다.
`local.properties`는 개인 PC 경로이므로 커밋하지 않습니다. 예:

```properties
sdk.dir=C:/Users/SSAFY/AppData/Local/Android/Sdk
```

```powershell
cd front/app
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :androidApp:installDebug
```

APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
Android 최소 지원 버전은 API 24입니다.

## iOS 실행 (Mac)

1. JDK와 Xcode를 설치하고 Xcode를 한 번 실행해 초기 설정을 완료합니다.
2. 저장소 루트에서 `chmod +x front/app/gradlew`를 실행합니다.
3. `front/app/iosApp/iosApp.xcodeproj`를 Xcode로 엽니다.
4. iPhone 시뮬레이터를 선택하고 Run을 실행합니다.

Xcode의 빌드 단계가 `:shared:embedAndSignAppleFrameworkForXcode`를 호출하여
공통 Kotlin 코드를 `Shared.framework`로 빌드하고 연결합니다. CocoaPods는 필요하지 않습니다.
실기기/배포 시 `iosApp/Configuration/Config.xcconfig`의 `TEAM_ID`와
`BUNDLE_ID`를 팀 계정에 맞게 설정하세요. 기본 번들 ID는 `com.ssafy.tingle`입니다.
iOS 최소 지원 버전은 15.3이며 ARM64 기기와 Apple Silicon 시뮬레이터를 대상으로 합니다.
Windows에서는 iOS 빌드와 실행을 할 수 없습니다.

## 코드 위치

- 공통 화면: `shared/src/commonMain/kotlin/com/ssafy/e106/App.kt`
- iOS 연결: `shared/src/iosMain/kotlin/com/ssafy/e106/MainViewController.kt`
- Android 연결: `androidApp/src/main/kotlin/com/ssafy/e106/MainActivity.kt`

공통 화면에 카운터 예제를 두었습니다. 이후 화면과 API 연동 코드를 `commonMain`에 추가하고,
플랫폼 API가 필요한 부분만 `androidMain`/`iosMain`으로 분리하면 됩니다.

## 출처

Gradle Wrapper와 Xcode 프로젝트는 Apache 2.0 라이선스의
[공식 Kotlin KMP App Template](https://github.com/Kotlin/KMP-App-Template)
(커밋 `6b6d09e4f3f5e845116422d59b658791ed6149c6`)을 바탕으로 구성했습니다.
원본 라이선스는 `LICENSE`에 보존했습니다.
