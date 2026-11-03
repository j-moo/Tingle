# S15P31E106-146 개인 분석 표시 정책: 확정 범위와 제안

갱신일: 2026-10-29 (Asia/Seoul). 이번 사용자 메시지로 전달된 BE 계약에 따라 **공식 버전·근거 B안·AI 내부 결과 보존·오류 코드는 확정**으로 반영했다. **15개 templateId, 차이 문구·priority는 계속 미승인 후보**다. 실제 인증과 운영 라우터는 연결하지 않았다.

## 1. 확정 가능한 계산·복사 규칙

근거는 [core.py](../personal_analysis/core.py), [service.py](../personal_analysis/service.py), [reference.py](../personal_analysis/reference.py), [카탈로그](../personal_analysis/data/questionnaire-SIS-Q-1.1-100.json), [validation.py](../personal_analysis/validation.py)다. 기존 -142 파일은 변경하지 않았다.

공식 조합은 `SIS-Q-1.1-100 + SIS-A-1.0 → local-personal-js100-port-1` 하나다. 두 참조 JSON의 기존 hash는 [adapter.py](adapter.py)에 고정하고 provenance/실제 파일과 비교한다. 최신/기본 버전 alias는 없다. 버전 승인이 표시 정책 승인을 대신하지 않는다.

### 1.1 readings: 실제 15개 분기와 고정 ID 후보

H(q)는 유효한 수치 >=4, L(q)는 <=2다. 모든 행은 **그 행의 기존 evidence 문항이 모두 known일 때만** 출력된다. unknown이 high/low를 만족하지 않아 else로 진입해도 evidence가 unknown이면 add가 행을 만들지 않는다. add에서 탈락해도 다음 elif/else를 재시도하지 않는다.

| 사례 | 고정 templateId 후보 | 기존 key | 분기 선택 조건 | 기존 priority | evidenceQuestionIds 순서 | 기존 title (내부 매핑용) |
| --- | --- | --- | --- | --- | --- | --- |
| B01 | `PA-R-EXPRESSION-01` | `expression` | H(Q030) ∧ L(Q032) | 10 | Q030 → Q032 | 먼저 다가가지만, 좋아한다는 말은 자주 하지 않아요. |
| B02 | `PA-R-EXPRESSION-02` | `expression` | 앞 분기 아님 ∧ H(Q032) ∧ L(Q033) | 10 | Q032 → Q033 | 애정 표현은 자주 하고, 개인적인 고민은 골라서 나눠요. |
| B03 | `PA-R-EXPRESSION-03` | `expression` | 앞 두 분기 아님 ∧ H(Q032) | 3 | Q032 → Q033 | 좋아하는 마음을 말로 전하는 편이에요. |
| B04 | `PA-R-EXPRESSION-04` | `expression` | 앞 두 분기 아님 ∧ L(Q032) | 3 | Q032 → Q033 | 좋아하는 마음을 자주 말하는 편은 아니에요. |
| B05 | `PA-R-EXPRESSION-05` | `expression` | 앞 두 분기 아님 ∧ Q032=3 | 3 | Q032 → Q033 | 애정 표현과 고민 공유를 상황에 맞춰 선택해요. |
| B06 | `PA-R-CONFLICT-01` | `conflict` | H(Q042) ∧ H(Q043) | 10 | Q042 → Q043 | 서운함은 빨리 이야기하고, 감정이 커지면 잠깐 쉬고 싶어요. |
| B07 | `PA-R-CONFLICT-02` | `conflict` | 앞 분기 아님 ∧ L(Q042) ∧ L(Q043) | 9 | Q042 → Q043 | 생각을 정리한 뒤, 시작한 대화는 이어가고 싶어요. |
| B08 | `PA-R-CONFLICT-03` | `conflict` | 앞 두 분기 아님 | 3 | Q042 → Q043 | 갈등 대화에서 시작 시점과 쉬는 방식이 중요해요. |
| B09 | `PA-R-EXPERIENCE-01` | `experience` | H(Q038) ∧ H(Q039) | 9 | Q038 → Q039 | 새로운 곳에 가는 것을 좋아하고, 약속은 미리 준비해요. |
| B10 | `PA-R-EXPERIENCE-02` | `experience` | 앞 분기 아님 ∧ H(Q038) | 2 | Q038 → Q039 | 만나기 전에 시간과 장소를 정해두는 편이에요. |
| B11 | `PA-R-EXPERIENCE-03` | `experience` | 앞 분기 아님 ∧ L(Q038) | 2 | Q038 → Q039 | 당일의 상황을 보며 약속을 정하는 편이에요. |
| B12 | `PA-R-EXPERIENCE-04` | `experience` | 앞 분기 아님 ∧ Q038=3 | 2 | Q038 → Q039 | 데이트의 계획과 장소를 유연하게 선택해요. |
| B13 | `PA-R-TIME-01` | `time` | L(Q037) ∧ H(Q032) | 9 | Q037 → Q032 | 각자 보내는 시간이 있어도 애정 표현은 자주 하고 싶어요. |
| B14 | `PA-R-TIME-02` | `time` | 앞 time 분기 아님 | 1 | Q034 → Q036 | 연락하는 빈도와 만나는 빈도를 따로 정해요. |
| B15 | `PA-R-SUPPORT-01` | `support` | Q045·Q044 모두 known | 2 | Q045 → Q044 | 상대가 힘들 때 내가 먼저 하는 일이 있어요. |

