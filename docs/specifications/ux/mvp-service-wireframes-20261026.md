# MVP 서비스 와이어프레임 · 2026-10-26

> 정리 상태(2026-10-27): 이 문서는 작성 당시 UX 초안이다. 최신 CM01~16(신고·읽음 표시·수락 취소 등 범위 조정)의 그림 반영은 남아 있으며, 충돌 시 [현재 MVP 명세](../functional-spec-mvp.md)와 [상세 명세](../functional-spec-v3.md)를 우선한다. 이번 작업은 파일 위치와 참조 정리이며 Figma·Mermaid 내용을 갱신한 것이 아니다.

상태: Figma 화면 설계 초안·프로토타입 연결·구조 검증 완료. 제품 코드 구현, 실제 API/기기 테스트, 팀 디자인 승인과 구분한다.

- [모바일 검토 보드](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=106-326) — 05 MVP 서비스 와이어프레임
- [화면별 검토 목차](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=110-1070)
- [홈 프로토타입](https://www.figma.com/proto/8Eue1f6TF7hMG3gVcsP5S4?node-id=112-2976)
- [운영 검토 보드](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=108-2) — 06 MVP 운영 와이어프레임
- [추가 공통 요소](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=106-295)

## 구성

기존 로그인·가입 32개 화면을 확인하고 나머지 서비스 상태 화면 79개와 운영 화면 12개를 추가했다. 설명이 붙은 검토 보드와 최상위 실행 프레임을 함께 제공한다. 복제본은 화면 수에 중복 계산하지 않는다. 가입 완료·로그인 인계용 2개와 화면 목차 1개는 별도다. 원래 가입 화면은 유지했다.

모바일 390×844, 운영 1280×900. 기존 Noto Sans KR(400/500/700), 색·간격·모서리 변수 및 버튼·입력·스위치·탭을 재사용했다. 서비스 헤더·상태 카드·메뉴 행·하단 탐색·사진 자리·메시지·단일 선택 7개 컴포넌트군을 추가했다. 사진 자리는 의도적인 와이어프레임이다.

기준: 기능 명세 v0.1.117 / 정책 v0.1.122. 2026-10-26 재확정으로 진동은 두 사람 모두 ON일 때만 양쪽 실행한다. LED는 본인 설정만 적용한다. 기존 문서의 각자 진동 실행 해석과 Figma 유저플로우 문구를 정정했다.

## 검토 순서

1. 가입 완료 → H01 홈 → P01 제안 → P02 프로필 → P04 내 수락 대기.
2. 별도 시작점 P05 상호 수락 → C02 첫 안내 → C03 대화. 서버 이벤트는 정적 프로토타입에서 자동 재현하지 않는다.
3. P03 프로필 갱신, P06 거절, P07 취소, P08 비성사, P09 만료, P10 조회 실패.
4. H02 연결 끊김·오프라인 준비, H03 기록 대기, H06 공간 부족 → K01~14 관리.
5. S01~07 신고·차단·나가기 → A01~18 동의·문의·탈퇴, M01~12 프로필·재검사.
6. 운영 O02→O03→O11→O12 신고 검토, O04→O05→O06→O07 문의/정정·실패 복구.

## 실제 확인

- 모바일 실행 화면 79개, 인계 2개, 목차 1개. 실행 영역의 이동 328개, 컴포넌트 인스턴스 420개, 텍스트 955개.
- 운영 실행 화면 12개, 이동 62개.
- 이동 대상은 같은 페이지의 유효한 최상위 프레임이며 누락 0개. 현재 탭 자기 이동은 제외.
- 모바일 모든 설계 프레임 390×844. 텍스트 가로 넘침 0개. Noto Sans KR만 사용. 이미지로 평탄화한 UI 0개.
- 홈 구역, 제안·채팅·신고·키링·프로필·계정의 대표 행과 운영 신고 상세를 이미지로 확인. 보완한 단일 선택과 나이 범위는 1배율 재확인. 모든 화면의 실제 클릭/실기기 테스트는 아니다.
- 진동/LED 가상 기대값 64조합 대조: 비대칭 진동 8조합을 양쪽 무진동으로 정정. 문서 기대값 검증이며 펌웨어 실행 테스트가 아니다.

## 남은 작업과 한계

- 입력·슬라이더·체크 상태와 서버 결과는 정적 시연이다. 실제 API·두 계정 테스트와 키보드/입력 검증, 저장·업로드·계산 실패의 세부 변형을 연결해야 한다.
- P11 매칭 이유는 P1 준비 상태 골격이며 계산 버전/실제 근거 확인 후 채운다. 점수·유형을 임의로 만들지 않았다. 아이스브레이킹 P1 상세는 이번 핵심 P0 화면 묶음 밖이다.
- 설문은 기존 가입 문항 컴포넌트를 재사용하는 연결 골격이다. 100문항을 새로 복제하거나 작동하도록 구현하지 않았다.
- 최종 약관·동의 본문, 운영 인증·결과 입력/권한 상세, 구매 링크는 확정 자료와 연결해야 한다. 배치 초안은 법적 문구 승인/실제 권한 부여를 뜻하지 않는다.
- 새 양쪽 진동 조건과 BLE 준비/반영·오프라인 기록·실제 진동/LED는 담당 계약 및 실물 검증이 남는다.
- 다음은 핵심 화면 배치 검토와 FE-07~11 화면/API 연결이다. 디자인 완료로 개발 업무를 완료 처리하지 않는다.

## 화면 목록

| ID | 화면 | 설계 |
| --- | --- | --- |
| H01 | 팅글 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-5) |
| H02 | 팅글 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-60) |
| H03 | 만남 연결 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-106) |
| H04 | 팅글 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-146) |
| H05 | 팅글 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-192) |
| H06 | 키링 안내 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-233) |
| P01 | 제안 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-273) |
| P02 | 상대 프로필 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-308) |
| P03 | 상대 프로필 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-340) |
| P04 | 수락한 제안 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-372) |
| P05 | 새로운 연결 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-398) |
| P06 | 제안 거절 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-425) |
| P07 | 수락 취소 — CM12 후속 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-452) |
| P08 | 제안 안내 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-478) |
| P09 | 제안 안내 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-497) |
| P10 | 제안 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-518) |
| P11 | 매칭 이유 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-553) |
| P12 | 제안 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-574) |
| C01 | 채팅 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-605) |
| C02 | 대화 시작 안내 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-638) |
| C03 | 지수 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-660) |
| C04 | 지수 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-695) |
| C05 | 지수 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-728) |
| C06 | 지수 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-760) |
| C07 | 대화 안내 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-791) |
| C08 | 대화 연결 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-812) |
| C09 | 채팅 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-839) |
| C10 | 지수 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-872) |
| S01 | 대화 메뉴 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-898) |
| S02 | [CM09 후속 — 신고 부분 MVP 제외] 신고하기 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-918) |
| S03 | [CM09 후속 — 신고 부분 MVP 제외] 신고 내용 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-957) |
| S04 | [CM09 후속 — 신고 부분 MVP 제외] 신고 접수 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-985) |
| S05 | 차단 확인 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1012) |
| S06 | 나가기 확인 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1038) |
| S07 | 메시지 선택 · 2/5 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1064) |
| K01 | 내 키링 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1093) |
| K02 | 내 키링 A | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1116) |
| K03 | 내 키링 B | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1142) |
| K04 | 매칭 설정 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1168) |
| K05 | 희망 나이 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1199) |
| K06 | 사진 조건 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1230) |
| K07 | 키링 초기화 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1254) |
| K08 | 초기화 확인 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1281) |
| K09 | 키링 추가 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1308) |
| K10 | 동기화 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1334) |
| K11 | 진동 설정 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1357) |
| K12 | LED 설정 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1382) |
| K13 | 초기화 완료 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1408) |
| K14 | 키링 연결 도움말 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1426) |
| M01 | 내 프로필 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1458) |
| M02 | 프로필 수정 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1486) |
| M03 | 프로필 사진 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1523) |
| M04 | 생활 정보 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1550) |
| M05 | 성향 재검사 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1583) |
| M06 | 성향 재검사 · 37/100 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1609) |
| M07 | 재검사 이어하기 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1638) |
| M08 | 변경 내용 확인 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1663) |
| M09 | 프로필 수정 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1687) |
| M10 | 선택 정보 동의 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1717) |
| M11 | 재검사 제출 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1747) |
| M12 | 재검사 처리 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1772) |
| A01 | 내 정보 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1799) |
| A02 | 계정 관리 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1843) |
| A03 | AI 동의 철회 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1868) |
| A04 | 채팅 이용 안내 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1895) |
| A05 | 차단한 사용자 — CM11 후속 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1928) |
| A06 | 문의하기 — CM08 일반 문의 외부/정정·분실 내부 경로 구분 필요 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1942) |
| A07 | 정보 정정 요청 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-1965) |
| A08 | 키링 분실 문의 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-2003) |
| A09 | 내 문의 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-2029) |
| A10 | 회원 탈퇴 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-2054) |
| A11 | 로그아웃 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-2078) |
| A12 | 탈퇴 최종 확인 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-2104) |
| A13 | 탈퇴 완료 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-2135) |
| A14 | 이용 안내 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-2153) |
| A15 | 동의·약관 안내 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-2174) |
| A16 | 차단 해제 — CM11 후속 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-2192) |
| A17 | 일반 문의 — CM08 외부 창구 안내로 대체 예정 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-2219) |
| A18 | 문의 접수 — 일반 문의 성공 화면은 CM08 제외, 정정·분실 접수 유지 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=107-2246) |
| O01 | 운영자 로그인 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=109-5) |
| O02 | [CM09 후속 — 신고 부분 MVP 제외] 신고 목록 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=109-31) |
| O03 | [CM09 후속 — 신고 부분 MVP 제외] 신고 상세 · R-104 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=109-70) |
| O04 | 문의 목록 — CM08 일반 문의 제외, 정정·분실 유지 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=109-109) |
| O05 | 문의 상세 · Q-208 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=109-153) |
| O06 | 처리 전 최종 확인 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=109-202) |
| O07 | 처리 결과 확인 필요 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=109-239) |
| O08 | 운영 계정·권한 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=109-272) |
| O09 | 접속·처리 기록 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=109-314) |
| O10 | 접근할 수 없습니다 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=109-355) |
| O11 | [CM09 후속 — 신고 부분 MVP 제외] 신고 검토 결과 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=115-89) |
| O12 | 검토 결과 저장 완료 | [열기](https://www.figma.com/design/8Eue1f6TF7hMG3gVcsP5S4?node-id=115-154) |

## 설문 재확인 화면 보완 대기 — 2026-10-26

기능 명세 .119 SUR-02 UX-SUR01~09와 [유저플로우 §03-A](mvp-user-flow-v1.md)에 화면 흐름·상태 명세를 추가했다. 기존 가입 문항/처리 화면과 M05~M07·M11~M12에 재확인 사유 목록, strict 충돌 해소, 미제출 보완/완료 원본 재응답 분기, 기존 결과 유지 안내, 상태 조회 실패·기기별 적용 표시를 연결할 설계 요구사항이다. 이 절은 수정 대상 기록이며 해당 Figma 프레임 수정·시연 연결·화면 검증은 아직 수행하지 않았다. 기존 79개 모바일/12개 운영 화면 수를 추가 구현한 것으로 늘리지 않는다.

## 압축 MVP CM01 화면 변경 요구 — 미반영

키링 관리 화면은 등록 기기 목록/추가 등록/복수 상태 비교에서 ‘내 키링’1개의 상태로 축소한다. 현재 등록이 있으면 추가 등록 불가를 안내하고 정상 초기화/기존 분실 처리 경로는 유지한다. 계정/키링 관계는 보존하며 복수 사용 KEY-05는 후속이다. 정책 .124·명세 .120의 요구사항 기록이며 Figma 프레임과 프로토타입은 아직 수정하지 않았다.


**압축 MVP CM04(2026-10-26):** CHAT-04 종료 1시간 전 별도 알림을 후속 이관했다. 전용 예약/중복/재시도/취소는 현재 MVP 요구에서 제외하며, 첫 입장 안내·화면의 실제 종료 시각 또는 남은 시간·생성 후 7일 자동 종료와 종료 안내는 유지한다. 최신 정책 .126/명세 .122, P0 28/P1 1/후속3. 기존 Mermaid/SVG/Figma의 예약 알림 표현은 후속 갱신 대상이다. ERD/API/앱·Jira 변경 및 실행 검증을 완료한 것은 아니다.


**압축 MVP CM05(2026-10-26):** CHAT-03 첫 입장 확인 버튼·사용자별 안내 확인 여부/시각 저장을 제거하고 상단의 실제 종료 시각·종료 후 대화 확인 불가 상시 안내로 단순화했다. 첫 입장/재입장 모두 바로 진입하며 메시지 읽음·가입 동의·7일 종료는 유지한다. 정책 .127/명세 .123, P0 28/P1 1/후속3 유지. 앞선 CM04 기록의 첫 입장 안내 유지 문구는 이번 결정으로 대체한다. 기존 도표/Figma·ERD/API·앱·Jira 실제 수정은 후속이며 문서 확인을 구현/테스트 완료로 처리하지 않는다.


**압축 MVP CM06(2026-10-26):** 상대에게 보여주는 메시지 읽음 표시 및 상대 화면의 개별 메시지 표시 판정/발신자 전달은 후속 이관했다. 내 목록의 새 메시지 표시·전송 성공/실패 안내는 유지한다. 본인 표시를 위한 최소 상태와 해제/동기화 계약은 남으므로 관련 테이블 전체 삭제로 해석하지 않는다. 앞선 CM05의 읽음 유지 문구를 이번 결정으로 대체한다. 정책 .128/명세 .124, P0 28/P1 1/후속3 유지(CHAT-02 일부 축소). Figma/ERD/API/앱·Jira 실제 변경 및 실행 검증은 별도다.


**압축 MVP CM08(2026-10-26):** 일반 문의는 외부 창구로 대체. 앱 내 일반 문의 작성·상태/내역·답변과 일반 문의용 운영 화면/API는 제외하며, 정정·분실/탈퇴·신고 처리는 유지한다. 외부 연결 열기/복귀를 접수 성공으로 표시하지 않는다. 외부 채널/주소 및 기존 보관 기준을 적용할 운영 설정은 미정이며 실제 개설·연결·Figma/ERD/API/앱·Jira 변경은 미완료다. 정책 .129/명세 .125, P0 28/P1 1/후속3 유지.


**압축 MVP CM09(2026-10-26):** 신고 전체(사유/설명/메시지 선택/접수·운영 목록/검토/기각·신고 대화 조회·신고용 90일 보관)를 후속 이관한다. 차단/해제/나가기·일반 종료 후 30일 보관/삭제·정정/분실/탈퇴는 유지한다. AI-02는 결정 전 범위 유지, 배너 신고 연결은 제거. AI-03은 신고 재도입 후 검토. 기존 신고 데이터가 있으면 별도 전환 검토 없이 삭제/기한 단축하지 않는다. 앞선 CM08의 신고 유지 문구는 이번 결정으로 대체한다. 정책 .130/명세 .126, P0 28/P1 1/후속3 유지(SAFE-01/CHAT-06 일부 축소). Figma/ERD/API/앱·Jira 실제 변경과 기존 데이터 전환은 미수행이다.


**압축 MVP CM11(2026-10-26):** 차단한 사용자 목록·사용자 차단 해제는 후속. 차단 자체와 관계 저장/접근 제한은 유지하며 차단 전 이번 버전에서는 되돌릴 수 없음을 안내한다. 사용자용 목록/해제 화면·API 제외, 운영자 해제 경로 추가 없음. 이전 CM09/CM10의 해제 유지 문구는 대체한다. 정책 .131/명세 .127, P0 28/P1 1/후속3 유지. Figma/ERD/API/앱 실제 변경·실행 검증은 미완료다.


**압축 MVP CM12(2026-10-26):** 내 수락 취소는 후속. 수락 이후 상대 응답/원래 24시간 만료 대기, 차단·탈퇴 및 방 생성 후 나가기는 유지한다. 취소 UI/API뿐 아니라 다른 사용자 요청으로 수락을 거절/미응답으로 되돌리는 것도 제외한다. 초기 거절의 30일 제한·자동 만료 기준 유지. 정책 .132/명세 .128, P0 28/P1 1/후속3 유지. 기존 취소 데이터 전환·Figma/ERD/API/앱 실제 수정·실행 검증은 미완료다.


**압축 MVP CM13 변경·CM14(2026-10-26):** 구글·카카오·네이버 로그인 3종 제공으로 재확정하여 직전 네이버 후속 결정을 대체한다. AI-01 대화 시작 질문 추천은 기존 AI 생성 방식으로 P0 필수 포함한다. 질문 3개를 선택·수정해 직접 전송하고, 생성 지연/실패·미사용에도 직접 대화 가능하며 정보 부족/실패는 일반 질문으로 대체한다. 정책 .134/명세 .130, P0 29/P1 0/후속3. 제공자 3종 등록·인증 및 AI 질문 계약/구현·검증은 남는다. Figma/ERD/API/앱·Jira는 이번에 변경하지 않았다. AI-02 위험 감지도 이후 CM15로 P0 유지 확정했다.


**압축 MVP CM15 및 범위 정리(2026-10-26):** AI-02 위험 대화 감지 P0 유지 확정. AI-01 시작 질문·AI-02 위험 안내를 모두 포함하며 신고는 후속, 직접 차단은 유지한다. 이번 압축 후보 CM01~15의 포함/제외 결정은 완료했고 기능 ID32개는 P0 29/P1 0/후속3이다. 정책 .135/명세 .131 기준. 다음은 제외된 화면/상태/API·데이터 요구를 실제 ERD/API와 대조하는 작업이다. 기능 제외가 기존 테이블/데이터 삭제 승인을 뜻하지 않으며 공용 계정·키링 연결·차단 관계·본인 새 메시지 상태 등은 유지한다. 외부 문의 채널/주소, AI 제공업체·최종 안내/입력 계약·평가 및 계산/기기 검증은 남는다. 이번 반영은 문서이며 ERD/API/코드·Figma·Jira·실행 테스트·MR/병합 완료가 아니다.
