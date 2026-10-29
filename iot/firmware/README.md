# 키링 펌웨어

실물 XIAO nRF52840 보드에서 실행하는 Arduino/C++ 코드를 보관한다.

## 폴더 구조

```text
firmware/
├── arduino-onboarding/
│   ├── rgb_led_serial/
│   │   └── rgb_led_serial.ino
│   ├── GUIDE.md
│   ├── README.md
│   └── VERIFICATION.md
└── README.md
```

## 폴더·파일 설명

| 경로 | 설명 |
| --- | --- |
| [arduino-onboarding/](arduino-onboarding/README.md) | Arduino 환경 설정과 내장 LED·시리얼 통신을 배우는 기초 실습 폴더다. |
| [arduino-onboarding/rgb_led_serial/](arduino-onboarding/rgb_led_serial/rgb_led_serial.ino) | 내장 RGB LED를 순서대로 깜빡이고 색상·켜짐/꺼짐을 시리얼 모니터에 출력하는 스케치 폴더다. |
| [rgb_led_serial.ino](arduino-onboarding/rgb_led_serial/rgb_led_serial.ino) | 배열과 반복문으로 LED를 제어하는 실제 C++ 소스다. Arduino IDE에서 이 파일을 연다. |
| [arduino-onboarding/README.md](arduino-onboarding/README.md) | 실습 파일 구성과 실행 순서를 안내한다. |
| [arduino-onboarding/GUIDE.md](arduino-onboarding/GUIDE.md) | 부품 19종의 역할, 보드 패키지 설치, LED·시리얼 실습과 오류 해결 방법을 설명한다. |
| [arduino-onboarding/VERIFICATION.md](arduino-onboarding/VERIFICATION.md) | 현재 RGB 예제의 컴파일 결과와 이전 빨간 LED 실물 시험·초기 환경 검증을 날짜별로 구분한 기록이다. |

## 실행 환경

- 보드: **Seeed XIAO nRF52840 일반형**
- 보드 패키지: **Seeed nRF52 Boards 1.1.13**
- 편집·컴파일·업로드: **Arduino IDE**
- 시리얼 모니터: **115200 baud**

Arduino 스케치는 폴더 이름과 기본 `.ino` 파일 이름을 동일하게 유지한다.
현재 예제는 `rgb_led_serial/rgb_led_serial.ino`를 열어 실행한다.

## VS Code에서 편집하기

C/C++ 확장을 사용하면 Arduino 보드 패키지의 헤더 위치를 편집기에 알려줘야 할 수 있다.
사용자 PC의 설치 경로에 맞춰 ARM 컴파일러, 헤더 검색 경로, 보드 매크로와 `Arduino.h` 자동 포함을 설정한다.
프로젝트별 설정 파일은 `.vscode/c_cpp_properties.json`에 둘 수 있다.

`#include` 경로 오류가 남으면 보드 패키지의 실제 설치 위치와 헤더 검색 경로를 먼저 확인한다.
경로를 수정했는데 이전 진단이 남아 있을 때 명령 팔레트(`Ctrl+Shift+P`)에서 **C/C++: Reset IntelliSense Database**를 실행한다.
편집기의 진단과 실제 컴파일 결과는 구분하며, 컴파일·보드 업로드는 Arduino IDE에서 진행한다.
