# Tingle 키링 펌웨어

기준일: 2026-11-06. `iot/` 전체가 Seeed XIAO nRF52840용 **단일 Zephyr 펌웨어 프로젝트**다. 등록·설정 저장·통신 코드를 함께 빌드하며, 보드에 복사하는 파일은 **[build/zephyr.uf2](build/zephyr.uf2)** 하나다.

## 폴더 구조

자동 생성 캐시와 일부 로그는 생략했다. `build/`의 결과물·SDK·이전 작업 자료는 로컬에만 있으며 새로 clone한 저장소에는 포함되지 않는다.

```text
iot/
├─ README.md                         # 폴더 구조·빌드·검증 안내
├─ CMakeLists.txt                    # 통합 펌웨어 소스·라이브러리·서버 공개키 설정
├─ prj.conf                          # BLE·USB·NVS 등 Zephyr 기능 설정
├─ app.overlay                       # 보드 하드웨어·외부 플래시 저장 영역
├─ west.yml                          # Zephyr 의존성·버전 정의
│
├─ src/                              # 펌웨어 구현
│  ├─ main.c                         # 부팅·무선 동작·등록 경로와 시험 경로 연결
│  ├─ nus.c                          # BLE NUS 서비스·JSON 요청 수신·응답 전송
│  ├─ request.c                      # 기기 정보·등록·설정·상태 요청 처리
│  ├─ proof.c                        # 등록·STORED 증명과 공통 검증 처리
│  ├─ settings_auth.c                # 서버 설정 서명·기기 challenge 검증
│  ├─ profile.c                      # 성향·최소 프로필 저장 payload 검증 (연동 후보)
│  ├─ reset.c                        # 초기화 인증·영속 저널·완료 증명·소유 순번
│  ├─ board.c                        # 기기 식별 키·설정 NVS 연결·보드 상태 읽기
│  ├─ engine.c                       # 합성 시험용 키링 간 처리·큐·중복 억제
│  ├─ storage.c                      # 합성 시험 엔진의 비휘발성 저장
│  ├─ phone.c                        # 진단 앱용 바이너리 명령 처리
│  ├─ codec.c                        # 바이너리 인코딩·디코딩
│  ├─ frame.c                        # NUS 수신 조각·메시지 경계 처리
│  ├─ json.c                         # JSON 파싱·필드 검증
│  └─ hash.c                         # 해시·인코딩 유틸리티
│
├─ include/                          # 자료형·상수·함수 선언
├─ config/
│  └─ dev-settings-public-key.hex    # 팀 개발 서버의 공개키, 비밀키 아님
├─ vendor/
│  └─ monocypher/                    # 암호·Ed25519 라이브러리와 라이선스
├─ tests/                            # C·Python·Java 검사와 합성 시험 데이터
│
├─ tools/                            # 개발·검증 보조 도구
│  ├─ build-firmware.ps1             # 통합 펌웨어 빌드·UF2 배치
│  ├─ test-host.ps1                  # 엔진·통신·등록/저장 증명 통합 검사
│  ├─ test-registration.ps1          # 등록·서버 서명·Java BE 호환성 검사
│  ├─ build-phone.ps1                # 실물 진단 APK 빌드·lint·Checkstyle
│  ├─ monitor-board.py               # USB 시리얼 로그 확인
│  ├─ sign-dev-settings.mjs          # 개발 설정 서명 검산 도구
│  ├─ profile_payload.py             # 골든 스냅샷·최소 프로필 저장 codec 참조
│  ├─ lab_server.py                  # 합성 시험용 로컬 수신 서버
│  ├─ phone-test/                    # 실물 키링용 Android 진단 앱
│  └─ virtual-keyring/               # Android 가상 키링 앱
│
├─ examples/                         # 과거 Arduino 실습, 제품 빌드에서 제외
│  ├─ arduino-onboarding/
│  └─ ble-board-to-board/
├─ hardware/                         # 하드웨어 관련 로컬 자료
│
├─ build/                            # Git 제외: 생성 결과·도구·이전 작업 자료
│  ├─ zephyr.uf2                     # ★ 보드에 복사할 유일한 UF2
│  ├─ firmware-manifest.json         # 버전·기준 커밋·소스별 해시·UF2 SHA-256
│  ├─ firmware/                     # 컴파일 중간 파일·ELF·맵·Zephyr 설정
│  ├─ toolchain/                    # 로컬 Zephyr·SDK·컴파일 도구
│  ├─ apps/                         # Android 앱 빌드 결과
│  ├─ tests/                        # 검사 실행 파일·결과
│  ├─ test-server/                  # 로컬 서버 검사 임시 자료
│  ├─ cache/                        # 빌드 캐시
│  └─ history/                      # 이전 산출물·Git 작업 트리 보존
│
├─ keyring-app-ble-contract-v1.md     # 앱·키링 BLE 계약 초안과 과거 시험 기준
├─ keyring-status-ble-v1.md           # 상태 조회 필드·미확인 값 처리
└─ dev-settings-signing-handoff-v1.md # 현재 개발 설정 서명·저장·FE/BE 연동
```

