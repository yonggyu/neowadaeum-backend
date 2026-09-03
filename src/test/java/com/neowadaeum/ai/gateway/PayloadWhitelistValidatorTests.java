package com.neowadaeum.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ai.gateway.PayloadWhitelistValidator.PayloadWhitelistViolationException;
import com.neowadaeum.ai.provider.OutlineRequest;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.play.port.SummaryRequest;
import com.neowadaeum.ai.provider.TurnOnlyStoryProvider;
import com.neowadaeum.play.port.GenerationContext;
import com.neowadaeum.play.port.GenerationContexts;
import com.neowadaeum.play.port.TurnRequest;
import com.neowadaeum.play.port.GeneratedTurn;
import java.time.LocalDate;
import com.neowadaeum.play.port.GeneratedChoice;
import com.neowadaeum.play.port.GeneratedParagraph;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-19 — <b>회원 식별정보가 AI 요청에 실리면 요청이 중단된다</b> (I-3, §10.1-5).
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 *
 * <p><b>실제 페이로드에는 그런 필드가 없다.</b> {@link TurnRequest} 에 이메일을 넣는 코드는 컴파일되지
 * 않는다 — 그것이 구조적 보장이고, 여기서 검증하는 것은 <b>그 보장이 깨진 뒤에도 남는 방어선</b>이다.
 * 그래서 위반 사례는 "필드를 늘린 미래의 페이로드"를 세워 재현한다.
 */
class PayloadWhitelistValidatorTests {

	private static final UUID STORY_VERSION = UUID.fromString("22222222-2222-4222-8222-222222222222");

	private final PayloadWhitelistValidator validator = PayloadWhitelistValidator.forProviderPayloads();

	/**
	 * <b>§10.1-5 그 자체다</b> — 이메일 · 생년월일 · {@code playerRef} 가 들어가면 중단된다 (I-3).
	 */
	@Test
	void S10_1_5_member_identity_in_the_payload_aborts_the_request() {
		PayloadWhitelistValidator narrowed = new PayloadWhitelistValidator(
				Map.of(LeakyRequest.class, Set.of("storyVersionRef", "turnNo")));

		assertThatThrownBy(() -> narrowed.validate(new LeakyRequest(STORY_VERSION, 1,
				"user@example.test", LocalDate.of(2000, 1, 1), UUID.randomUUID())))
				.isInstanceOf(PayloadWhitelistViolationException.class)
				.hasMessageContaining("email")
				.hasMessageContaining("birthDate")
				.hasMessageContaining("playerRef");
	}

	/**
	 * <b>지우고 보내지 않는다 — Provider 가 아예 불리지 않는다</b> ({@code .claude/rules/ai.md}).
	 *
	 * <p>지워서 보내면 요청은 성공하고 유출 경로는 다음 필드를 기다리며 남는다. 호출 횟수 0 이
	 * 그것을 구분하는 유일한 증거다.
	 */
	@Test
	void I3_the_provider_is_never_called_when_the_payload_is_rejected() {
		AtomicInteger calls = new AtomicInteger();
		StoryProvider counting = new TurnOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "counting";
			}

