# MAT-04 내부 생성 서비스 (-144)

`MatchExplanationService`는 서버가 조회·검증한 `ResolvedContext`와
`DisclosureScope`를 받는 비동기 내부 서비스다. HTTP 요청 본문을 이 객체로
직접 변환하면 안 된다. 기본 권한·정보 사용 범위는 거부다.

## 처리와 반환

1. `authorize`로 현재 권한을 확인한다.
2. 같은 matching.id·판정 참조·버전·열람자인지 확인하고, 충돌·미승인·지원 밖
   근거를 제외한다. 모델 전송과 화면 표시 허용 목록은 각각 적용한다.
3. 승인된 최소 근거만 모델에 보내 생성하고, 구조 검사 후 별도 의미 검증을
   호출한다. 두 호출은 기본 8초의 전체 제한을 공유한다.
4. 모델·검증 오류와 타임아웃은 유효한 표시 허용 근거의 `RULE_FALLBACK`으로
   처리한다. 권한·무결성 실패나 유효 근거 없음은 설명을 반환하지 않는다.
5. 반환 직전 권한을 재확인한다. 점수·제안·기한·수락 상태는 변경하지 않는다.

성공 결과의 `Explanation`은 내부 OpenAPI의
`source/text/evidenceKeys/promptVersion/encounterId` 5필드를 따른다.
`encounterId`는 matching.id인 양의 int64다. 공개 조회의
`ExplanationStatus`와는 다른 DTO다. 내부 처리 사유와 `used_evidence_ids`는
공개 응답 필드가 아니다.

## 연동 전 경계

- 공개 근거 키 매핑이 미합의여서 `evidenceKeys=[]`다. 내부 `e1`·reasonKeys를
  공개 키로 자동 변환하지 않는다.
- `AiInput(attemptId/contextId/manifest)` HTTP 처리와 `contextId` 재조회,
  실제 판정 원본 요약 어댑터는 연결하지 않았다. 같은 판정·버전이라는 비교는
  전달받은 참조 사이의 일치 검사이며, 실제 DB·Manifest 대조 완료가 아니다.
- BE 공개 API·matching.evaluation 저장·현재 참여/동의/종료 상태 권한 검사는
  연결하지 않았다. `authorize`와 scope는 신뢰하는 서버 구현이 공급해야 한다.
- 정보 공개 정책, Manifest의 당시 입력 대응, 중복 실행·늦은 결과 저장 방지,
  공개 PENDING/FAILED 대응은 후속 BE·AI 합의/연동 대상이다.
- GMS는 기존 `httpx2`를 사용한다. 키는 실행 환경에서만 읽으며, 재시도와 대체
  모델은 없다. 정상 생성마다 생성 1회와 의미 검증 1회가 필요하다.
- 필터와 의미 검증 호출이 모든 환각·간접 정보 노출을 보장해서 막는 것은
  아니다. 실제 모델 품질·비용·지연·공개 범위의 사람 평가는 -145 대상이다.
  이 구현의 테스트는 합성 입력과 MockTransport를 사용하며 운영 GMS 검증이 아니다.

## 로컬 확인

저장소 루트의 PowerShell에서 기존 가상환경으로 전체 테스트를 실행한다.
실제 GMS 키나 사용자 정보는 필요하지 않다.

```powershell
Set-Location ai-server
.\.venv\Scripts\python.exe -m pytest -q
```

-145에서는 GEN-01~03의 여러 근거 종합·차이·한 방향 설명, EXPL-01~04의
정상·근거 없음·상충·모델 실패, 권한 철회·판정/버전 혼합·비공개 정보 및
주입 문구, 의미 검증 호출의 실패/타임아웃을 확인한다. 실제 GMS 실험은 비용과
보안 조건을 먼저 확인하고 합성 입력으로 진행한다.
