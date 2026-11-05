# S15P31E106-146 표시 정책 합성 검토 기록

> **이전 초안의 검토 이력**: 이 문서의 1,840개 시나리오와 fit 숫자를 text에 넣는 renderer는 앞선 문서 설계 단계 기록이다. 현재 BE 확정 계약과 fit 비노출 제안은 [README](README.md) 및 [현재 표시 정책](DISPLAY_POLICY_DRAFT.md)을 따른다. 현재 구현 검증은 worktree 464 PASS / 최신 develop 임시 조합 634 PASS이며, 이 문서의 과거 188 PASS·27개 파일 해시 확인과 구분한다.

검토일: 2026-10-29 (Asia/Seoul). [표시 정책 후보](DISPLAY_POLICY_DRAFT.md)의 설계 검증이다. 문서 안의 Python 코드는 재현용 메모리 검토 코드이며 운영 모듈이나 pytest 테스트 파일에 설치하지 않았다.

## 1. 실행 범위와 결과

- 기존 -146 worktree: `codex/ai-personal-analysis-adapter-S15P31E106-146@c63e5be`.
- Python 3.12.12 / FastAPI 0.150.0 / Pydantic 2.14.1 / pytest 8.4.2 / Node.js 24.18.0. 새 의존성 설치 없음.
- 아래 스크립트: **1,840개 합성 시나리오 통과**. pytest 개수와 다른 단위이며 스크립트의 counts 합이다. 하나의 시나리오 안에서 여러 유형/필드를 검사하는 경우도 있다.
- 기존 회귀: **188 passed in 5.65s**. 기존 테스트에 운영 POST 404·health 200·승인 설정 비활성 검사가 포함되어 있다.
- 최신 develop 조합 358 PASS는 앞선 README의 검증 이력이다. 이번 회차 재실행 결과로 집계하지 않는다.
- CI/Docker/실제 BE/JWT/serviceBearer/외부 analysisVersion 승인/저장 데이터 마이그레이션은 검증하지 않았다.
- 보호 대상 운영·테스트·스크립트·JSON·requirements 27개 파일은 문서 작성 전후 SHA-256 비교 대상으로 고정했다. 최종 비교 결과는 README의 이번 회차 기록에 남긴다.

| 시나리오 구분 | 수 |
| --- | --- |
| `readings_15` | 15 |
| `readings_boundary` | 1,584 |
| `readings_order` | 2 |
| `gap_numeric_boundary` | 200 |
| `gap_category_and_missing` | 11 |
| `gap_order` | 2 |
| `preference_evidence` | 15 |
| `self_evidence` | 5 |
| `invalid_input` | 5 |
| `projection_and_input_order` | 1 |
| **합계** | **1,840** |

모든 15개 대표 입력·gap·추천 사례는 원래 analyze_personal()을 통해 100문항 카탈로그 검증을 통과시켰다. 1,584개 reading 경계 조합은 유효한 1~5/unknown 값으로 core.readings()를 직접 검사했다. 점수·모드·정렬은 원래 코어 결과를 비교하며 별도 계산 결과를 운영 설정에 넣지 않았다.

## 2. 15개 분기 입력

기본 입력은 Q001~Q100 전체 unknown이다. 표의 숫자 키는 문항 ordinal로 실제 카탈로그 ID에 역매핑한다. 제시한 항목만 덮어쓰면 해당 분기 1개만 반환되므로 상위 3개 제한에 가려지지 않는다. 각 사례에서 title/priority/evidence를 고정 기대값과 비교하고 text를 변환 전후 그대로 보존하는지 확인했다.

