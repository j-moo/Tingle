# Tingle 키링 펌웨어

기준일: 2026-11-06. `iot/` 전체가 Seeed XIAO nRF52840용 **단일 Zephyr 펌웨어 프로젝트**다. 등록·설정 저장·통신 코드를 함께 빌드하며, 보드에 복사하는 파일은 **[build/zephyr.uf2](build/zephyr.uf2)** 하나다.

앱·서버 연동 담당자에게 공유할 내용은 기존 인계의 **[FE 전달문](app-flow-v2-handoff.md#fe-전달문)**·**[BE 전달문](app-flow-v2-handoff.md#be-전달문)**과 [합동 시험/완료 기준](app-flow-v2-handoff.md#합동-시험과-완료-기준)을 따른다. 현재 구현 기반·추가 연동 작업·제안 API·실물 미검증 범위를 구분했다.

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
│  ├─ matching.c                     # 고정 PC 참조의 양방향 점수·조건 검사
│  ├─ peer.c                         # 다중 상대·암호화 광고 교환·기존 3개 큐 연결
│  ├─ peer_auth.c                    # 서버 설정 증명과 임시 키에 대한 기기 서명
│  ├─ policy.c                       # 전체 설정·계정 참조·관계 제한 검증
│  ├─ product.c                      # 소유자 인증·동결 증명·조각 조회·서명 접수 삭제
│  ├─ battery.c                      # 잔량계/전압 추정·USB·미측정 상태 구분
│  ├─ service.c                      # 메인 루프의 설정 적용·초기화 중지/완료 조정
│  ├─ reset.c                        # 초기화 인증·영속 저널·완료 증명·소유 순번
│  ├─ board.c                        # 기기 식별 키·설정 NVS 연결·보드 상태 읽기
│  ├─ engine.c                       # 정상/합성 공용 처리·3개 큐·소유별 중복 억제
│  ├─ storage.c                      # 기록·억제·증거·조회 번호 비휘발성 저장
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
│  ├─ test-peer.ps1                  # 10개 가상 peer의 암호화 교환·계산·복구 검사
│  ├─ test-product.ps1               # 전체 설정·다중 계정·기록 업로드·서명 ACK 검사
│  ├─ verify-app-flow.mjs            # Node/OpenSSL 독립 증명 검산
│  ├─ verify-peer-golden.py           # 외부 골든 기준과 독립 C 계산 비교
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
├─ dev-settings-signing-handoff-v1.md # 기존 개발 설정·196B codec·서명 원문
├─ app-flow-v2-handoff.md             # 전체 앱 흐름·새 payload·FE/BE 필수 연동
└─ user-flow-coverage-v1.md           # 내부 유저 플로우41항목·구현/검사 대조
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

- 기본 버전은 `0.7.2-owner-flow`다. 팀 서버 검증키는 `config/dev-settings-public-key.hex`, 키 ID는 `dev-ed25519-799e2999c648fda3`다. 서버 비밀키는 보드에 넣지 않는다.
- 실제 앱의 NUS JSON 기기 정보 조회·등록 challenge 서명·설정 검증/영구 저장·서명된 `STORED` 응답 경로를 포함한다.
- 기존1바이트 ON/OFF 외에 최신 develop `411fce89`는 FE264·BE257의196B 성향 저장 공급/서명·보호 중계를 포함한다. 펌웨어의 전체 TSV3 성향/개인 조건 설정도 지원하며 해당 grant/기록 연동은 아래 인계대로 추가 연결해야 한다. 정상 저장 후 `LAB ENABLE` 없이 무선을 제어하고 저장 완료와 전체 매칭 준비를 구분한다.
- `0.3.1`에는 `tingle-profile-storage-v1` 성향·최소 프로필 저장 경로를 추가했다. AI r2 파생값·네 값 상태, 나이/성별/희망 조건/사진 여부/흡연/음주/종교/알림 옵션을 196B로 받아 기존 서명·영구 저장·STORED 경로를 사용한다. 추가 암호화 특성 `6e400004...`에서 먼저 Read probe(`01`)를 성공시킨 후 같은 연결로 전송한다. 이름·생년월일·사진 원문·원본 설문을 넣지 않는다. 후속 FE264·BE257이 이 저장 형식을 지원하며, 실제 앱/서버 배포·보드 종단 및 전체 TSV3 매칭 연동은 별도로 확인한다.
- `0.4.0`에는 위 정상 서명 저장 뒤 **키링 간 연결 없이** HELLO→암호화 AUTH→암호화 PROFILE/control을 확장 광고·스캔으로 교환하는 개발 경로를 연결했다. 고정 PC 참조의 실제 양방향 계산과 기본 조건 검사를 수행하며 세션16개를 처리한다. 3개 큐는 기존 영속 엔진을 사용하고 임의 체류 시간을 추가하지 않는다. 상세 wire·인증·계산·한계·실물 절차는 [peer 개발 명세](peer-profile-exchange-v1.md)를 따른다. 운영 관계 제한·공식 만남 업로드와 실물 RF 시험은 별도다.
- `LAB ENABLE`, 바이너리 진단 앱, 가상 키링은 합성 시험 경로다. 이 경로의 통과를 실제 앱·서버의 만남 검증 완료로 보고하지 않는다.
- `0.5.0`의 정상 전체 흐름은 `tingle-keyring-settings-v2`를 사용한다. 같은 계정의 여러 키링을 공통 ownerRef로 구분해 자기 매칭을 막고, 서명된 유효기간·관계 제한·사용 상태를 적용한다. 소유자 인증 후 실제 저장 용량·큐 수 조회, 동결 만남 증명·조각 전송, 서명 서버 접수 후 원본 삭제와 이탈 후 억제 해제를 구현했다. 이전 196B만으로 전체 앱 준비를 승격하지 않는다. FE/BE 변경 작업·정확한 원문·오류·복구·검증은 **[전체 연동 인계](app-flow-v2-handoff.md)**를 따른다. 외부 모터/RGB/버튼은 사용자가 추후로 보류했다. 실물 탑재·앱 종단 검증은 별도다.
- `0.6.0`은 로컬 상대 계정 중복 억제·영속 억제 참조, 원본 삭제 후에도 유지되는 기록 번호/조회 epoch, TSV3/타입4의 서버 서명 만남 기한, 이전 억제 자료의 서명 복구를 보강했다. 배터리는 USB 전압을100%로 오표시하지 않으며 선택적 연료계 연결을 지원한다. 기본 보드의 정확 잔량/장착 판별은 UNCONFIRMED로 유지한다. 현재 적용 규격과 FE/BE 작업은 [인계 문서 맨 위 v3 절](app-flow-v2-handoff.md)을 우선한다.
- `0.7.0`은 양도된 같은 기기의 과거/신규 소유 세대를 분리하고, 관측한 옛 인증 회귀 거부·현재 후보 조건 재확인·실패 입력 변경 시 재계산·서명된 scope 복구 v2·저장 공간 예약·늦은 완료 및 연결 경계를 보강했다. [전체41항목 대조표](user-flow-coverage-v1.md)와 [최신 인계0.7 절](app-flow-v2-handoff.md)을 우선한다. 정확한 배터리 잔량·제품 LED/진동은 제외하며 실물 탑재/실제 앱·서버 종단 통과를 뜻하지 않는다.
- `0.7.1`은 !251 리뷰에서 확인한 초기화 중지 계획의 세대 불일치를 수정했다. 메인 루프와 공유하는 `service.c`를 서명된 초기화·재부팅·무선 중지 실패/재시도·일반 설정 적용 중 초기화 도착 사례로 검사한다. FE 상태 조회는2304B로 확장하고 C가 생성한548B STATUS를 실제 Kotlin 전송/파서 회귀의 입력으로 사용한다. 무선/플래시 호스트 어댑터 검사와 실물 완료는 구분한다.
- `0.7.2`는 이전 소유 메타데이터의 서명 복구 후, 현재 설정·기한·ON·저장 상태가 유효하면 추가 동기화 없이 매칭을 재개한다. 누락 중 성향/제어 전송·후보 계산·이탈 관측은 보류한다. OFF·만료·저장 오류와 재부팅 후 신뢰 동기화 조건은 유지한다. 상세 조건은 [최신 인계](app-flow-v2-handoff.md)를 따른다.
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