- 표의 title은 내부 분기를 찾는 값이다. 공개 Reading에는 title을 추가하지 않고 원래 row.text를 복사한다.
- ID는 명시적 registry 후보다. title/text 해시·priority·응답값·정렬 위치로 만들지 않는다. 분기를 추가해도 기존 번호를 바꾸거나 폐기 ID를 재사용하지 않는다.
- 코어의 priority 내림차순, 동점 expression→conflict→experience→time→support, 상위 3개를 그대로 복사한다.
- evidence는 원래 순서대로 카탈로그 역매핑한다. Q037→Q032와 Q045→Q044도 번호순으로 바꾸지 않는다.
- evidence는 해당 문구의 근거다. 앞 분기 배제를 위해 읽은 Q030이나 time 기본 분기의 가드 Q037/Q032를 새로 추가하지 않는다.
- 기존 support의 `‘이유 설명’를`처럼 어색한 조사도 이번에는 보존한다. 교정은 별도 문구 변경으로 검토한다.
- 미등록 key/title/ordinal은 임시 ID·일반 문장·부분 성공으로 대체하지 않고 전체 변환을 차단한다.

[proposals.py](proposals.py)가 이 15개 후보를 보관하지만 기본 PresentationPolicy에는 등록하지 않는다.

### 1.2 selfPreferenceDifferences: 계산과 표시 분리

원래 preference_gaps는 Q065~Q095를 순서대로 검사하며 자기 문항은 id-35다. 실제 self/wish가 known이고 wish_fit(..., Q098)이 None이 아니며 fit<1인 행만 만든다. fit 오름차순의 안정 정렬 후 최대 2개이고 동점은 선호 문항 번호순이다. Q061~Q064는 이 차이 목록에 포함하지 않는다.

**현재 검토용 제안은 fit을 text에 노출하지 않는 label-only 양식**이다. fit을 계산·선정·정렬에서 제거하거나 다시 계산하지 않는다. 원래 행의 label/self/wish/fit과 자기·선호 ID를 검증한 후 표시한다.

```text
key: self-preference-{catalogue[selfId]}-{catalogue[id]}
templateId 후보: PA-D-SELF-WISH-FIT
text 후보:
{label}: 나의 응답은 ‘{self}’이고, 상대에게 바라는 응답은 ‘{wish}’입니다.
priority 후보: 2 - index
```

- index는 코어가 이미 반환한 차이 배열의 0부터 시작하는 위치다. 첫 행 2, 둘째 행 1이고 한 행이면 2다. fit의 수치를 정수로 근사하지 않는다.
- priority는 두 차이 행 사이의 표시 순위다. 심각도·관계 부적합·readings.priority와 비교할 점수가 아니다. 두 배열을 합쳐 정렬하지 않는다.
- label은 기존 LABELS, self/wish는 기존 answer_label 결과만 사용한다. 실제 상대의 응답·성격·위험도를 추정하지 않는다.
- key는 실제 카탈로그 ID 쌍으로 만든다. templateId는 문장 양식 후보이므로 두 행에 동일할 수 있다. 행을 templateId 하나로 덮어쓰지 않는다.
- ID의 FIT는 계산으로 선정한 차이 양식을 식별하는 기존 후보명이다. fit 숫자 노출 여부를 ID 문자열에서 추론하지 않는다. 문구 변경과 ID 변경은 별도 검토한다.
- 이전 fit 수치 노출형 초안은 [이전 합성 검토](SYNTHETIC_DISPLAY_REVIEW.md)에 이력으로 남겼다. 현 샘플은 label-only 제안이며 두 양식 모두 운영 문구 승인을 받은 것으로 보지 않는다.
- 수치 허용 범위 전체는 fit=None, 일치/교집합은 fit=1로 차이 행에서 제외한다. Q089는 하한 1의 음주 상한 비교다. Q087은 irregular를 양쪽에서 제거하고 유효 값이 비면 None이다. 새 차이 행을 만들지 않는다.

### 1.3 typeRecommendations: 실제 점수 참여 문항

