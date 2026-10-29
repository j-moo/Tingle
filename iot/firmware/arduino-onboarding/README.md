# Arduino 기초 실습

XIAO nRF52840 일반형의 내장 RGB LED와 USB 시리얼 출력을 익히는 예제다.
보드와 데이터 USB 케이블로 실습하며, 배터리나 외부 모듈은 필요하지 않다.

## 폴더 구조

```text
arduino-onboarding/
├── rgb_led_serial/
│   └── rgb_led_serial.ino
├── GUIDE.md
├── README.md
└── VERIFICATION.md
```

## 폴더·파일 설명

| 경로 | 설명 |
| --- | --- |
| [rgb_led_serial/](rgb_led_serial/rgb_led_serial.ino) | RGB LED와 시리얼 로그 실습용 Arduino 스케치 폴더다. |
| [rgb_led_serial.ino](rgb_led_serial/rgb_led_serial.ino) | 빨강 → 초록 → 파랑 순서로 각각 0.5초 켜고 0.5초 끈다. 시리얼 연결이 준비되면 `RED: ON`, `RED: OFF` 같은 로그를 출력한다. |
| [GUIDE.md](GUIDE.md) | 부품 설명, 개발 환경 설정, 코드 읽는 법과 문제 해결을 담은 상세 안내다. |
| [VERIFICATION.md](VERIFICATION.md) | 현재 RGB 예제의 컴파일 결과, 빨간 LED 실물 시험과 2026-10-27 초기 예제 검증을 구분해 기록한다. 초기 `blink_builtin`·`serial_led`는 현재 폴더 구성에 포함되지 않는다. |

## 실행 방법

1. Arduino IDE에서 [rgb_led_serial.ino](rgb_led_serial/rgb_led_serial.ino)를 연다.
2. **Seeed nRF52 Boards 1.1.13 → Seeed XIAO nRF52840**과 연결된 보드의 포트를 선택한다.
3. **✓ 확인**으로 컴파일하고 **→ 업로드**로 보드에 전송한다.
4. 내장 LED의 색상 순서와 0.5초 켜짐·꺼짐을 관찰한다.
5. 시리얼 모니터를 열고 **115200 baud**를 선택해 색상·상태 로그를 확인한다.

내장 LED는 **LOW일 때 켜지고 HIGH일 때 꺼진다**.
시리얼 모니터를 닫아도 LED는 계속 깜빡인다. 업로드 중 포트 접근 오류가 발생하면
시리얼 모니터 연결을 닫고 실제 포트를 다시 확인한다. 자세한 내용은 [GUIDE.md](GUIDE.md)를 참고한다.