			@Override
			public GeneratedTurn generateTurn(TurnRequest request) {
				calls.incrementAndGet();
				return new GeneratedTurn(List.of(GeneratedParagraph.narration("본문")),
					List.of(new GeneratedChoice(1, "선택")),
						JsonMapper.builder().build().readTree("{}"), false, null);
			}
		};

		// 선언을 좁혀 "선언에 없는 필드를 든 페이로드"를 만든다 — 실제 DTO 로는 재현할 수 없다.
		AiGateway gateway = new AiGateway(counting,
				new PayloadWhitelistValidator(Map.of(TurnRequest.class, Set.of("storyVersionRef"))));

		assertThatThrownBy(() -> gateway.generateTurn(TurnRequest.opening(STORY_VERSION, GenerationContexts.sample())))
				.isInstanceOf(PayloadWhitelistViolationException.class);
		assertThat(calls).hasValue(0);
	}

	/**
	 * <b>화이트리스트이지 금지 목록이 아니다.</b>
	 *
	 * <p>{@code email} 만 막으면 {@code contactMail} 이 그대로 나간다. 처음 보는 이름이 막히는지가
	 * 이 방식의 값이다.
	 */
	@Test
	void I3_a_name_nobody_anticipated_is_blocked_too() {
		PayloadWhitelistValidator narrowed = new PayloadWhitelistValidator(
				Map.of(UnexpectedNameRequest.class, Set.of("storyVersionRef")));

		assertThatThrownBy(() -> narrowed.validate(new UnexpectedNameRequest(STORY_VERSION, "user@example.test")))
				.isInstanceOf(PayloadWhitelistViolationException.class)
				.hasMessageContaining("contactMail");
	}

	/** 한 겹만 보면 안쪽에 담아 보내면 그만이다. 중첩 객체와 배열 안쪽까지 내려간다. */
	@Test
	void I3_nested_objects_and_arrays_are_inspected() {
		PayloadWhitelistValidator narrowed = new PayloadWhitelistValidator(
				Map.of(NestedRequest.class, Set.of("entries", "turnNo")));

		assertThatThrownBy(() -> narrowed.validate(
				new NestedRequest(List.of(new NestedRequest.Entry(1, UUID.randomUUID())))))
				.isInstanceOf(PayloadWhitelistViolationException.class)
				.hasMessageContaining("playerRef");
	}

	/** 선언이 없는 타입은 통과시키지 않는다. 통과시키면 화이트리스트에 구멍이 하나 생긴 것과 같다. */
	@Test
	void I3_a_payload_type_without_a_declaration_is_rejected() {
		assertThatThrownBy(() -> this.validator.validate(new UnexpectedNameRequest(STORY_VERSION, "x")))
				.isInstanceOf(PayloadWhitelistViolationException.class)
				.hasMessageContaining("no field whitelist");
	}

	/**
	 * <b>S-3 — 예외 메시지에 값이 없다.</b>
	 *
	 * <p>{@code GlobalExceptionHandler} 의 폴백이 예외를 통째로 로그에 남긴다. 값을 메시지에 넣으면
	 * 막으려던 것이 로그로 나간다.
	 */
	@Test
	void SEC3_the_violation_message_carries_field_names_but_no_values() {
		PayloadWhitelistValidator narrowed = new PayloadWhitelistValidator(
				Map.of(LeakyRequest.class, Set.of("storyVersionRef", "turnNo")));
		String secret = "user@example.test";

		assertThatThrownBy(() -> narrowed.validate(new LeakyRequest(STORY_VERSION, 1, secret,
				LocalDate.of(2000, 1, 1), UUID.randomUUID())))
				.hasMessageContaining("email")
				.hasMessageNotContaining(secret)
				.hasMessageNotContaining("2000-01-01");
	}

	/** 실제로 나가는 세 페이로드는 그대로 통과한다. 방어선이 정상 경로를 막으면 우회가 생긴다. */
	@Test
	void I3_the_real_payloads_pass() {
		this.validator.validate(TurnRequest.opening(STORY_VERSION, GenerationContexts.sample()));
		this.validator.validate(TurnRequest.of(STORY_VERSION, 3, 2, GenerationContexts.sample()));
		this.validator.validate(new SummaryRequest("직전 요약",
				List.of(new SummaryRequest.TurnDigest(1, "선택", "요지")), 600));
		this.validator.validate(new OutlineRequest("세계관", 5, 3));
	}

	/**
	 * <b>페이로드에 필드가 늘면 선언도 함께 고쳐야 한다.</b>
	 *
	 * <p>이 테스트가 이 작업의 실질이다. 선언이 DTO 를 따라가지 못하면 새 필드가 검사 없이 나가고,
	 * 그것은 조용하다. 어긋나는 순간 여기서 빨갛게 남는다.
	 */
	@Test
	void I3_the_declaration_does_not_drift_from_the_payload_records() {
		// populated() 다 — 목록이 비어 있으면 중첩 안쪽 이름이 직렬화 결과에 나타나지 않고,
		// 그러면 이 검사가 그 이름들을 그냥 지나친다 (B-22).
		assertThat(this.validator.declaredFieldsFor(TurnRequest.class))
				.containsExactlyInAnyOrderElementsOf(serializedFieldNames(
						TurnRequest.opening(STORY_VERSION, GenerationContexts.populated())));
		assertThat(this.validator.declaredFieldsFor(OutlineRequest.class))
				.containsExactlyInAnyOrderElementsOf(serializedFieldNames(new OutlineRequest("세계관", 5, 3)));
		assertThat(this.validator.declaredFieldsFor(SummaryRequest.class))
				.containsExactlyInAnyOrderElementsOf(serializedFieldNames(new SummaryRequest("요약",
						List.of(new SummaryRequest.TurnDigest(1, "선택", "요지")), 600)));
	}

	// ── gameState 불투명 서브트리 (#96) ───────────────────────

	/**
	 * <b>안쪽을 안 본다고 아무거나 통과시키지는 않는다</b> (I-3, #96).
	 *
	 * <p>{@code gameState} 는 작품이 {@code state_schema} 로 정한 자유 형태라 허용 목록을 만들 수
	 * 없다. 열거할 수 없는 것은 <b>작품 키</b>이고, 절대 나타나면 안 되는 것은 유한하다.
	 */
	@Test
	void I3_member_identity_inside_the_opaque_subtree_is_rejected() {
		assertThatThrownBy(() -> this.validator.validate(withGameState(
				"{\"affinity\":{\"yuna\":18},\"playerRef\":\"11111111-1111-4111-8111-111111111111\"}")))
				.isInstanceOf(PayloadWhitelistViolationException.class)
				.hasMessageContaining("playerRef");
	}

	/** <b>중첩 안쪽에 숨겨도 걸린다.</b> 한 겹만 보면 안쪽에 담아 보내면 그만이다. */
	@Test
	void I3_member_identity_nested_deeper_in_the_opaque_subtree_is_rejected() {
		assertThatThrownBy(() -> this.validator.validate(withGameState(
				"{\"profile\":{\"inner\":{\"email\":\"a@b.c\"}}}")))
				.isInstanceOf(PayloadWhitelistViolationException.class)
				.hasMessageContaining("email");
	}

	/**
	 * <b>정상 GameState 는 그대로 통과한다.</b> 방어선이 정상 경로를 막으면 우회가 생긴다.
	 *
	 * <p>작품이 정한 키({@code affinity.yuna})는 선언에 없지만 막히지 않아야 한다 — 그것이 이
	 * 예외가 존재하는 이유다.
	 */
	@Test
	void R4_1_a_normal_game_state_passes_with_work_defined_keys() {
		assertThatCode(() -> this.validator.validate(withGameState(
				"{\"chapter\":2,\"turn\":7,\"location\":\"강의실\",\"affinity\":{\"yuna\":18},"
						+ "\"flags\":[\"met_yuna\"],\"inventory\":[]}")))
				.doesNotThrowAnyException();
	}

	private static TurnRequest withGameState(String gameStateJson) {
		GenerationContext context = new GenerationContext("세계관", java.util.List.of(),
				JsonMapper.builder().build().readTree(gameStateJson), null, java.util.List.of(), null);
		return TurnRequest.opening(STORY_VERSION, context);
	}

	/** 직렬화 결과에 실제로 나타나는 이름. 중첩 안쪽 이름까지 모은다. */
	private static Set<String> serializedFieldNames(Object payload) {
		java.util.Set<String> names = new java.util.LinkedHashSet<>();
		collect(JsonMapper.builder().build().valueToTree(payload), names);
		return names;
	}

	private static void collect(tools.jackson.databind.JsonNode node, Set<String> names) {
		if (node.isObject()) {
			node.properties().forEach(property -> {
				names.add(property.getKey());
				// 검증기가 안 내려가는 자리는 여기서도 안 내려간다 — 안 그러면 GameState 의
				// 데이터 키(작품마다 다르다)가 "선언되어야 할 이름"으로 잡힌다 (B-22).
				if (!"gameState".equals(property.getKey())) {
					collect(property.getValue(), names);
				}
			});
		}
		else if (node.isArray()) {
			node.forEach(element -> collect(element, names));
		}
	}

	/** 회원 식별정보가 붙어 버린 미래의 턴 요청. §10.1-5 가 말하는 그 상황이다. */
	private record LeakyRequest(UUID storyVersionRef, int turnNo, String email, LocalDate birthDate,
			UUID playerRef) {
	}

	/** 금지 목록에는 없지만 화이트리스트에도 없는 이름. */
	private record UnexpectedNameRequest(UUID storyVersionRef, String contactMail) {
	}

	/** 안쪽에 담아 보내려는 시도. */
	private record NestedRequest(List<Entry> entries) {

		private record Entry(int turnNo, UUID playerRef) {
		}
	}

	/**
	 * <b>판정 페이로드도 선언 대상이다</b> (B-30, I-3).
	 *
	 * <p>선언이 없는 타입은 통과하지 못한다 — 새 요청 타입이 검증을 <b>지나가는</b> 것이 아니라
	 * <b>막히는</b> 쪽이 기본값이어야 한다.
	 */
	@Test
	void I3_the_classification_payload_is_declared_and_carries_only_texts() {
		PayloadWhitelistValidator validator = PayloadWhitelistValidator.forProviderPayloads();

		validator.validate(new com.neowadaeum.common.spi.SafetyClassificationRequest(
				java.util.List.of("판정할 문장")));
	}
}