`src/`의 여러 파일은 한 펌웨어로 컴파일된다. `build/firmware/`는 두 번째 소스 프로젝트가 아니라 컴파일 작업 공간이다. 이전 `iot/xiao-keyring/`, `iot/xiao-registration/`, 루트 `build/` 경로는 사용하지 않는다.

## 빌드와 결과 확인

Zephyr 4.2.0, 보드 대상 `xiao_ble/nrf52840`, 공식 Zephyr SDK 0.17.2를 기준으로 한다. Python과 필요한 Zephyr 패키지, CMake, Ninja가 필요하며 빌드 스크립트가 SDK를 자동 설치하지는 않는다.

저장소 루트에서 실행한다. 아래 예시는 실행 도구가 `PATH`에 등록된 환경이다. 그렇지 않으면 각 옵션에 해당 실행 파일의 절대 경로를 지정한다.

```powershell
# 현재 PC처럼 Python 패키지를 로컬 toolchain/site에 준비한 경우
$env:PYTHONPATH = (Resolve-Path ./iot/build/toolchain/site).Path
./iot/tools/build-firmware.ps1 -Python python -CMake cmake -Ninja ninja
Get-FileHash ./iot/build/zephyr.uf2 -Algorithm SHA256
Get-Content ./iot/build/firmware-manifest.json
```

기본 Zephyr 경로는 `build/toolchain/zephyr`, SDK는 `build/toolchain/zephyr-sdk-0.17.2`다. 외부 설치를 사용하면 `-ZephyrBase`·`-ZephyrSdk`를 지정하고 그 Python 환경에 필요한 패키지를 준비한다. 위 `PYTHONPATH` 설정은 로컬 패키지 디렉터리가 있을 때만 사용한다.

빌드 성공 시 UF2를 `build/zephyr.uf2`로 이동하고 manifest를 갱신한다. 실패하면 직전 UF2가 남을 수 있으므로 **종료 코드·빌드 로그·manifest를 확인**한다. `sourceCommit`은 기준 Git 커밋이며 `sourceDirty=true`이면 미커밋 소스가 포함된 빌드다. 이를 해당 커밋만으로 만든 바이너리라고 보고하지 않는다.

`build/`는 `.gitignore`의 `build/` 규칙으로 제외된다. 현재 로컬 `build/history/`에는 Git 작업 트리도 있으므로 전체 `build/`를 단순 캐시처럼 일괄 삭제하지 않는다. 캐시 정리가 필요하면 대상과 보존 자료를 확인하고, 연결된 작업 트리는 `git worktree list`로 먼저 확인한다.

## 현재 개발 빌드 범위

