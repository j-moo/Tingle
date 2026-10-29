# Arduino 첫 실습 검증 기록

## 현재 예제와 실습 기록 — 2026-10-28

- 현재 소스: [rgb_led_serial/rgb_led_serial.ino](rgb_led_serial/rgb_led_serial.ino). 배열·반복문으로 내장 RGB LED를 순서대로 제어하고 USB 시리얼에 색상·상태를 출력한다.
- 사용자 확인 모델: **XIAO nRF52840 일반형**. 실물 리비전은 미확인이다. USB 부트로더의 Sense 표기와 실물 모델은 구분했다.
- 환경: Arduino IDE 2.3.10, Arduino CLI 1.5.1, Seeed nRF52 Boards 1.1.13. FQBN은 `Seeeduino:nrf52:xiaonRF52840`이다.
- 이름 변경 후 RGB 예제의 실제 컴파일 통과: Flash **42,732 / 811,008 bytes**, RAM **7,144 / 237,568 bytes**. 아래 명령으로 다시 확인할 수 있다.

```text
arduino-cli compile --fqbn Seeeduino:nrf52:xiaonRF52840 iot/firmware/arduino-onboarding/rgb_led_serial
```

| 확인 항목 | 실제 결과 |
| --- | --- |
| 앞선 빨간 LED 단독 예제 업로드 | COM13에서 `Device programmed.` 확인. 사용자가 빨간 LED의 0.5초 간격 깜빡임을 확인했다. |
| 현재 RGB·시리얼 예제 | 컴파일 통과. 세 색상의 실물 동작·시리얼 출력은 이 기록에서 확인 완료로 처리하지 않는다. |
| Windows `9009` | IDE의 Python 검색 경로 문제를 확인하고, 기존 Python 위치를 Arduino 로컬 설정에 지정한 뒤 IDE 컴파일 성공을 확인했다. 이 PC의 환경 수정이며 저장소 파일은 아니다. |
| COM13 접근 거부 | 시리얼 모니터 연결을 닫은 후 포트 열기와 재업로드에 성공했다. |
| VS Code `#include` 진단 | C/C++ 확장 설치 후 Arduino 헤더 경로를 찾지 못한 진단이었다. 경로 설정 후 사용자가 표시 해제를 확인했다. PC별 편집기 설정은 별도로 준비한다. |

USB 시리얼의 `1`/`0` 명령 제어는 현재 RGB 로그 예제에 포함되지 않는다. 해당 명령의 실물 시험, 외부 RGB·Grove·배터리·BLE와 제품 기능 검증은 후속 항목이다.

## 작업 재개 후 재검증 — 2026-10-29

- 원격 `develop`의 `12cf5db`를 현재 작업 브랜치에 fast-forward로 반영했다. IoT 작업 파일과 겹치는 변경이나 병합 충돌은 없었다.
- 현재 RGB 예제를 같은 FQBN으로 다시 컴파일해 종료 코드 0을 확인했다. Flash **42,732 / 811,008 bytes**, RAM **7,144 / 237,568 bytes**다.
- 통합 전 MD 3개의 SHA-256과 제목·링크를 정리한 본문을 대조해 통합 문서의 누락·중복이 없음을 확인했다.
- IoT 문서와 `docs/README.md` 총 13개의 UTF-8·LF·마지막 개행·후행 공백, 로컬 링크 94개와 통합 문서 섹션 링크 11개를 검사했다. Git에 포함되지 않는 로컬 APK 링크는 빌드 안내로 바꿨다.
- `git diff --check`를 통과했다. 이번 재검증에서는 보드 업로드나 실물 동작 시험을 실행하지 않았다.

## 초기 예제 기록 — 2026-10-27

아래는 당시의 `blink_builtin`·`serial_led` 예제와 미실행 항목을 보존한 이력이다. 두 스케치는 현재 폴더 구성에 포함되지 않는다.

기준일: 2026-10-27(Asia/Seoul) · S15P31E106-149 · 예제 v0.1.0

### 당시 준비 상태