| 사례 | 기대 templateId 후보 | 덮어쓰기 입력 | 기대 priority | 기대 근거 순서 |
| --- | --- | --- | --- | --- |
| B01 | `PA-R-EXPRESSION-01` | `{"30":4,"32":2}` | 10 | Q030 → Q032 |
| B02 | `PA-R-EXPRESSION-02` | `{"32":4,"33":2}` | 10 | Q032 → Q033 |
| B03 | `PA-R-EXPRESSION-03` | `{"32":4,"33":3}` | 3 | Q032 → Q033 |
| B04 | `PA-R-EXPRESSION-04` | `{"30":3,"32":2,"33":3}` | 3 | Q032 → Q033 |
| B05 | `PA-R-EXPRESSION-05` | `{"32":3,"33":3}` | 3 | Q032 → Q033 |
| B06 | `PA-R-CONFLICT-01` | `{"42":4,"43":4}` | 10 | Q042 → Q043 |
| B07 | `PA-R-CONFLICT-02` | `{"42":2,"43":2}` | 9 | Q042 → Q043 |
| B08 | `PA-R-CONFLICT-03` | `{"42":3,"43":3}` | 3 | Q042 → Q043 |
| B09 | `PA-R-EXPERIENCE-01` | `{"38":4,"39":4}` | 9 | Q038 → Q039 |
| B10 | `PA-R-EXPERIENCE-02` | `{"38":4,"39":3}` | 2 | Q038 → Q039 |
| B11 | `PA-R-EXPERIENCE-03` | `{"38":2,"39":3}` | 2 | Q038 → Q039 |
| B12 | `PA-R-EXPERIENCE-04` | `{"38":3,"39":3}` | 2 | Q038 → Q039 |
| B13 | `PA-R-TIME-01` | `{"32":4,"37":2}` | 9 | Q037 → Q032 |
| B14 | `PA-R-TIME-02` | `{"34":3,"36":3}` | 1 | Q034 → Q036 |
| B15 | `PA-R-SUPPORT-01` | `{"44":"explain","45":"listen"}` | 2 | Q045 → Q044 |

제목 원문은 표시 정책의 15-ID 표와 코드 CASES에 고정했다. 문구·ID를 해시로 만들어 검사하지 않는다.

## 3. 경계·순서·추천 근거 기대값

| 구분 | 합성 입력 / 경계 | 기대값 |
| --- | --- | --- |
| expression | Q030/Q032/Q033 각각 1,2,3,4,5,unknown: 216조합 | 2/3/4 경계, 첫 if 우선, 해당 evidence가 unknown이면 미출력 |
| conflict/experience | 각각 두 문항의 6×6: 72조합 | high/low/else 분리, unknown일 때 미출력 |
| time | Q037/Q032/Q034/Q036의 6^4: 1,296조합 | if 선택 근거와 else 근거가 다름. else에 가드 Q037/Q032를 붙이지 않음 |
| 상위 3개 | priority 10/10/9/1/2 등 | 동점 원래 key 순서 보존, 최대 3개. 별도 time=9가 앞서는 사례도 확인 |
| 수치 차이 | Q065와 Q089 각각 actual/wish 1~5 × close/some/wide/unknown: 200조합 | fit=0 포함, 0<fit<1 포함, fit=1/None 제외, Q089 상한의 하한 1, 전체 허용 범위 제외 |
| 범주·복수선택 | Q079는 **배열**, Q040/Q075 교집합, Q052/Q087 irregular 제거 | 원래 카탈로그 타입 준수. 교집합 있으면 fit=1, 유효 시간대가 비면 None |
| 차이 순서 | Q065/Q066/Q067 같은 fit, 서로 다른 fit | fit 오름차순·동점 문항 번호순·최대 2개, 새 priority=2/1 |
| 주요 추천 | Q061=low, Q065=1, Q079=[apologize], Q096=[approach], Q098=close | A: [Q061,Q065,Q079], B: 여기에 Q096,Q098 추가 |
| 전체 추천 참여 문항 | Q061~095를 카탈로그 첫 값으로, Q098=close | rows ID는 Q061~082,Q086,Q088,Q094만. fit=0인 row도 포함 |
| 비참여 선호 | Q083/Q087/Q089/Q090만 응답 | 현재 MODEL 가중 feature에 없어 PREFERENCE_FIT 후보 없음. 자기 정보도 없으면 fallback 추천도 빈 배열 |
| 전체 범위 탈락 | Q065=3,Q079=[apologize],Q098=wide | Q065가 fit=None으로 빠짐. B evidence=[Q079], Q098은 조건 근거에 추가하지 않음 |
| Q096 효과 | unknown/any/미참여 money 선택/모든 참여 domain 동일 가중 | 앞 3개는 조건 ID 제외. 동일 가중에서 score가 같아도 Q096을 소비했다면 ID 포함 |
| Q098 기본값 | some과 unknown | 같은 수치 결과가 나와도 B안에서는 some만 조건 근거로 포함 |
| 범주만 비교 | Q079=[apologize],Q096=[conflict],Q098=wide | B=[Q079,Q096], Q098 제외 |
| 비점수 입력 | Q097/Q099/Q100만 변경 | 추천 유형·점수·근거 불변 |
| SELF 전체 측정 | 자기 60문항 첫 유효 값, 상대 35문항 any | 37개 근거. E/A/C/I+MODEL의 q feature만 확장, N/애착 척도 제외 |
| SELF 일부 척도 누락 | 위 입력의 Q001만 unknown | E 전체 Q001/Q006/Q011/Q016 근거 제외. 남은 정보로 추천 가능 |
| SELF 비가중 척도 | 위 입력의 Q004만 unknown | N score에는 영향 가능, 추천 type/score/근거에는 영향 없음 |
| SELF 조건 입력 | 위 입력에 Q096/Q098만 변경 | 추천 score/근거 불변. 두 조건 ID 제외 |
| 전체 unknown | 100문항 unknown | INSUFFICIENT, 추천 없음, 근거를 임의 생성하지 않음 |
| 잘못된 입력 | bool, any 혼합 배열, Q098=any, Q032=any | INVALID_ANSWER로 차단, 문장 생성하지 않음 |
| 원응답 순서 | 예시의 answers 배열 뒤집기 | 서비스 결과 전체 동일 |