- 기본 버전은 `0.3.1-dev-signed`다. 팀 서버 검증키는 `config/dev-settings-public-key.hex`, 키 ID는 `dev-ed25519-799e2999c648fda3`다. 서버 비밀키는 보드에 넣지 않는다.
- 실제 앱의 NUS JSON 기기 정보 조회·등록 challenge 서명·설정 검증/영구 저장·서명된 `STORED` 응답 경로를 포함한다.
- 현재 개발 설정은 매칭 ON/OFF의 **1바이트 payload**다. 정상 저장 후 `LAB ENABLE` 없이 확장 광고·스캔을 제어한다. 전체 성향 적용·신뢰 시각 준비·실제 양방향 매칭 완료와는 구분한다.
- `0.3.1`에는 `tingle-profile-storage-v1` 성향·최소 프로필 **저장 후보**도 추가했다. AI r2 파생값·네 값 상태, 나이/성별/희망 조건/사진 여부/흡연/음주/종교/알림 옵션을 196B로 받아 기존 서명·영구 저장·STORED 경로를 사용한다. 추가 암호화 특성 `6e400004...`에서 먼저 Read probe(`01`)를 성공시킨 후 같은 연결로 전송한다. 이름·생년월일·사진 원문·원본 설문을 넣지 않는다. 현재 FE/BE는 아직 1바이트 형식에 묶여 있어 아래 인계 문서의 공급/중계 변경이 필요하다. 이를 앱 종단이나 매칭 계산 완료로 표시하지 않는다.
- `LAB ENABLE`, 바이너리 진단 앱, 가상 키링은 합성 시험 경로다. 이 경로의 통과를 실제 앱·서버의 만남 검증 완료로 보고하지 않는다.
- 공통 UF2를 여러 보드에서 사용할 수 있다. 보드별 기존 식별 키는 유지하며, 공통 펌웨어 탑재와 서버의 기기 신뢰/소유 등록은 별개다. 보드 업데이트 때 초기화·전체 erase·키 재생성을 함께 실행하지 않는다.
- 서버 서명으로 허가한 `GET_RESET_CHALLENGE` → `RESET_DEVICE` → `GET_RESET_RESULT` 경로를 추가했다. 삭제 착수 저널·증가한 `ownershipEpoch`를 먼저 저장하고, 소유 데이터·설정·페어링을 논리적으로 정리한 뒤 기기 서명 `CLEARED`를 저장한다. serial·기기 키·keyId는 보존하며 전원 차단 후 같은 작업을 복구한다. 상세 규격과 BE/FE 후속은 [BLE 계약 6절](keyring-app-ble-contract-v1.md#6-초기화등록-해제양도)과 해당 절의 구현 현황을 따른다.
- 기존 기기는 epoch=0의 v1 등록·설정을 계속 사용한다. **실제 서비스 초기화 후(epoch≥1)에는 등록·설정·STORED의 v2가 필수**다. BE/FE의 초기화 API·v2 연결 전에는 앱의 초기화를 활성화하지 않는다. 펌웨어 파일 교체만으로 초기화를 실행하지 않는다. epoch를 모르는 과거 펌웨어로 되돌리는 절차는 지원하지 않는다.
- 배터리 비율·기록 용량·미전송 건수 등은 `null` 또는 `UNKNOWN`일 수 있다. 미측정 값을 0이나 정상으로 바꾸어 표시하지 않는다.

## 검사와 관련 문서

```powershell
./iot/tools/test-host.ps1 -Zig zig -Python python -Javac javac -Java java
./iot/tools/build-phone.ps1 -Java java
```

호스트 검사는 엔진·큐·저장/재시작·바이너리 호환성, Python 수신 서버, Ed25519/설정 서명, 실제 Java BE v1 검증기와의 호환성을 확인한다. 초기화는 모의 영속 저장소의 단계별 전원 차단·응답 유실·기기 키 보존·옛 실행 재조회·소유 순번 차단을 검사하고, 별도 JDK Ed25519 검증기로 초기화 및 등록/STORED v2 원문을 검산한다. 이 v2 검산은 BE의 새 API 구현 완료를 뜻하지 않는다. 기본 TinyCrypt 경로는 `build/toolchain/modules/crypto/tinycrypt`이며 다른 위치는 `-TinyCrypt`로 지정한다. Android 빌드는 JDK·Android SDK·Gradle 의존성이 별도로 필요하다.

빌드·호스트 검사·Android lint 성공은 실물 탑재나 앱 종단 시험 완료를 뜻하지 않는다. 실물 시험에서는 보드 식별값, 실제 펌웨어 버전, 등록→설정 전달→STORED→서버 ACK, 전원 재시작 후 저장 버전과 키 유지, ON/OFF와 연결 복구를 따로 기록한다.

- [개발 설정 서명·FE/BE 연동 안내](dev-settings-signing-handoff-v1.md)
- [앱·키링 BLE 계약](keyring-app-ble-contract-v1.md)
- [상태 조회 명세](keyring-status-ble-v1.md)
- [현재 키링 기능 기준](../docs/specifications/functional-spec-v3/key.md)
- [Notion 프로젝트](https://app.notion.com/p/3edc91ccb66080b6ab18cb7d5144ab8c)