| 항목 | 확인 결과 |
| --- | --- |
| 실물 모델 | 사용자 확인: XIAO nRF52840 일반형 |
| 실물 보드 리비전·개발용 개체 | 아직 확인 필요 |
| PC IDE | Arduino IDE 2.3.10 확인 |
| IDE 내장 CLI | Arduino CLI 1.5.1 확인 |
| 보드 패키지 | Seeed nRF52 Boards 1.1.13 설치 완료. core list 재조회 확인 |
| 보드 선택·FQBN | Seeed XIAO nRF52840 / Seeeduino:nrf52:xiaonRF52840. board listall 확인 |
| 보드의 기존 설정·기록 보존 여부 | 실제 업로드 전 사용자 확인 필요 |
| 배선 | 예제 조건: USB만 연결, 배터리·외부 모듈 없음. 실물 상태는 미확인 |
| 현재 COM 포트 | CLI에 COM3·COM4만 보이며 VID/PID·XIAO 식별 정보 없음. 업로드 대상 미확인 |
| 제품 프로토콜·정책·규칙 버전 | 이 예제는 제품 통신·매칭을 실행하지 않아 적용하지 않음 |

### 당시 자동 확인

| 검사 | 상태 | 의미 |
| --- | --- | --- |
| blink_builtin 보드용 컴파일 | 통과, 종료 코드 0 | Flash 21,108 / 811,008 bytes, RAM 3,096 / 237,568 bytes |
| serial_led 보드용 컴파일 | 통과, 종료 코드 0 | Flash 42,172 / 811,008 bytes, RAM 7,152 / 237,568 bytes |
| 파일 형식·문서 링크 | 4개 파일 통과 | UTF-8/LF/마지막 개행/후행 공백 및 로컬 링크 확인 |

Arduino IDE에 Seeed 공식 보드 URL을 추가하고 코어와 종속 도구를 설치했다.
기존 Arduino AVR Boards 1.8.8은 유지했다. IDE가 열려 있다면 재시작 후 보드를 선택한다.
컴파일은 IDE에 포함된 CLI로 아래 인자를 실행했으며 결과물은 OS 임시 폴더에 두었다.

```text
compile --fqbn Seeeduino:nrf52:xiaonRF52840 iot/firmware/arduino-onboarding/blink_builtin
compile --fqbn Seeeduino:nrf52:xiaonRF52840 iot/firmware/arduino-onboarding/serial_led
```

컴파일 결과는 프로그램을 보드에 쓰거나 물리 동작을 확인한 결과가 아니다.
USB 장치 목록 조회만 수행했으며 포트에 명령을 보내거나 업로드·리셋·초기화하지 않았다.

### 당시 실보드 확인 계획

2026-10-27 당시 아래 항목의 실제 결과는 모두 **미실행**이었다. 이후 빨간 LED 업로드·관찰 결과는 위의 2026-10-28 기록을 따른다. 아래 표는 당시 계획과 기대 결과를 보존한다.

| ID | 재현 순서 | 기대 결과 | 실제 결과 |
| --- | --- | --- | --- |
| HW-01 | 새 개발용 보드 1대를 데이터 USB로 연결 | 연결한 장치의 COM 포트 확인 | 미실행 |
| HW-02 | blink_builtin 업로드 | 업로드 성공 메시지 | 미실행 |
| HW-03 | 빨간 내장 LED 관찰 | 0.5초 ON / 0.5초 OFF 반복 | 미실행 |
| HW-04 | serial_led 업로드, 모니터 115200 열기 | 시작 도움말과 약 1초 주기의 상태 | 미실행 |
| HW-05 | 1 전송 | 빨간 LED ON, LED=ON | 미실행 |
| HW-06 | 0 전송 | 빨간 LED OFF, LED=OFF | 미실행 |
| HW-07 | ? / x / 줄바꿈 전송 | 도움말 / 오류 / 줄바꿈 무시 | 미실행 |
| HW-08 | 모니터 닫기·다시 열기 | 다시 명령·상태 확인 가능 | 미실행 |
| HW-09 | USB 재연결 후 모니터 열기 | 예제 재시작, LED OFF, 새 uptime | 미실행 |

실행자 / 실행 시각 / 보드 A 또는 B / 리비전 / 예제 파일·변경값 / 실제 COM 포트 /
업로드 결과 / LED 관찰 / 시리얼 출력 / 실패·재시도 순서를 덧붙인다.
실제 개인정보·인증키·토큰은 기록하지 않는다.

BLE·외부 RGB·Grove·배터리·비휘발성 저장·서버 연동·24시간 운전은 이 표의 검증 범위가 아니다.
Story 완료에는 실제 수용 조건 확인과 관련 검토·MR 병합이 남아 있다.
