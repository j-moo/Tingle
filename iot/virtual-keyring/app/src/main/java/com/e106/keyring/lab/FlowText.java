package com.e106.keyring.lab;

/** Presentation only; persisted process and protocol codes remain language independent. */
public final class FlowText {
	private FlowText() {
	}

	public static String describe(String code) {
		switch (code) {
			case "RECEIVE_ASSEMBLY": return "일반 광고 조각을 모으고 있어요. 완성·인증 전에는 판정하지 않아요.";
			case "LOCAL_QUEUED": return "완전한 수신 데이터를 판정 큐에 넣었어요. 빈 차례는 즉시 처리해요.";
			case "LOCAL_EVALUATING": return "내 기기에서 두 사람의 설정·임시 성향을 판정해요.";
			case "LOCAL_EVALUATED": return "내 기기의 판정이 끝났어요. 상대에게 결과 응답은 요청하지 않아요.";
			case "LOCAL_SAVED": return "내 기기에 만남을 저장했어요. 서비스 앱 전송 대기이며 상대 저장은 확인하지 않아요.";
			case "LOCAL_DUPLICATE": return "이미 저장한 상대예요. 기존 만남을 유지하고 반복 알림을 막았어요.";
			case "LOCAL_RETRY_WAIT": return "실패한 작업을 오류 재처리 큐에 보관했어요. 다른 상대는 계속 처리해요.";
			case "LOCAL_QUEUE_FULL": return "오류 작업 보관 한도에 도달했어요. JSON에서 오류 원인을 확인해주세요.";
			case "BOOT": return "기존 만남과 임시 사용자 데이터를 불러왔어요. 등록·서버 동기화는 생략해요.";
			case "FIXTURE_READY": return "임시 성향·희망 성향을 저장했어요. 기기 시각으로 시험을 준비해요.";
			case "AUTHENTICATED": return "같은 서비스의 키링을 발견하고 시험 그룹 인증을 확인했어요.";
			case "MATCH_DATA_QUEUED": return "내 회원·키링 ID, 성향, 설정 데이터를 전송 대기열에 넣었어요.";
			case "MATCH_DATA_RECEIVED": return "상대의 회원·키링 ID, 성향, 설정 데이터를 받았어요.";
			case "ALGORITHM_QUEUE": return "받은 성향 데이터를 알고리즘 판정 대기 큐에 넣었어요.";
			case "MATCH_EVALUATING": return "임시 매칭 알고리즘을 실행해요. 성향 판정은 항상 성공하도록 설정돼 있어요.";
			case "MATCH_EVALUATED": return "내 조건·임시 매칭 판정이 끝났어요. 결과는 JSON에서 볼 수 있어요.";
			case "PEER_MATCH_RESULT": return "상대가 계산한 매칭 판정을 받았어요.";
			case "BOTH_CONDITIONS_PASSED": return "양쪽 매칭 판정이 모두 통과했어요.";
			case "CONFIRMATION_QUEUE": return "조건 확인이 끝난 순서대로 만남 확정 대기 큐에 넣었어요.";
			case "WAIT_TURN": return "먼저 조건을 확인한 상대의 처리가 끝나기를 기다려요.";
			case "RECHECK_SENT": return "차례가 되어 상대가 여전히 근처인지, 현재 조건이 맞는지 다시 확인해요.";
			case "WAIT_RECHECK": return "상대의 근접·현재 조건 재확인 요청을 기다려요.";
			case "RECHECK_PASSED": return "근접 응답과 현재 조건을 다시 확인했어요.";
			case "PREPARED_LOCALLY": return "만남 ID·참여자·시각과 내 제출 증빙을 기기에 저장했어요. 확정 전 준비 상태예요.";
			case "COMMITTED_LOCALLY": return "상대 준비 저장을 확인하고 내 만남을 확정 저장했어요.";
			case "CONFIRMED_LOCALLY": return "공통 만남을 저장했어요. 만남 당시 데이터는 이후에도 유지해요.";
			case "WAIT_RECEIPT": return "상대에게 저장 완료 응답을 보냈어요. 상대의 수신 확인을 기다려요.";
			case "BOTH_STORED": return "양쪽에 같은 만남이 저장된 것을 확인했어요.";
			case "UPLOAD_PENDING": return "앱 전송 대기 큐에 보관해요. 서비스 앱·서버가 없어 전송하지 않고 원본을 유지해요.";
			case "FEEDBACK_READY": return "양쪽 알림이 켜져 있어 LED·진동 0.5초 / 대기 0.5초 / 0.5초를 예약했어요.";
			case "FEEDBACK_STARTED": return "키링 LED·진동 안내를 시작했어요.";
			case "FEEDBACK_FINISHED": return "LED·진동 두 번의 안내가 끝났어요. 같은 만남에는 다시 울리지 않아요.";
			case "FEEDBACK_STOPPED": return "현재 안내와 남은 안내를 중지했어요. 매칭과 기록은 유지해요.";
			case "NO_VIBRATOR": return "진동 모터가 없는 기기여서 화면 LED·흔들림으로 안내해요.";
			case "FEEDBACK_SKIPPED": return "알림 설정·OFF·기한 상태에 따라 안내를 생략하고 기록을 유지해요.";
			case "FEEDBACK_SUPPRESSED": return "이미 처리한 만남 안내예요. 반복 진동·깜빡임을 막았어요.";
			case "DUPLICATE_SUPPRESSED": return "같은 상대의 처리 중인 만남이 있어 기존 기록을 유지해요.";
			case "DURABILITY_RETRY": return "기존 만남 ID·증빙을 유지해 저장 응답을 다시 확인해요.";
			case "WAIT_RESPONSE": return "상대 응답을 기다리며 같은 데이터를 재시도해요. 매칭 실패로 처리하지 않아요.";
			case "PEER_LEFT": return "최근 상대 신호를 확인하지 못했어요. 대기 차례를 비우고 저장된 만남은 유지해요.";
			case "CONDITIONS_REJECTED": return "현재 참여 조건을 통과하지 못했어요. 새 만남·알림 없이 종료해요.";
			case "EVENT_EXPIRED": return "원래 만남 기한이 지났어요. 재시도로 기한을 늘리거나 다시 알리지 않아요.";
			case "RADIO_CHECK": return "진단용 통신을 확인해요. 만남 저장·진동과는 별도예요.";
			case "INCOMPATIBLE_PROTOCOL": return "상대와 처리 단계가 달라요. 모든 기기에 0.13 앱을 설치해주세요.";
			case "WAIT_MATCH_DATA": return "판정 결과를 먼저 받았어요. 성향 데이터 조립을 기다려요.";
			case "STALE_DECISION": return "이전 입력에 대한 판정은 적용하지 않고 현재 입력의 응답을 기다려요.";
			case "RECHECK_DEFERRED": return "현재 조건 또는 대기 순서가 준비되지 않아 재확인을 잠시 보류해요.";
			case "TURN_RETRY": return "응답 대기 차례를 다시 조정했어요. 저장된 기록은 유지해요.";
			case "PEER_ERROR": return "상대가 처리 오류를 알렸어요. 기록을 보존하고 재시도해요.";
			case "ERROR": return "처리를 완료하지 못했어요. 상세 원인은 JSON에서 확인할 수 있어요.";
			default: return "키링 실행 상태가 변경됐어요.";
		}
	}
}
