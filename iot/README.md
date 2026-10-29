# IoT

Suddenly I See 키링의 실물 보드 펌웨어와 Android 가상 키링 시험 앱을 관리한다.

## 폴더 구조

```text
iot/
├── firmware/          # 실물 XIAO 보드에서 실행하는 Arduino/C++ 코드
├── virtual-keyring/   # Android 기기로 키링 동작을 시험하는 앱
├── KEYRING-SPEC.md    # 키링 통신·데이터·화면 설계 통합 문서
└── README.md          # IoT 폴더 안내
```

## 폴더별 역할

| 경로 | 설명 |
| --- | --- |
| [firmware/](firmware/README.md) | XIAO nRF52840용 펌웨어와 보드 실습 예제를 보관한다. 현재는 내장 RGB LED와 USB 시리얼 출력 예제가 있다. |
| [virtual-keyring/](virtual-keyring/README.md) | 휴대폰·태블릿으로 BLE 송수신, 독립 매칭 판정, 기록 저장, LED·진동 효과를 시험하는 Android 프로젝트다. |
| [KEYRING-SPEC.md](KEYRING-SPEC.md) | 기존 `docs/contracts/iot/`의 MD 3개를 합친 통신 명세·데이터 구조·화면 설계 문서다. 작성 당시 버전과 과거 피드백을 포함한다. |

## 시작하기

- 실물 보드 실습: [펌웨어 안내](firmware/README.md)에서 예제를 선택하고 Arduino IDE로 연다.
- Android 가상 키링: [가상 키링 안내](virtual-keyring/README.md)에서 프로젝트 구조와 빌드 방법을 확인한다.
- 제품 동작·통신 기준: [MVP 명세](../docs/specifications/functional-spec-mvp.md)와 [키링 통합 문서](KEYRING-SPEC.md)를 참고한다.