“전체 범위 탈락”은 evidence가 점수 참여 입력을 뜻한다는 설계 한계를 확인하는 사례다. Q098이 후보 탈락에 사용되었다는 사실을 완전한 처리 경로로 보여주려면 별도 provenance 계약이 필요하다.

현재 후보 스크립트는 조회 가능한 고정 registry와 유효한 결과를 전제로 한다. 운영 renderer에서 미등록 (key,title)/ordinal/feature/domain·조작된 row를 전체 실패로 다루는 상세 HTTP 테스트는 **승인 후 구현할 테스트 설계**다. 이 문서만으로 해당 방어가 새로 구현되었다고 주장하지 않는다.

## 4. 재현 명령

기존 회귀 검사:

```powershell
Set-Location 'C:\Users\SSAFY\.codex\worktrees\personal-analysis-146\S15P31E106\ai-server'
& 'C:\Project\S15P31E106\ai-server\.venv\Scripts\python.exe' -B -X utf8 -m pytest -q -p no:cacheprovider
```

아래 Python 블록은 위 ai-server 폴더에서 실행한다. Python/검토 파일을 저장하지 않으려면 PowerShell의 단일 인용 here-string에 블록을 넣고 표준입력으로 전달한다.

```powershell
# <아래 Python 코드>를 이 위치에 그대로 넣는다.
@'
<아래 Python 코드>
'@ | & 'C:\Project\S15P31E106\ai-server\.venv\Scripts\python.exe' -B -X utf8 -
```

후보 선택 B의 renderer를 메모리에만 정의한다. 실제 PresentationPolicy 기본값·버전 승인 목록은 변경하지 않는다. 마지막 JSON의 counts/total, 15개 실측 문구, 두 모드 예시를 확인할 수 있다.

