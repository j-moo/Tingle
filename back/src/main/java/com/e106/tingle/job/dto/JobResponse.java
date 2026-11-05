package com.e106.tingle.job.dto;

/**
 * 본인 비동기 작업 조회 응답. 종류와 상관없이 같은 7개 항목이다.
 * 결과는 kind 별 결과 API(resourceId)로 본다. 작업 입력·토큰·원문·내부 오류는 담지 않는다.
 *
 * @param jobId 종류-번호 형식의 공개 작업 번호(예: ANALYSIS-12)
 * @param kind ANALYSIS · MATCH_EXPLANATION · ICEBREAKERS
 * @param status PENDING(접수) · RUNNING(실행 중) · SUCCEEDED · INSUFFICIENT(분석만, 정보 부족) · FAILED
 * @param resourceId 결과 자원 번호. 분석 resultId, 설명 proposalId, 시작 질문 chatRoomId
 * @param errorCode FAILED 일 때만 공개 오류 코드(SERVICE_UNAVAILABLE), 아니면 null
 * @param retryable 같은 요청 API를 다시 부르면 새로 시도하는지
 * @param pollAfterSeconds 다음 조회까지 기다릴 초. 끝난 작업은 0(더 조회하지 않는다)
 */
public record JobResponse(String jobId, String kind, String status, long resourceId, String errorCode,
	boolean retryable, int pollAfterSeconds) {
}
