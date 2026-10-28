package com.e106.tingle.global.jpa;

import java.util.Locale;
import java.util.Map;

/** ERD enum 이름은 Java 클래스 이름과 독립적이다. 생성·리터럴·형변환에서 같은 이름을 쓴다. */
final class CanonicalEnumNames {
	private static final Map<String, String> NAMES = Map.ofEntries(
		Map.entry("SocialProvider", "auth_provider"), Map.entry("PushPlatform", "device_platform"),
		Map.entry("Gender", "gender"), Map.entry("SmokingStatus", "smoking_status"),
		Map.entry("DrinkingFrequency", "drinking_frequency"), Map.entry("Religion", "religion"),
		Map.entry("AnalysisStatus", "analysis_status"), Map.entry("KeyringState", "keyring_state"),
		Map.entry("MatchingStatus", "matching_status"), Map.entry("ProposalResponse", "proposal_response"),
		Map.entry("ChatCloseReason", "chat_close_reason"), Map.entry("ChatReportReason", "chat_report_reason"),
		Map.entry("ChatReportStatus", "chat_report_status"), Map.entry("SafetyNoticeType", "safety_notice_type"),
		Map.entry("OperatorAuditKind", "operator_audit_kind"), Map.entry("OperatorAction", "operator_action"),
		Map.entry("OperatorResult", "operator_result"), Map.entry("ProfileCorrectionField", "profile_correction_field"),
		Map.entry("ServiceCaseEvent", "service_case_event"), Map.entry("ServiceCaseKind", "service_case_kind"),
		Map.entry("ServiceCaseStatus", "service_case_status"));

	private CanonicalEnumNames() {
	}

	static String sqlName(String javaName) {
		String name = NAMES.getOrDefault(javaName, javaName.toLowerCase(Locale.ROOT));
		if (!name.matches("[a-z][a-z0-9_]*")) {
			throw new IllegalArgumentException("Enum SQL 이름을 명시적으로 매핑해야 합니다: " + javaName);
		}
		return name;
	}
}