PREFERENCE_FIT는 선택 유형의 실제 rows[].id를 순서대로 카탈로그로 역매핑한다. 해당 feature가 MODEL.weights에 있고 선호가 known이며 wish_fit이 None이 아닌 행만 원래 rows에 들어간다. **fit=0도 점수 참여 근거**다. 참여 가능 집합은 Q061~Q082, Q086/Q088/Q094의 25개지만 실제 rows에 있는 항목만 반환한다.

Q061~Q064는 E/A/C/I 유형 대표값에 대한 선호다. 본인의 Q001~Q020으로 이 모드의 score를 계산하지 않으므로 자기 척도 문항을 붙이지 않는다. Q083~Q085/Q087/Q089~Q093/Q095는 현재 MODEL 가중 feature가 아니어서 추천 score 근거가 아니다. 차이 설명에서 사용하는 문항 범위와 구분한다.

SELF_SIMILARITY는 partner-preferences 후보가 없을 때 전환한다. 실제 rows[].feature만 MODEL.weights 순서로 읽어 qN은 해당 카탈로그 ID, 척도는 SCALES 전체로 확장하고 처음 나타난 ID만 남긴다. 정보 부족으로 ranking이 비면 추천도 비며 근거를 만들지 않는다.

| 척도 | 전체 확장 순서 |
| --- | --- |
| E | Q001 → Q006 → Q011 → Q016 |
| A | Q002 → Q007 → Q012 → Q017 |
| C | Q003 → Q008 → Q013 → Q018 |
| I | Q005 → Q010 → Q015 → Q020 |

척도 문항 하나가 unknown이면 척도 전체가 None이므로 그 척도 문항 전체를 근거에서 제외한다. N/avoidance/anxiety는 scores에는 있으나 현재 MODEL 가중치에 없어 추천 근거에는 넣지 않는다. 전체 자기 측정 사례는 37개 근거다. 두 모드에서 Q097/Q099/Q100은 score 근거가 아니다. reasons의 일부 문항으로 전체 점수 근거를 대신하지 않는다.

type/score/referenceMode와 선정 배열 순서는 그대로 복사한다. score는 반올림하지 않는다. 현재 순서는 score 내림차순·동점 영어 유형 코드순이다. 초기 대표값과의 비교이며 실제 상대의 자격 통과·추천 확률을 뜻하지 않는다.

## 2. 승인된 Q096/Q098 근거 기준 (B안)

| 출력 / 실제 참여 조건 | Q096 | Q098 |
| --- | --- | --- |
| readings | 미포함 | 미포함 |
| 차이, 수치 옵션 비교 | 미포함 | close/some/wide로 응답했으면 추가 |
| 차이, 범주/배열 비교 | 미포함 | 미포함 |
| PREFERENCE_FIT | Q096이 배열이고 실제 domains 중 선택한 것이 있을 때 추가 | 실제 rows에 척도/수치 비교가 있으며 close/some/wide로 응답했으면 추가 |
| SELF_SIMILARITY | 미포함 | 미포함 |

기존 주요 근거 → Q096 → Q098 순서다. 차이는 자기/선호 쌍 → Q098이다. Q096의 선택 영역이 실제 참여 domain과 겹치지 않으면 제외한다. 모든 참여 domain을 2배로 가중해 최종 평균이 같아져도 조건을 소비했으면 포함한다.

Q098=some과 unknown 기본 허용폭은 계산상 같을 수 있으나 명시적 some만 조건 근거로 포함한다. Q096=any/unknown도 가중 1의 기본 동작이지만 측정 조건 근거로 추가하지 않는다.

Q098 때문에 수치 선호가 전체 허용 범위가 되어 fit=None으로 탈락하고 범주형 rows만 남으면 탈락 문항과 Q098은 evidence에서 제외한다. B안은 **반환 score의 실제 참여 근거**이며 모드 선택·누락의 전 경로까지 설명하지 않는다. 전체 재현에는 BE의 입력·설문/계산/표시 스냅샷 보관이 필요하다.

A(비교 문항만)는 단순하지만 가중/허용폭을 설명하지 못한다. C(모드 선택·탈락까지 모든 의존성)는 provenance가 풍부하나 unknown/any 제어 상태와 측정 근거가 섞여 별도 구조가 필요하다. 이번에는 B안으로 확정했으며 A/C는 트레이드오프 기록이다.

### 잘못된 근거·unknown·any

