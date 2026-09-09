package com.neowadaeum.authoring.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/**
 * 단계별 저장 요청 (§13.8).
 *
 * <p><b>{@code payload} 를 여기서 해석하지 않는다.</b> 단계별 입력을 그대로 나르며, 해석은
 * 검수(B-50, B-54)의 몫이다 — 미리 풀어 두면 <b>단계가 늘 때마다 계약이 는다.</b>
 *
 * <p><b>{@code JsonNode} 로 받는다</b> (이슈 #465). 계약의 {@code DraftPayload} 는 객체이고
 * {@code String} 으로 받으면 <b>계약대로 보낸 요청이 역직렬화에서 죽는다</b> — 그 실패는
 * {@code @Valid} 앞에서 나므로 어느 칸이 문제인지도 말해 주지 못한다. 해석하지 않는다는 성질은
 * {@code JsonNode} 로도 그대로다.
 *
 * <p><b>크기를 제한한다.</b> 원고는 사람이 쓰는 것이고, 제한이 없으면 한 요청이 저장소를 채운다.
 *
 * @param payload 단계별 입력 원문 (JSON 객체). 서버는 <b>형태만</b> 본다
 */
public record DraftPatchRequest(@Min(1) @Max(5) int step, @NotNull JsonNode payload) {

	/** 직렬화했을 때의 상한. {@code String} 이던 시절의 {@code @Size(max = 65536)} 과 같은 값이다. */
	static final int MAX_PAYLOAD_LENGTH = 65536;

	/**
	 * 형태와 크기 (이슈 #465).
	 *
	 * <p><b>거절 사유에 보낸 값을 담지 않는다</b> (S-3, S-7) — 이 문구는 그대로 응답의
	 * {@code details.fields} 에 실린다.
	 */
	@AssertTrue(message = "payload must be a JSON object within the size limit")
	boolean isPayloadAcceptable() {
		if (this.payload == null) {
			return true;   // 빠진 것은 @NotNull 이 말한다. 둘이 함께 걸리면 한 칸에 사유가 둘 실린다
		}
		return this.payload.isObject() && this.payload.toString().length() <= MAX_PAYLOAD_LENGTH;
	}

	/** 저장 계층이 받는 모양. 원고는 {@code jsonb} 컬럼에 원문으로 남는다. */
	String payloadJson() {
		return this.payload.toString();
	}
}
