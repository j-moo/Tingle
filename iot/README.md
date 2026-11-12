# Tingle 키링 펌웨어

2026-11-12 기준 `iot/`는 Seeed XIAO nRF52840용 단일 Zephyr 펌웨어 프로젝트다. 주문한 SIS D3.1 Y3를 기본으로 등록·설정 저장·통신·물리 안내를 함께 빌드한다. 보드에 복사할 파일은 [build/zephyr.uf2](build/zephyr.uf2)다.

앱·서버 연동 담당자에게 공유할 내용은 기존 인계의 **[FE 전달문](app-flow-v2-handoff.md#fe-전달문)**·**[BE 전달문](app-flow-v2-handoff.md#be-전달문)**과 [합동 시험/완료 기준](app-flow-v2-handoff.md#합동-시험과-완료-기준)을 따른다. 현재 구현 기반·추가 연동 작업·제안 API·실물 미검증 범위를 구분했다.

## SIS D3.1 Y3 PCB

`0.9.5-d31`은 제조 파일 `01_SIS_D31_GERBER_CAM_Y3.zip`과 D3.1의 `06_PIN_NET_MAP.csv`를 기준으로 한다. 기본 빌드와 `-Carrier d31`은 같은 설정을 사용한다. [공개 검색 이름 BLE 계약](keyring-app-ble-contract-v1.md#public-scan-name)과 [Fold6 검색·페어링 인계](app-flow-v2-handoff.md#fold6-ble-recovery)를 참조한다.

| 기능 | XIAO 핀 | 제조 기판 연결 |
| --- | --- | --- |
| RGB 빨강 | D0 / P0.02 | R1 → J4.3, HIGH에서 점등 |
| RGB 초록 | D1 / P0.03 | R2 → J4.2, HIGH에서 점등 |
| RGB 파랑 | D3 / P0.29 | R3 → J4.1, HIGH에서 점등. J4.4는 GND |
| 진동 | D2 / P0.28 | R4 → J5.1 IN. J5.2는 BAT_SW에서 공급되는 VMOTOR, J5.3은 GND |
| 물리 안내 중지 | D4 / P0.04 | SW2를 누르면 LOW |
| 전원 | SW1 / BAT_SW | SW1이 배터리 전원 경로를 끊는다. D5 소프트 전원 입력은 사용하지 않는다. |

매칭 진동은 배터리 조건이 맞을 때 동작하며, USB VBUS 연결 중에는 차단한다. 0.9.5는 아래 조건의 **충전 확인 진동만** USB에서 2초간 허용한다. USB로 보드에 전원을 공급하면 등록·동기화·LED 등의 기능을 사용할 수 있다. SW1으로 재시작하면 저장된 기기 키·설정·만남 기록을 복원하고, 앱 설정·시간 동기화 후 새 매칭을 시작한다.

### USB 충전 중 배터리 LED

D3.1 Y3의 배터리를 연결하고 SW1을 ON으로 둔 상태에서 XIAO USB-C에 전원을 공급한다. `0.9.4-d31`부터 **XIAO 보드 내장 RGB와 외부 J4 RGB**에 같은 잔량 구간을 표시한다. 내장 RGB는 외부 LED 하네스를 연결하지 않아도 동작한다. 별도의 작은 충전등은 충전 IC가 제어하며 색을 바꾸는 대상이 아니다.

| 잔량 구간 | 내장/외부 RGB | 기본 XIAO의 전압 환산 기준 |
| --- | --- | --- |
| 30% 미만 | 빨강 | 3.00V 이상, 3.57V 미만 |
| 30% 이상, 70% 미만 | 파랑 | 3.57V 이상, 3.93V 미만 |
| 70% 이상 | 초록 | 3.93V 이상, 4.30V 이하 |

현재 기판에는 별도 잔량계가 없어 기존의 `clamp((mV - 3300) * 100 / 900, 0, 100)` 전압 추정을 LED에만 사용한다. **충전 중 전압은 실제 잔량보다 높게 보일 수 있으며 색은 정확한 SOC나 완충 증명이 아니다.** 기본 보드에서는 USB VBUS·CHG 활성·3.00~4.30V 측정이 모두 확인될 때만 표시한다. CHG가 비활성이면 충전 종료와 배터리 미연결을 구분할 수 없어 충전 색을 끈다. 별도 잔량계를 연결한 빌드에서는 장착 여부와 유효한 잔량 측정값이 있을 때만 USB 잔량 색을 표시한다. `GET_STATUS`의 USB/충전 중 `batteryPercent:null`, `UNKNOWN` 처리 등 기존 계약은 바꾸지 않는다.

같은 구간이 다섯 번 연속 확인되면 색을 바꾼다(측정 간격 약 250ms). USB 제거·알 수 없는 충전 신호·범위 밖 전압·확인된 배터리 미연결은 충전 표시를 해제한다. 내장 RGB는 부팅 오류 빨강과 명시적으로 켠 Lab 진단 파랑을 우선하며, 진단 종료 시 충전 색으로 복귀한다. USB가 연결돼 있으나 충전 상태를 모를 때는 부팅 완료 초록도 표시하지 않는다. USB를 뽑으면 기존 배터리 동작의 준비 완료 초록으로 복귀한다. Rev G 소프트 OFF에서는 내장/외부 RGB를 모두 끈다.

외부 만남 LED가 켜지면 해당 색을 우선 표시하고, 종료되면 충전 상태에 따라 다시 표시한다. 실물 시험에서 SW1 ON/OFF와 배터리 미연결, USB 삽입·제거, 저·중·고 구간, 진단/만남 안내 종료 후 복귀를 확인한다. 30%·70% 경계는 모의 잔량계와 ADC 입력 호스트 검사로 검증하며 실제 배터리 잔량 정확도는 실측으로 별도 검증한다.

### 충전과 배터리 보호 범위

- XIAO USB-C → 내장 BQ25101 충전 IC → BAT 경로로 단일 3.7V 리튬 배터리를 충전한다. D3.1은 SW1 ON이어야 배터리와 XIAO BAT가 연결된다. 0.9.5의 `kr_board_charge_init()`은 P0.13을 LOW로 설정해 공칭 **100mA**를 선택한다. 실제 배터리에 들어가는 전류는 시스템 소비·충전 단계에 따라 달라진다.
- 충전 전압 조절(공칭 4.2V), 전류 감소 후 종료 및 재충전은 충전 IC의 기능이다. RGB 색이나 `GET_STATUS`가 충전을 켜거나 끄지 않는다. LED 판정에서 쓰는 4.30V는 측정값 허용 범위이며 충전 목표 전압이 아니다. [Seeed 배터리 안내](https://wiki.seeedstudio.com/XIAO_BLE/#battery-charging-current) · [TI BQ25101 데이터시트](https://www.ti.com/lit/ds/symlink/bq25101.pdf)
- 배터리 단독에서는 **3.30V 이하를 250ms 이상 간격으로 5회** 확인하면 새 작업 수신을 막고 진행 중인 동기 저장을 마친 뒤 BLE·LED·진동·외부 Flash를 정지하고 System OFF에 진입한다. 4.30V 초과 5회 또는 ADC 측정 실패 20회도 같은 절전 대상이다. 부팅 시에는 NVS/키 생성·무선 시작 전 먼저 검사한다. USB 공급 중에는 진단·복구를 위해 자동 절전하지 않는다. 3.45V 이하 LOW 표시, 이상 상태 회복은 3.50~4.20V 5회다. 이는 **펌웨어 운용 여유값**으로, 실물 ADC 오차와 부하 시험 후 조정할 수 있으며 팩 보호 차단 전압이 아니다.
- 진동은 시작 때와 구동 중에도 전압을 확인해 3.30V 미만/4.25V 초과·미측정이면 중단한다. Rev G의 USB 3V3 매칭 모터 경로는 기존 전원 정책을 유지한다. ADC와 전원 상태는 NUS·메인·출력 작업 간 잠금으로 직렬화한다.
- 절전은 배터리 배선을 끊지 않는다. USB VBUS 상승은 nRF52840 System OFF에서 콜드 부팅할 수 있으며, 저장 중 USB가 들어오면 절전 대신 재부팅한다. 기기 키·설정·만남 기록·페어링 저장 영역과 레코드 형식은 변경하지 않는다. 기상 후 새 매칭은 기존 설정/시간 재동기화 조건을 따른다. [Nordic 전원 관리](https://docs.nordicsemi.com/r/bundle/ps_nrf52840/page/power.html)

### 확인한 배터리 자료와 펌웨어 한계

PCB 대화에서 선택한 배터리는 DTP102050 3.7V 1,000mAh다. 근거는 ICBANQ 2020-11-25 PDF `6b6a71c40aea4536824e34acce397a07.pdf`(SHA-256 `d875499892ce01a8b55aed98501852c2a25615cb102dd2dcef93c363927d65ec`) 4·7·8쪽이다. 정규 충전 전압 4.2V, 방전 종료 2.75V, 표준 충전 200mA, 최대 연속 충전/방전 500mA, 충전 온도 10~45℃다. 문서의 팩 보호회로(DW01+·8205)는 과충전 4.30±0.05V, 과방전 2.4±0.1V의 최후 보호이며 일상 운용 목표가 아니다. 실제 장착 팩이 이 제품인지도 실물 라벨로 대조한다.

100mA는 이 배터리 표준보다 낮지만 XIAO BQ25101의 약 10시간 충전 안전 타이머 안에 1,000mAh 완충을 보장하지 않는다. 50mA에서는 단순 용량/전류만 약 20시간, 100mA에서도 약 10시간에 CV 구간과 시스템 소비가 더해진다. 타이머를 무효화하거나 USB 재연결을 반복해 우회하는 로직은 넣지 않는다. USB 연결이 9시간 이상이며 CHG가 활성일 때 `LONG_USB_SESSION`, CHG가 활성에서 비활성으로 바뀌면 `STOPPED_UNCONFIRMED`를 보고한다. 연결 시간은 충전 IC의 내부 타이머·실제 누적 충전 시간과 다르다. CHG만으로 완충/타임아웃/배터리 제거를 판별할 수 없다.

XIAO 회로도의 TS는 고정 저항으로 처리되어 배터리 온도를 읽을 수 없다. 현재 기판에서 **온도에 따른 충전 제어, 실제 충전 전류·정확한 SOC 계측, 배터리의 물리적 차단**은 펌웨어만으로 추가할 수 없다. MCU 내부 온도를 배터리 온도로 대신하지 않는다. [Seeed 회로도](https://files.seeedstudio.com/wiki/XIAO-BLE/Seeed_Studio_XIAO_nRF52840_PDF.pdf)와 위 TI 데이터시트를 함께 참조한다.

### USB 충전 확인 진동

- 배터리와 모터를 연결하고 D3.1 SW1 ON 상태에서 XIAO USB-C를 연결한다. VBUS·CHG 활성·정상 전압이 5회 연속 확인되고 시작 전압이 3.50~4.25V이면 **출력 시작부터 2초간 한 번** 진동한다. USB로 부팅한 경우에도 같은 조건이다. XIAO 단독에는 진동 모터가 없으므로 J5의 기존 모터가 있어야 한다.
- 한 USB 연결에서 충전 신호 흔들림·재충전·상태 조회·설정 재전송으로 반복하지 않는다. 500ms 이상 USB 제거가 관측된 뒤 재연결하면 다시 한 번 가능하다. 이 상태는 RAM에서 관리하며 재부팅은 새 관측 세션이다. 기기 키나 설정에 진동 플래그를 기록하지 않는다.
- 약한 배터리·전압 이상·CHG 미확인·확인된 배터리 부재면 진동을 건너뛰며, 나중에 잔량이 올라가도 같은 연결에서 뒤늦게 울리지 않는다. USB 제거·CHG 비활성·구동 중 전압 이상·SW2/STOP_FEEDBACK·초기화/절전은 진행 중 진동을 취소한다. 출력을 약 50ms마다 검사한다.
- 충전 확인이 매칭 진동과 겹치면 충전 확인이 우선하고 매칭 진동을 나중에 재생하지 않는다. 만남 원본 저장과 매칭 LED는 유지한다. 기본 기판에서는 배터리 존재를 확정 계측하지 못하므로 SW1 OFF/배터리 미연결에서도 오진동이 없는지 실물 시험이 필요하다. 충전 진동은 완충이나 배터리 안전 인증을 뜻하지 않는다.

## Rev G PCB와 물리 안내

`-Carrier revg`는 **Tingle Rev G 31x50 Connector** 프로필의 `0.9.5-revg`를 빌드한다. Rev F도 같은 GPIO와 모터 전원을 사용한다. Rev G는 배터리 입력 J1과 XIAO BAT 연결 J2를 구분하고 XIAO 내장 USB-C 충전을 사용한다. 동작 중에는 J6 RGB에 같은 충전 색을 표시한다. [Seeed XIAO 회로도](https://files.seeedstudio.com/wiki/XIAO-BLE/Seeed_Studio_XIAO_nRF52840_PDF.pdf)

| 기능 | XIAO 핀 | PCB 연결과 동작 |
| --- | --- | --- |
| RGB 빨강 | D0 / P0.02 | R8 470Ω → J6.3, HIGH에서 점등 |
| RGB 초록 | D1 / P0.03 | R9 470Ω → J6.2, HIGH에서 점등 |
| RGB 파랑 | D3 / P0.29 | R10 470Ω → J6.1, HIGH에서 점등. J6.4는 GND |
| 진동 | D2 / P0.28 | R19 100Ω → J5.1 IN. J5.3은 XIAO 3V3, J5.4는 GND |
| 물리 안내 중지 | D4 / P0.04 | SW2를 누르면 LOW |
| 소프트 전원 | D5 / P0.05 | SW1이 GND에 연결되면 OFF. R13 풀업으로 HIGH면 ON |

Rev G의 RGB 출력은 HIGH에서 고구동(S0H1)을 사용한다. 각 채널의 470Ω 직렬 저항은 PCB에 있다. [nRF52840 GPIO 전기 특성](https://docs.nordicsemi.com/r/bundle/ps_nrf52840/page/gpio.html)

만남을 비휘발성 저장한 뒤에 물리 안내를 시작한다. LED는 각자의 설정을 따르고 동일 만남 ID에서 같은 색을 고른다. 계속 근접한 경우 점등 30초 후 종료한다. 진동은 양쪽 설정이 모두 ON일 때 120ms 구동, 150ms 간격, 120ms 구동을 한 묶음으로 실행한다. 진동 묶음과 겹친 추가 만남은 정상 저장하며 현재 물리 출력을 연장하거나 재시작하지 않는다.

SW2와 앱의 `STOP_FEEDBACK`은 현재 만남 LED와 남은 진동을 취소한다. 상대가 서명된 근접 관측 시간 안에 다시 감지되지 않거나 현재 설정·소유 세대·준비 상태가 유효하지 않으면 만남 출력을 종료한다. 매칭 OFF·초기화에서도 만남 안내를 종료한다. 재전송과 재부팅 뒤에도 동일 확정 기록의 안내는 다시 실행하지 않는다. 보드 자체 RGB는 부팅·Lab 진단과 위 USB 충전 잔량 표시에 사용한다.

Rev G의 Grove 모터는 XIAO 3V3에서 전원을 받는다. USB가 연결돼도 ON 상태에서 알림을 구동할 수 있다. 배터리 단독에서는 3.30–4.25V 조건을 적용한다. 구동 중 USB 상태가 바뀌면 남은 매칭 진동을 취소한다. 0.9.5부터 공칭 충전 전류는 100mA이며 위 배터리 운용·충전 확인 정책을 공유한다. 배터리 잔량은 기존 전압 기반 추정 및 미확인 표시를 유지한다. [Seeed 충전 설정](https://wiki.seeedstudio.com/XIAO_BLE/#battery-charging-current)

SW1을 OFF로 옮기면 진행 중인 저장을 마친 뒤 BLE와 물리 출력을 끄고 nRF52840 System OFF에 진입한다. 배터리와 충전 회로는 연결된 상태다. ON 복귀는 콜드 부팅 경로로 진행하며 기존 키·설정·만남 기록을 복원한다. 새 매칭은 기존 부팅 정책대로 설정·시간을 다시 동기화한 뒤 시작한다.

실물 시험에서는 J1·J2 및 J5·J6 하네스 극성을 확인하고, 배터리와 USB 각각에서 동시 LED·모터 부하, 버튼 중지, 전원 전환, OFF 소비 전류와 충전, ON 복귀 및 실제 앱 재동기화를 검증한다.

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
│  ├─ carrier.c                      # PCB GPIO, 알림 및 절전 복귀
│  ├─ feedback.c                     # 양쪽 옵션과 안내 타이밍
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
│  ├─ power.c                        # 저전압 운용·USB 충전 단발 진동·전원 진단
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

- 기본 버전은 SIS D3.1 Y3용 `0.9.5-d31`이다. 팀 서버 검증키는 `config/dev-settings-public-key.hex`, 키 ID는 `dev-ed25519-799e2999c648fda3`다.
- TSV3 적용 확인은 [`GET_SETTINGS_APPLIED_PROOF`](app-flow-v2-handoff.md#tsv3-설정-적용-증명--073-applied-proof)로 조회한다.
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