- 미등록/중복/누락 ID, bool/잘못된 값, 빈/중복/any 혼합 배열은 기존 카탈로그 검증에 따른 422다. unknown으로 수선하지 않는다.
- unknown은 미측정, any는 명시적 무제약이다. 둘을 특정 선호로 문장화하지 않는다. Q098=any는 잘못된 입력이다.
- NONE 등 실제 카탈로그 범주 코드는 unknown으로 바꾸지 않는다.
- 결과의 잘못된 ordinal/feature/domain·모드, 조작된 차이 label/self/wish, 비유한 fit은 대체 문장/근거를 만들지 않고 실패시킨다.
- 키 매핑/계약 검증 실패는 기존 503, 예상 못 한 계산/변환 결함은 500이다. 원문 예외/원응답을 반환하지 않는다.

## 3. 아직 승인이 필요한 항목과 버전·저장 영향

1. 15개 고정 templateId와 차이 ID/key namespace.
2. label-only 차이 문구, fit 수치 비노출 선택, 표시 순위 priority=2/1.
3. SIS-A-1.0과 연결할 최종 표시 정책의 불변 식별/원문 보관 방식. 버전 조합은 확정되었지만 위 표시 내용까지 승인된 것은 아니다.
4. 기존 저장 text/ID/priority/evidence를 최신 사전으로 조용히 재렌더링하지 않는 정책과 재처리/마이그레이션 구분.

동일 분기의 맞춤법/표현 교정은 동일 ID를 유지할 수 있다. 문구 개정과 ID 교체를 자동으로 묶지 않는다. 분기 의미가 달라지면 새 ID를 검토하고 폐기 ID를 재사용하지 않는다. 같은 ID의 문구가 달라져도 과거 결과는 당시 원문 또는 불변 버전별 사전으로 재현해야 한다.

계산이 같으면 기존 calculationVersion을 유지한다. 공개 text/priority/evidence/ID가 달라지는 정책 변경은 외부 analysisVersion 및 별도 표시 버전/저장 manifest에 미칠 영향을 BE와 승인해야 한다. 같은 외부 버전을 재사용해도 된다고 임의 판단하지 않는다. 샘플은 제안 표시임을 외부 검토 manifest로 구분하며 운영 저장/승인 결과가 아니다.

## 4. 실제 구현 경계와 응답 샘플

difference_reading(row,index,answers)와 reference_evidence(row,answers)에 검증된 요청의 **호출별 복사 문맥**을 전달한다. 전역 request·호출 횟수 기반 closure·재계산한 순위를 사용하지 않는다. 코드의 기본 정책은 미승인 문구/ID를 등록하지 않으며, 제안 factory를 명시적으로 선택한 검토 샘플과 테스트만 해당 문구를 쓴다.

SUCCEEDED/INSUFFICIENT 모두 AI가 계산한 scores·배열·missingQuestionIds를 보존한다. 공개 결과의 필터는 BE 책임이며 AI에서 임의 추가하지 않는다. 실제 BE 필터 구현/저장 동작은 이번 로컬 작업으로 검증하지 않았다.

- [정상 합성 요청](../../tests/fixtures/personal-analysis-api/succeeded.request.json) / [정상 제안 응답](../../tests/fixtures/personal-analysis-api/succeeded.response.proposal.json): SUCCEEDED / ASSIGNED / EMBER.
- [정보 부족 합성 요청](../../tests/fixtures/personal-analysis-api/insufficient.request.json) / [정보 부족 제안 응답](../../tests/fixtures/personal-analysis-api/insufficient.response.proposal.json): E=50·나머지 점수 null, readings 2개·차이 2개·추천 3개·missing 52개 보존.
- [검토 manifest](../../tests/fixtures/personal-analysis-api/sample-manifest.json): 승인된 계약과 미승인 표시 정책·파일 해시를 구분.
- 전체 필드 자료형·오류·Request-ID·재현 명령·변경 파일은 [README](README.md)에 정리했다.

## 5. 검증 및 BE 협의 사항

최종 worktree **464 PASS**, 최신 develop@49d4ee2 임시 조합 **634 PASS**. 15개 readings 분기, 차이 경계 200조합, 두 추천 모드·조건 근거, 기존 JS 147개 합성 사례의 결과 보존, UUID 수신/오류 경계, 샘플 재생성, 운영 POST 404를 확인했다. [이전 합성 문서의 1,840개 시나리오](SYNTHETIC_DISPLAY_REVIEW.md)는 fit 노출형 초안의 별도 이력이다.

이전 독립 검증 당시 남은 협의는 표시 문구/ID/priority 승인, 최종 정책 버전·저장 스냅샷, 실제 DTO/공개 필터·저장 round-trip, X-Request-Id의 생성/반송/로그 책임이었다. 당시 대기 중이던 서비스 JWT·401/403의 현재 공통 구현과 오류 분류는 [보안 모듈 README](../security/README.md)를 따른다. 표시 정책은 여전히 미승인이며 운영 라우터는 활성화하지 않는다.
