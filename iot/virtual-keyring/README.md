# 가상 키링 Lab

휴대폰·태블릿에서 BLE 송수신, 독립 매칭 판정, 만남 기록 저장, LED·진동 효과를 시험하는 Android 앱이다.
현재 앱 버전은 **0.13.0-lab**, 패키지는 `com.e106.keyring.lab`이며 Android 8.0(API 26) 이상을 지원한다.

## 폴더 구조

```text
virtual-keyring/
├── app/
│   ├── src/main/java/com/e106/keyring/lab/   # 화면·BLE·저장·백그라운드 처리
│   ├── src/main/res/                       # 문자열·테마·아이콘 리소스
│   ├── src/main/AndroidManifest.xml        # 앱 구성과 권한 선언
│   ├── src/androidTest/                    # Android 계측 검증 코드
│   └── build.gradle                       # Android 앱 빌드 설정
├── core/
│   ├── src/main/java/com/e106/keyring/core/ # 매칭·광고·기록 처리 로직
│   ├── src/test/java/com/e106/keyring/core/ # 코어 검증 코드
│   └── build.gradle                       # Java 모듈과 검증 작업 설정
├── config/checkstyle/                     # Java 코드 스타일 검사 규칙
├── gradle/wrapper/                        # 지정 버전의 Gradle 실행 도구
├── artifacts/                             # 로컬 APK·로그·검증 자료, Git 제외
├── GUIDE.md
├── LAB-PROTOCOL.md
├── LAB-PROTOCOL-V1.md
├── LAB-PROTOCOL-V2.md
├── VERIFICATION.md
├── build.gradle
├── gradle.properties
├── gradlew
├── gradlew.bat
├── settings.gradle
└── README.md
```

## 폴더별 역할

| 폴더 | 설명 |
| --- | --- |
| `app/` | Android 화면, Bluetooth 권한·통신, SQLite 저장, 알림과 백그라운드 동작을 구현한다. |
| `core/` | Android 화면과 분리된 Java 로직이다. 광고 데이터 구성·인증·조립, 매칭 판정과 실패 재처리를 담당한다. 이전 규격의 호환 검증 코드도 포함한다. |
| `config/checkstyle/` | 네이버 Java 스타일의 검사 규칙과 검사 제외 설정을 보관한다. |
| `gradle/wrapper/` | `gradlew`가 사용할 Gradle 버전·배포 주소 설정과 실행용 JAR를 보관한다. |
| `artifacts/` | 작성자의 로컬 APK와 검증 증거를 보관한다. Git에서 제외하므로 새로 받은 저장소에는 없을 수 있다. |
| `.gradle/`, `build/`, 각 모듈의 `build/` | 빌드할 때 생성되는 로컬 캐시·APK·검사 보고서 폴더다. Git에서 제외한다. |

## 주요 파일

| 파일 | 설명 |
| --- | --- |
| [GUIDE.md](GUIDE.md) | 설치·실물 시험, 화면·백그라운드 동작, 빌드·검사와 연동 범위를 설명한다. |
| [LAB-PROTOCOL.md](LAB-PROTOCOL.md) | 현재 0.13 앱의 데이터 송수신과 독립 처리 규격이다. |
| [LAB-PROTOCOL-V1.md](LAB-PROTOCOL-V1.md) | 초기 0.1 앱의 GATT 통신 규격을 보존한 문서다. |
| [LAB-PROTOCOL-V2.md](LAB-PROTOCOL-V2.md) | 0.2~0.10 앱의 광고·스캔 규격을 보존한 문서다. |
| [VERIFICATION.md](VERIFICATION.md) | 버전별 자동 검사와 실물 시험 결과를 기록한다. |
| [settings.gradle](settings.gradle) | `app`, `core` 모듈과 의존성 저장소를 등록한다. |
| [build.gradle](build.gradle) | Android 빌드 플러그인과 공통 Checkstyle 검사 작업을 설정한다. |
| [gradle.properties](gradle.properties) | Gradle JVM 메모리와 인코딩을 설정한다. |
| [gradlew](gradlew), [gradlew.bat](gradlew.bat) | 각각 macOS·Linux, Windows에서 지정된 Gradle을 실행한다. |
| [app/build.gradle](app/build.gradle) | Android SDK, 앱 버전·패키지와 `core` 의존성을 설정한다. |
| [core/build.gradle](core/build.gradle) | Java 버전과 `verifyCore` 등 코어 검증 작업을 설정한다. |
| [.gitignore](.gitignore) | 로컬 설정·빌드 결과·기기 데이터·서명 자료의 Git 추적을 제외한다. |

## 주요 소스

아래 Android 소스는 `app/src/main/java/com/e106/keyring/lab/`, 코어 소스는 `core/src/main/java/com/e106/keyring/core/`에 있다.

| 파일 | 설명 |
| --- | --- |
| `HomeActivity.java`, `MainActivity.java`, `KeyringView.java` | 키링 화면과 설정·진단 화면을 구성한다. |
| `LabController.java`, `BleTransport.java` | 앱 동작 제어, BLE 광고·스캔과 송수신을 담당한다. |
| `KeyringService.java` | 백그라운드 통신과 알림을 유지한다. |
| `KeyringDatabase.java` | 설정·만남·통신 로그·실패 작업을 SQLite에 저장한다. |
| `IndependentMatcher.java`, `MatchWork.java`, `LocalMatchStore.java` | 수신한 데이터의 독립 판정, 내 기기 저장과 실패 재처리를 담당한다. |
| `ProfileBroadcaster.java` | 자기 정보·설정·성향을 반복 광고한다. |
| `Packet.java`, `AdvertisementCodec.java`, `AdvertisementFrames.java` | 무선 메시지의 구성·인증·조각 처리를 담당한다. |

## 빌드와 실행

Android SDK 경로와 Java 실행 환경을 설정한 뒤, 저장소 루트에서 실행한다.

```powershell
cd iot/virtual-keyring
.\gradlew.bat :core:verifyCore :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug checkstyleAll
```

생성 APK는 `app/build/outputs/apk/debug/app-debug.apk`다.
설치·참여 기기 설정과 시험 순서는 [GUIDE.md](GUIDE.md)를 참고한다.
