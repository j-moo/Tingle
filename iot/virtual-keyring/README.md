# 가상 키링 Lab

Galaxy S20+와 Galaxy Tab S6 Lite에서 자기 정보·설정·성향을 광고하고, 받은 데이터로 각자 매칭·저장·LED·진동을 수행하는 Android 시험 앱이다.

- Story: [S15P31E106-124](https://ssafy.atlassian.net/browse/S15P31E106-124), 김종무(IOT), 진행 중
- 버전: **0.13.0-lab**, 패키지 `com.e106.keyring.lab`, Android 8.0(API 26) 이상
- 설치 파일: [virtual-keyring-0.13.0-debug.apk](artifacts/virtual-keyring-0.13.0-debug.apk)
- [구현 규격](LAB-PROTOCOL.md), [검증 결과](VERIFICATION.md)

## 이번 변경

2026-10-26 최신 사용자 요청을 적용했다. 기기 사이에서는 자기 데이터만 송신하고 상대 데이터만 수신한다. 근접 재확인, 판정 결과 교환, 저장 합의·응답은 없다. 각 기기가 받은 두 사람의 설정·임시 성향으로 독립적으로 판정한다. 서비스 앱 등록·동기화는 제외하며 임시 성향 알고리즘은 항상 통과한다. 준비·자기 자신·매칭 OFF·사진 필터·정책/규칙 버전 게이트는 유지한다.

```text
내 회원·키링 정보 + 설정 + 성향 → 반복 광고 (스캔과 병행)
상대 데이터 완성·인증 → 판정 FIFO → 즉시 독립 판정 → 내 기기 저장 → 서비스 앱 전송 대기
                             └ 판정·저장 실패 → 오류 재처리 큐 → 실패 단계만 재시도
```

- 빈 차례는 즉시 처리한다. 정상 작업에는 3초/30초 재시도 간격이나 후보 예약 대기가 없다. 실패한 작업이 다음 정상 후보를 막지 않는다.
- 실패한 작업만 1·2·4·8·16·30초(이후 최대 30초) 후 재시도한다. 입력·진행 단계·이벤트 ID·실패 횟수·기한을 SQLite에 저장하고 OFF 동안 중지한다. 재실행 후 이어서 처리한다. **실패 작업만 즉시 재시도** 버튼도 제공한다.
- 불완전한 일반 광고는 최대 150초 동안 암호문 조각을 모은다. 완성 즉시 인증 후 판정한다. 150초를 기다린 다음 판정하는 방식이 아니다. 불완전/변조 데이터는 알고리즘에 넣지 않는다.
- 지원 기기는 전체 데이터 **216바이트**를 확장 광고 한 묶음으로 보낸다. 앱 한도 255바이트, 실행 중 갱신 한도 251바이트 안이며 서비스 식별자·형식·암호화까지 포함한다. 일반 광고 호환 경로도 같은 데이터를 병행한다. 상대별 송신 큐와 HELLO 협상은 없다.
- 한쪽만 수신하면 그 기기만 판정·저장할 수 있다. **내 기기 저장 완료**가 상대 수신·저장까지 보증하지 않는다. 각자의 이벤트 ID·기준 시각은 다를 수 있다.

이 독립 방식은 기존 팀 계약의 공통 이벤트 합의·양쪽 기록 검증 전제와 다르다. 최신 사용자 요청을 시험 앱에만 반영했으며 공통 계약·FE·BE는 수정하지 않았다. 새 기록은 `LOCAL_PENDING`, `peerStorageVerified=false`, `productionUploadAllowed=false`로 구분한다. 서버 제출 규격으로 임의 변환하지 않는다.

## 설치하고 시험하기

1. **참여 기기 모두 0.13 APK를 기존 앱 위에 업데이트**한다. 앱 삭제·데이터 초기화는 하지 않는다. 그룹 코드·기기 ID·기존 만남·로그는 유지한다. 0.12 이하와 새 무선 형식은 호환되지 않는다.
2. 새 실행은 OFF다. 양쪽에서 기존의 같은 그룹 코드를 확인하고 ON을 누른다. 처음 사용하는 경우 첫 기기에서 만든 32자리 코드를 나머지 기기에 입력한다. Bluetooth·주변 기기·알림 권한, Android 11 이하의 위치 권한·위치 서비스를 켠다.
3. 오른쪽 말풍선에서 **송수신 기록**을 연다. 자기 `PROFILE_BROADCAST` 송신, 같은 그룹 발견, 상대 `PROFILE_BROADCAST` 수신, `LOCAL_QUEUED → LOCAL_EVALUATING → LOCAL_EVALUATED → LOCAL_SAVED`를 확인한다. 재확인/저장 응답 패킷은 새로 나오지 않는다.
4. **설정 · BLE 진단 → 만남 기록**에서 `내 기기 저장 완료 · 상대 저장 미확인`을 확인한다. 여러 기기라면 처음 만난 상대별로 각각 저장된다. 양쪽 이벤트 ID가 같을 필요는 없다.
5. 두 프로필의 알림이 ON이면 내 저장 후 **0.5초 활성 → 0.5초 대기 → 0.5초 활성**을 한 번 실행한다. 여러 상대의 효과는 겹치지 않게 순서대로 재생하지만 매칭·저장은 효과 재생을 기다리지 않는다. 진동 모터가 없는 S6 Lite 모델은 화면 LED·흔들림과 `NO_VIBRATOR` 로그로 확인한다.
6. 같은 상대의 기존 처리 중 기록은 OFF·재실행 후에도 보존하며 반복 만남·진동을 만들지 않는다. 0.12의 이미 확정된 상대도 억제를 유지한다. 그래서 기존 상대 시험 시 새 알림 없이 `LOCAL_DUPLICATE`가 보일 수 있다. 기록 삭제·억제 해제 기능은 이번 범위에 추가하지 않았다.
7. JSON 저장/공유로 수신 원문, 전체 과정, `localPending`(내 저장 기록), `processingQueue`(실패 입력·단계·재시도 시각)를 확인한다. 내보내기만으로 원본이나 큐를 삭제하지 않는다.
8. 오류 재처리 시 첫 작업의 원인·단계·시각을 확인한다. 정상 판정 탈락은 오류 재처리 대상이 아니다. 불완전 수신은 `RECEIVE_ASSEMBLY`와 조각 카운터로 구분한다.

확장 경로가 없는 기기는 일반 광고 29개 조각을 모아야 하므로 무선 수신 시간이 길어질 수 있다. 실제 통신 속도는 기기·OS·혼잡·절전에 따라 달라진다. 두 기기의 자동 날짜·시간을 사용한다. 송신 시작 로그는 Android 컨트롤러의 데이터 수락이며 상대 수신 확인은 아니다.

## 화면·백그라운드

휴대폰을 화면이 위로 향하도록 수평으로 놓은 자세가 기울기 0이다. 반대 기울기·반사광·같이 기울어지는 주황 백라이트, 흑백 테마를 유지한다. 도형은 Canvas/Camera 구현이며 외부 3D 에셋을 포함하지 않는다.

ON에서 홈·다른 앱·화면 꺼짐 뒤 복귀는 통신을 유지한다. 뒤로가기 종료·최근 앱 제거·강제 종료·재부팅 뒤 새 실행은 OFF다. OFF는 통신·현재 알림을 중지하지만 저장 기록·실패 작업·억제 상태를 지우지 않는다. 장시간 시험은 삼성 절전 앱과 배터리 최적화 설정을 확인한다. 물리 RF·절전·배터리·진동은 실물 검증 항목이다.

## 빌드와 검사

검증 환경: Windows, Android Studio JBR 25.0.3, SDK/Build Tools 36, Gradle 9.6.1, AGP 9.1.1. `JAVA_HOME`·`ANDROID_HOME` 또는 로컬 `local.properties`를 설정한다.

```powershell
cd iot/virtual-keyring
.\gradlew.bat :core:verifyCore :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug checkstyleAll
```

`verifyIndependent`는 새 생산 경로의 FIFO·오류 격리·복구·기한·입력 검증과 2/4/10대 무선 모델을 검사한다. `verifyCore`에는 구형 포맷과 엔진의 호환 회귀 검사도 포함된다. 구형 `ContractEngine`, `CandidateQueue`, `RadioOutbox`는 새 앱의 실행 경로가 아니다.

- 앱: `app/build/outputs/apk/debug/app-debug.apk`
- 계측: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- Lint: `app/build/reports/lint-results-debug.html`
- 네이버 Checkstyle: `build/reports/checkstyle/all.html` (10.26.1, 경고도 실패)

```powershell
$labAdb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
& $labAdb devices -l
& $labAdb -s '<확인한 대상 serial>' install -r app/build/outputs/apk/debug/app-debug.apk
& $labAdb -s '<테스트 serial>' install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $labAdb -s '<테스트 serial>' shell am instrument -w com.e106.keyring.lab.test/com.e106.keyring.lab.StorageInstrumentation
```

동일 서명 업데이트는 기존 데이터를 보존한다. 서명이 충돌하면 기존 앱을 바로 삭제하지 않는다. RF 계측은 별도 DB와 명시적으로 전달한 시험 그룹을 사용한다.

## 코드와 남은 연동

| 위치 | 책임 |
| --- | --- |
| `core/IndependentMatcher`, `MatchWork`, `LocalMatchStore` | 즉시 FIFO 판정·내 저장·실패 작업 격리와 재시도 |
| `core/ProfileBroadcaster` | 자기 데이터만 확장/일반 광고로 반복 송신 |
| `core/Packet`, `AdvertisementCodec`, `AdvertisementFrames` | 바이너리·인증·조각 복구 |
| `app/LabController`, `BleTransport` | Android 송수신·실패 타이머·로그·JSON |
| `app/KeyringDatabase` | SQLite v6, 기존 기록 보존, 실패 입력·원본·알림 상태 |
| `app/KeyringService`, `HomeActivity`, `KeyringView` | 백그라운드·화면·알림 파형 |

서비스 앱 등록·동기화, 실제 성향/관계 제한, 운영 인증·서명, 서버 업로드·ACK·원본 정리·억제 해제는 제외한다. 임시 데이터·기기 시각은 서버 동기화 성공을 의미하지 않는다. 실보드·워치·iOS·전원·배터리 검증은 후속 범위다.
