package com.neowadaeum.authoring.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.authoring.draft.StoryDraft;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이슈 #465 — <b>원고의 {@code payload} 는 객체로 오가고 객체로 나간다.</b>
 *
 * <p>계약({@code DraftPayload})도 컬럼({@code jsonb})도 객체였는데 <b>DTO 두 개만 {@code String}</b>
 * 이었다. 그래서 갓 만든 원고를 조회하면 {@code payload} 가 빈 객체가 아니라 <b>두 글자짜리
 * 문자열 {@code "{}"}</b> 로 나갔고, 계약대로 객체를 보낸 저장 요청은 역직렬화에서 죽어
 * <b>어느 칸이 문제인지도 말하지 못하는 400</b> 이 됐다 — 작품 만들기가 Step 1 에서 막혔다.
 *
 * <p><b>기존 통합 테스트는 이것을 잡지 못했다.</b> 픽스처가 계약이 아니라 <b>그때의 구현</b>에
 * 맞춰 {@code payload} 를 문자열로 보내고 있었기 때문이다 (#354 와 같은 모양의 실패다).
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class DraftJsonContractTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID AUTHOR = UUID.fromString("00000000-0000-4000-8000-000000000465");

	/**
	 * <b>갓 만든 원고에서 났다.</b> {@code String} 필드를 그대로 내보내면 Jackson 이 JSON 원문을
	 * 한 번 더 escape 한다.
	 */
	@Test
	void S465_a_new_draft_serializes_payload_as_an_object_not_a_string() {
		JsonNode response = JSON.valueToTree(DraftResponse.of(StoryDraft.start(AUTHOR, Instant.now())));

		assertThat(response.get("payload").isObject()).isTrue();
		assertThat(response.get("payload").isEmpty()).isTrue();
	}

	/** {@code findings} 도 같은 자리에 있었다. 계약은 배열이다 (R8.2). */
	@Test
	void S465_findings_serializes_as_an_array() {
		JsonNode response = JSON.valueToTree(DraftResponse.of(StoryDraft.start(AUTHOR, Instant.now())));

		assertThat(response.get("findings").isArray()).isTrue();
		assertThat(response.get("findings").isEmpty()).isTrue();
	}

	/** 계약대로 <b>객체</b>를 보낸 저장 요청이 받아들여진다. 이것이 Step 1 을 막고 있던 것이다. */
	@Test
	void S465_a_patch_body_carrying_an_object_payload_is_accepted() {
		DraftPatchRequest request = JSON.readValue(
				"{\"step\":2,\"payload\":{\"title\":\"봄의 학교\",\"genres\":[\"romance\"]}}",
				DraftPatchRequest.class);

		assertThat(request.payload().isObject()).isTrue();
		assertThat(violationsOf(request)).isEmpty();
		assertThat(JSON.readTree(request.payloadJson()).get("title").asString()).isEqualTo("봄의 학교");
	}

	/** 객체가 아닌 {@code payload} 는 거절된다 — 계약의 {@code DraftPayload} 는 객체다. */
	@Test
	void S465_a_payload_that_is_not_an_object_is_rejected() {
		DraftPatchRequest request = JSON.readValue("{\"step\":2,\"payload\":\"{}\"}",
				DraftPatchRequest.class);

		assertThat(violationsOf(request)).isNotEmpty();
	}

	/** 크기 상한은 {@code String} 이던 시절과 같다 — 한 요청이 저장소를 채우지 못한다. */
	@Test
	void S465_a_payload_over_the_size_limit_is_rejected() {
		String oversize = "{\"title\":\"" + "가".repeat(DraftPatchRequest.MAX_PAYLOAD_LENGTH) + "\"}";
		DraftPatchRequest request = new DraftPatchRequest(2, JSON.readTree(oversize));

		assertThat(violationsOf(request)).isNotEmpty();
	}

	/** <b>거절 사유에 보낸 값이 실리지 않는다</b> (S-3, S-7) — 이 문구가 그대로 응답에 나간다. */
	@Test
	void SEC3_the_rejection_message_does_not_carry_the_submitted_text() {
		DraftPatchRequest request = JSON.readValue("{\"step\":2,\"payload\":\"비밀 원고 문장\"}",
				DraftPatchRequest.class);

		assertThat(violationsOf(request)).allSatisfy(message ->
				assertThat(message).doesNotContain("비밀 원고 문장"));
	}

	private static java.util.List<String> violationsOf(DraftPatchRequest request) {
		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
			Validator validator = factory.getValidator();
			return validator.validate(request).stream()
					.map(jakarta.validation.ConstraintViolation::getMessage).toList();
		}
	}
}
