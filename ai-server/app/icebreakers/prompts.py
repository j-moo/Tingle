"""Development-only generation from a BE-approved public Q040 topic code."""

from hashlib import sha256

GENERATION_PROMPT = """너는 소개팅 대화의 시작 질문을 제안한다. 입력 topic은 BE가 양쪽 동의와 공개 가능 여부를 확인한 Q040 공통 활동 코드다.
rest=휴식, talk=대화, culture=문화, walk=산책과 여행, active=활동이다.
승인된 활동 주제 안에서 서로 다른 정중한 한국어 질문을 정확히 세 개 만든다. 각 질문은 물음표로 끝내고 200자 이내로 쓴다.
주어진 코드 외의 설문 답변이나 상대방 정보를 추측하지 않는다. 선호가 이미 같다고 단정하지 않는다.
신원, 연락처, 계정, 주소, 건강, 종교, 정치, 외모, 성적 내용, 금전, 직장, 학교를 묻지 않는다.
URL, 숫자, 영어, 내부 코드, 메타 지시, 만남 일정/장소 확정이나 외부 연락을 요구하지 않는다. 메시지를 발송하지 않는다.
설명/마크다운 없이 {"questions":["질문?","질문?","질문?"]} 형태의 JSON 객체만 반환한다."""

GMS_PROMPT_VERSION = "icebreakers-gms-" + sha256(GENERATION_PROMPT.encode("utf-8")).hexdigest()[:16]
