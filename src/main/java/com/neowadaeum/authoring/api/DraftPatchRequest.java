package com.neowadaeum.authoring.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 단계별 저장 요청 (§13.8).
 *
 * <p><b>{@code payload} 를 여기서 해석하지 않는다.</b> 단계별 입력을 그대로 나르며, 해석은
 * 검수(B-50, B-54)의 몫이다 — 미리 풀어 두면 <b>단계가 늘 때마다 계약이 는다.</b>
 *
 * <p><b>크기를 제한한다.</b> 원고는 사람이 쓰는 것이고, 제한이 없으면 한 요청이 저장소를 채운다.
 *
 * @param payload 단계별 입력 원문 (JSON 객체). 서버는 <b>형태만</b> 본다
 */
public record DraftPatchRequest(@Min(1) @Max(5) int step,
		@NotBlank @Size(max = 65536) String payload) {
}