```python
import sys, json, hashlib
from copy import deepcopy
from itertools import product
from pathlib import Path
sys.path.insert(0, str(Path.cwd()))
from app.personal_analysis import core
from app.personal_analysis.reference import CATALOGUE, CALCULATION_VERSION, MODEL, QUESTIONS, QUESTION_IDS
from app.personal_analysis.service import analyze_personal
from app.personal_analysis.validation import AnalysisInputError
from app.personal_analysis_api.dto import Reading, TypeReference

# B01~B15: independent expected titles, priorities and evidence; no production policy.
CASES = [
("PA-R-EXPRESSION-01","expression",{30:4,32:2},"먼저 다가가지만, 좋아한다는 말은 자주 하지 않아요.",10,[30,32]),
("PA-R-EXPRESSION-02","expression",{32:4,33:2},"애정 표현은 자주 하고, 개인적인 고민은 골라서 나눠요.",10,[32,33]),
("PA-R-EXPRESSION-03","expression",{32:4,33:3},"좋아하는 마음을 말로 전하는 편이에요.",3,[32,33]),
("PA-R-EXPRESSION-04","expression",{30:3,32:2,33:3},"좋아하는 마음을 자주 말하는 편은 아니에요.",3,[32,33]),
("PA-R-EXPRESSION-05","expression",{32:3,33:3},"애정 표현과 고민 공유를 상황에 맞춰 선택해요.",3,[32,33]),
("PA-R-CONFLICT-01","conflict",{42:4,43:4},"서운함은 빨리 이야기하고, 감정이 커지면 잠깐 쉬고 싶어요.",10,[42,43]),
("PA-R-CONFLICT-02","conflict",{42:2,43:2},"생각을 정리한 뒤, 시작한 대화는 이어가고 싶어요.",9,[42,43]),
("PA-R-CONFLICT-03","conflict",{42:3,43:3},"갈등 대화에서 시작 시점과 쉬는 방식이 중요해요.",3,[42,43]),
("PA-R-EXPERIENCE-01","experience",{38:4,39:4},"새로운 곳에 가는 것을 좋아하고, 약속은 미리 준비해요.",9,[38,39]),
("PA-R-EXPERIENCE-02","experience",{38:4,39:3},"만나기 전에 시간과 장소를 정해두는 편이에요.",2,[38,39]),
("PA-R-EXPERIENCE-03","experience",{38:2,39:3},"당일의 상황을 보며 약속을 정하는 편이에요.",2,[38,39]),
("PA-R-EXPERIENCE-04","experience",{38:3,39:3},"데이트의 계획과 장소를 유연하게 선택해요.",2,[38,39]),
("PA-R-TIME-01","time",{37:2,32:4},"각자 보내는 시간이 있어도 애정 표현은 자주 하고 싶어요.",9,[37,32]),
("PA-R-TIME-02","time",{34:3,36:3},"연락하는 빈도와 만나는 빈도를 따로 정해요.",1,[34,36]),
("PA-R-SUPPORT-01","support",{45:"listen",44:"explain"},"상대가 힘들 때 내가 먼저 하는 일이 있어요.",2,[45,44]),
]
MAPPING = {(key,title): tid for tid,key,_,title,_,_ in CASES}
assert len(MAPPING) == len(set(MAPPING.values())) == 15
ORDINAL = {ordinal: qid for qid,ordinal in QUESTION_IDS.items()}
def answers(patch=None):
    return {**{i:"unknown" for i in range(1,101)}, **(patch or {})}
def payload(a):
    return dict(surveyVersion=CATALOGUE["surveyVersion"],
                calculationVersion=CALCULATION_VERSION,
                metadata=dict(ownAge=None,desiredAgeMin=None,desiredAgeMax=None),
                answers=[dict(questionId=ORDINAL[i],value=v) for i,v in a.items()])
def run(a):
    before=deepcopy(a)
    result=analyze_personal(payload(a))
    assert a == before
    return result
def render_reading(row):
    return Reading(key=row["key"],templateId=MAPPING[(row["key"],row["title"])],
                   text=row["text"],priority=row["priority"],
                   evidenceQuestionIds=[ORDINAL[e["id"]] for e in row["evidence"]]).model_dump()
def fit_text(fit):
    assert type(fit) in (int,float) and 0 <= fit < 1
    return json.dumps(fit,allow_nan=False,separators=(",",":"))
def render_differences(rows, a, context=True):
    out=[]
    for index,row in enumerate(rows):
        assert row["selfId"] == row["id"]-35
        evidence=[ORDINAL[row["selfId"]],ORDINAL[row["id"]]]
        if context and a[98] in ("close","some","wide") and all(
            core.numeric(o["value"]) for o in QUESTIONS[row["selfId"]]["options"]
        ):
            evidence.append(ORDINAL[98])
        text=(f"{row['label']}: 나의 응답은 ‘{row['self']}’이고, 상대에게 바라는 응답은 "
              f"‘{row['wish']}’입니다. 현재 비교 규칙의 적합도(0~1)는 {fit_text(row['fit'])}입니다.")
        out.append(Reading(key="self-preference-"+ORDINAL[row["selfId"]]+"-"+ORDINAL[row["id"]],templateId="PA-D-SELF-WISH-FIT",
                           text=text,priority=2-index,evidenceQuestionIds=evidence).model_dump())
    return out
def evidence(row,a,context=True):
    ids=[]
    if row["referenceMode"] == "partner-preferences":
        ids=[ORDINAL[r["id"]] for r in row["rows"]]
        if context and isinstance(a[96],list) and any(d["id"] in a[96] for d in row["domains"]):
            ids.append(ORDINAL[96])
        numeric_row=any(r["id"] <= 64 or all(
            core.numeric(o["value"]) for o in QUESTIONS[r["id"]-35]["options"]
        ) for r in row["rows"])
        if context and a[98] in ("close","some","wide") and numeric_row:
            ids.append(ORDINAL[98])
    else:
        assert row["referenceMode"] == "self-similarity"
        for item in row["rows"]:
            key=item["feature"]
            ids += [ORDINAL[int(key[1:])]] if key.startswith("q") else [ORDINAL[i] for i in core.SCALES[key][0]]
    result=list(dict.fromkeys(ids))
    assert all(q in QUESTION_IDS and a[QUESTION_IDS[q]] != "unknown" for q in result)
    return result

counts={}
observed=[]
for tid,key,patch,title,priority,ids in CASES:
    result=run(answers(patch))
    assert len(result.readings)==1
    row=result.readings[0]
    assert (row["key"],row["title"],row["priority"],[e["id"] for e in row["evidence"]]) == (key,title,priority,ids)
    rendered=render_reading(row)
    assert rendered["text"] == row["text"]
    observed.append(dict(templateId=tid,patch=patch,title=title,text=row["text"],priority=priority,evidenceQuestionIds=rendered["evidenceQuestionIds"]))
counts["readings_15"]=15

# 2/3/4 thresholds plus unknown; expected branch selection is independent.
values=[1,2,3,4,5,"unknown"]
hi=lambda x: type(x) is int and x >= 4
lo=lambda x: type(x) is int and x <= 2
n=0
for x,y,z in product(values,repeat=3):
    a=answers({30:x,32:y,33:z})
    branch=0 if hi(x) and lo(y) else 1 if hi(y) and lo(z) else 2 if hi(y) else 3 if lo(y) else 4
    spec=CASES[branch]
    rows=core.readings(a)
    should_emit=all(a[i]!="unknown" for i in spec[5])
    assert len(rows)==int(should_emit)
    if rows: assert MAPPING[(rows[0]["key"],rows[0]["title"])]==spec[0]
    n+=1
for x,y in product(values,repeat=2):
    for start,key,patch,branch in [
        (5,"conflict",{42:x,43:y},0 if hi(x) and hi(y) else 1 if lo(x) and lo(y) else 2),
        (8,"experience",{38:x,39:y},0 if hi(x) and hi(y) else 1 if hi(x) else 2 if lo(x) else 3),
    ]:
        rows=core.readings(answers(patch))
        assert len(rows)==int(x!="unknown" and y!="unknown")
        if rows: assert MAPPING[(rows[0]["key"],rows[0]["title"])]==CASES[start+branch][0]
        n+=1
for w,x,y,z in product(values,repeat=4):
    a=answers({37:w,32:x,34:y,36:z})
    spec=CASES[12 if lo(w) and hi(x) else 13]
    rows=core.readings(a)
    assert len(rows)==int(all(a[i]!="unknown" for i in spec[5]))
    if rows: assert MAPPING[(rows[0]["key"],rows[0]["title"])]==spec[0]
    n+=1
counts["readings_boundary"]=n
a=answers({30:4,32:2,33:3,42:4,43:4,38:4,39:4,37:3,34:3,36:3,45:"listen",44:"explain"})
assert [r["key"] for r in run(a).readings]==["expression","conflict","experience"]
a=answers({30:3,32:4,33:3,37:2,42:3,43:3,38:3,39:3,45:"listen",44:"explain"})
assert [r["key"] for r in run(a).readings]==["time","expression","conflict"]
counts["readings_order"]=2

n=0
for q,actual,wish,tolerance in product([65,89],range(1,6),range(1,6),["close","some","wide","unknown"]):
    a=answers({q-35:actual,q:wish,98:tolerance})
    span={"close":0,"some":1,"wide":2}.get(tolerance,1)
    low=max(1,1 if q==89 else wish-span)
    high=min(5,wish+span)
    expected=None if low==1 and high==5 else min(1,max(0,1-max(low-actual,0,actual-high)/max(low-1,5-high)))
    rows=run(a).selfPreferenceDifferences
    assert len(rows)==int(expected is not None and expected<1)
    if rows:
        assert rows[0]["fit"]==expected
        item=render_differences(rows,a)[0]
        assert item["priority"]==2 and fit_text(expected) in item["text"]
    n+=1
counts["gap_numeric_boundary"]=n
gap_cases=[
({44:"explain",79:["apologize"]},1),
({44:"explain",79:["explain"]},0),
({40:["walk","talk"],75:["talk"]},0),
({40:["walk"],75:["rest"]},1),
({52:["irregular"],87:["evening"]},0),
({52:["evening","irregular"],87:["evening"]},0),
({52:["evening","irregular"],87:["morning"]},1),
({30:1,65:"any"},0),
({30:"unknown",65:5},0),
({30:1,65:3,98:"wide"},0),
({54:1,89:5,98:"close"},0),
]
for patch,length in gap_cases:
    assert len(run(answers(patch)).selfPreferenceDifferences)==length
counts["gap_category_and_missing"]=len(gap_cases)
a=answers({30:1,31:1,32:1,65:5,66:5,67:5,98:"close"})
gaps=run(a).selfPreferenceDifferences
assert [(r["id"],r["fit"]) for r in gaps]==[(65,0),(66,0)]
assert [r["priority"] for r in render_differences(gaps,a)]==[2,1]
a=answers({30:1,31:2,65:5,66:4,98:"close"})
gaps=run(a).selfPreferenceDifferences
assert [r["id"] for r in gaps]==[65,66] and gaps[0]["fit"]<gaps[1]["fit"]<1
counts["gap_order"]=2

p=answers({61:"low",65:1,79:["apologize"],96:["approach"],98:"close"})
result=run(p)
assert result.referenceMode=="PREFERENCE_FIT" and len(result.typeReferences)==3
for row in result.typeReferences:
    assert evidence(row,p,False)==["Q061","Q065","Q079"]
    assert evidence(row,p)==["Q061","Q065","Q079","Q096","Q098"]
base_scores=[(r["type"],r["score"]) for r in result.typeReferences]
ref_n=1
for q,value,expected in [
 (96,"unknown",["Q061","Q065","Q079","Q098"]),
 (96,"any",["Q061","Q065","Q079","Q098"]),
 (96,["money"],["Q061","Q065","Q079","Q098"]),
 (98,"unknown",["Q061","Q065","Q079","Q096"]),
]:
    a={**p,q:value}
    for row in run(a).typeReferences: assert evidence(row,a)==expected
    ref_n+=1
for patch,expected in [
 ({79:["apologize"],96:["conflict"],98:"wide"},["Q079","Q096"]),
 ({65:3,79:["apologize"],98:"wide"},["Q079"]),
 ({61:"low",65:"any",79:"unknown",98:"close"},["Q061","Q098"]),
 ({83:1,87:["morning"],89:1,90:["none"],98:"close"},[]),
]:
    a=answers(patch)
    r=run(a)
    if not expected: assert r.referenceMode=="SELF_SIMILARITY" and r.typeReferences==[]
    else:
        assert r.referenceMode=="PREFERENCE_FIT"
        for row in r.typeReferences: assert evidence(row,a)==expected
    ref_n+=1
for q,value in [(97,["smoking"]),(99,"similar"),(100,3)]:
    cat=next(x for x in CATALOGUE["questions"] if x["ordinal"]==q)
    if q!=97: value=QUESTIONS[q]["options"][0]["value"]
    a={**p,q:value}
    r=run(a)
    assert [(x["type"],x["score"]) for x in r.typeReferences]==base_scores
    assert all(ORDINAL[q] not in evidence(x,a) for x in r.typeReferences)
    ref_n+=1
# Every supported preference feature, including zero-fit rows and lifestyle model features.
allprefs=answers({98:"close",96:["lifestyle"]})
for q in range(61,96):
    cat=next(x for x in CATALOGUE["questions"] if x["ordinal"]==q)
    allprefs[q]=deepcopy(next(x["enum"][0] if "enum" in x else [x["items"]["enum"][0]]
                            for x in cat["responseSchema"]["oneOf"] if "enum" in x or x.get("type")=="array"))
r_allprefs=run(allprefs)
eligible=[ORDINAL[q] for q in list(range(61,83))+[86,88,94]]
assert r_allprefs.referenceMode=="PREFERENCE_FIT"
for row in r_allprefs.typeReferences:
    assert evidence(row,allprefs,False)==eligible
    assert evidence(row,allprefs)==eligible+["Q096","Q098"]
assert any(x["fit"]==0 for row in r_allprefs.typeReferences for x in row["rows"])
ref_n+=1
# Explicit some and unknown use the same default span; only measured context is evidence.
explicit={**p,98:"some"}
implicit={**p,98:"unknown"}
r_explicit,r_implicit=run(explicit),run(implicit)
assert [(x["type"],x["score"]) for x in r_explicit.typeReferences]==[(x["type"],x["score"]) for x in r_implicit.typeReferences]
assert all("Q098" in evidence(x,explicit) for x in r_explicit.typeReferences)
assert all("Q098" not in evidence(x,implicit) for x in r_implicit.typeReferences)
ref_n+=1
# Equal domain weighting can cancel numerically; it remains a consumed condition.
uniform={**p,96:["personality","approach","conflict"]}
unweighted={**p,96:"any"}
r_uniform,r_unweighted=run(uniform),run(unweighted)
assert [(x["type"],x["score"]) for x in r_uniform.typeReferences]==[(x["type"],x["score"]) for x in r_unweighted.typeReferences]
assert all("Q096" in evidence(x,uniform) for x in r_uniform.typeReferences)
ref_n+=1
counts["preference_evidence"]=ref_n

# All measured self answers, all partner preferences any: classify and expand only used features.
s=answers()
for q in range(1,61):
    cat=next(x for x in CATALOGUE["questions"] if x["ordinal"]==q)
    s[q]=deepcopy(next(x["enum"][0] if "enum" in x else [x["items"]["enum"][0]]
                       for x in cat["responseSchema"]["oneOf"] if "enum" in x or x.get("type")=="array"))
for q in range(61,96): s[q]="any"
r=run(s)
assert r.referenceMode=="SELF_SIMILARITY" and len(r.typeReferences)==3
expected_self=[1,6,11,16,2,7,12,17,3,8,13,18,5,10,15,20,30,31,32,33,34,35,36,37,41,38,51,59,39,40,53,42,43,44,45,46,47]
for row in r.typeReferences: assert evidence(row,s)==[ORDINAL[q] for q in expected_self]
self_projection=[TypeReference(typeCode=x["type"],score=x["score"],mode=r.referenceMode,
                               evidenceQuestionIds=evidence(x,s)).model_dump() for x in r.typeReferences]
partial={**s,1:"unknown"}
r_partial=run(partial)
assert len(r_partial.typeReferences)==3
for row in r_partial.typeReferences: assert evidence(row,partial)==[ORDINAL[q] for q in expected_self if q not in [1,6,11,16]]
r_n=run({**s,4:"unknown"})
assert [(x["type"],x["score"]) for x in r_n.typeReferences]==[(x["type"],x["score"]) for x in r.typeReferences]
for row in r_n.typeReferences: assert "Q004" not in evidence(row,{**s,4:"unknown"})
controls={**s,96:["approach"],98:"wide"}
r_controls=run(controls)
assert [(x["type"],x["score"]) for x in r_controls.typeReferences]==[(x["type"],x["score"]) for x in r.typeReferences]
for row in r_controls.typeReferences: assert evidence(row,controls)==evidence(row,s)
assert run(answers()).typeReferences==[]
counts["self_evidence"]=5

# Invalid input is rejected before rendering, never repaired or explained.
for q,value in [(65,True),(65,["any",1]),(96,["any","approach"]),(98,"any"),(32,"any")]:
    try: run(answers({q:value}))
    except AnalysisInputError as exc: assert exc.code=="INVALID_ANSWER"
    else: raise AssertionError((q,value))
counts["invalid_input"]=5

# Concrete approved-policy-free projection: PREFERENCE_FIT + two real gap rows.
example=answers({30:4,32:2,44:"explain",45:"listen",65:1,79:["apologize"],96:["approach"],98:"close"})
r=run(example)
projection=dict(readings=[render_reading(x) for x in r.readings],
                selfPreferenceDifferences=render_differences(r.selfPreferenceDifferences,example),
                typeRecommendations=[TypeReference(typeCode=x["type"],score=x["score"],mode=r.referenceMode,
                                                   evidenceQuestionIds=evidence(x,example)).model_dump() for x in r.typeReferences])
assert [x["typeCode"] for x in projection["typeRecommendations"]]==[x["type"] for x in r.typeReferences]
assert [x["score"] for x in projection["typeRecommendations"]]==[x["score"] for x in r.typeReferences]
for row in r.typeReferences:
    TypeReference(typeCode=row["type"],score=row["score"],mode=r.referenceMode,evidenceQuestionIds=evidence(row,example))
assert run(dict(reversed(list(example.items())))).model_dump()==r.model_dump()
counts["projection_and_input_order"]=1
report=dict(counts=counts,total=sum(counts.values()),branches=observed,
            examplePatch={ORDINAL[k]:v for k,v in example.items() if v!="unknown"},
            exampleStatus=r.status,example=projection,selfEvidence=[ORDINAL[q] for q in expected_self],
            preferenceScores=base_scores,selfExample=self_projection)
print(json.dumps(report,ensure_ascii=False,indent=2))
```

## 5. 승인 후 추가할 테스트 설계

1. 실제 승인 registry의 15개 templateId 유일성, 제목 교정 후 같은 ID 유지, 새 의미 분기의 ID 재사용 금지.
2. 모르는 title/ordinal/feature/domain·빈/조작 evidence가 있는 출력 전체 실패. fallback ID·부분 성공·원래 행 누락 금지.
3. 요청/result/index를 받는 승인 renderer의 동시 요청 격리. 호출 순서·반복 렌더링에도 priority가 바뀌지 않음.
4. BE 공개 DTO 및 저장 round-trip으로 text/templateId/priority/evidence 순서·비정수 score 불변 확인.
5. 과거 analysisVersion 결과가 새 문구 정책 배포 후에도 동일하게 조회되는지 확인. 재처리 결과는 원본과 구분.
6. 승인 전 운영 404, 빈 APPROVED_VERSION_BINDINGS/표시 정책, 미설정 503을 유지하는 회귀.

## 6. BE 확정 계약 반영 후 검증 연결

현재 [추가 API 계약 테스트](../../tests/test_personal_analysis_api_contract.py)는 15개 분기·수치 차이 200조합·실제 참여 근거·헤더 경계·샘플 재생성을 검사한다. 차이 문장은 label/self/wish만 사용하고, 원래 fit 계산/정렬을 보존하며 priority=2/1은 미승인 제안으로 시험한다. 공식 근거 B안과 표시 승인은 별개다.

기존 188개를 유지한 최종 worktree는 464개, 최신 develop@49d4ee2 임시 조합은 634개 통과했다. 정상/정보 부족 JSON과 필드별 자료형·재현 명령은 [README](README.md)에 있다.
