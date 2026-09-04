package com.neowadaeum.ai.gateway;

import com.neowadaeum.ai.provider.OutlineRequest;
import com.neowadaeum.play.port.SummaryRequest;
import com.neowadaeum.common.spi.SafetyClassificationRequest;
import com.neowadaeum.play.port.TurnRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 요청 페이로드의 필드 화이트리스트 검증 (I-3, §10.1-5, B-19).
 *
 * <p><b>이미 구조적 보장이 있는데 왜 또 검사하는가.</b> {@code ai} 모듈은 도메인 엔티티를 모르고,
 * {@link TurnRequest} 에는 회원 식별정보를 담을 <b>필드 자체가 없다.</b> 그것은 "오늘의 DTO 는
 * 안전하다"는 증명이지 <b>"내일 누가 필드를 늘려도 새어 나가지 않는다"</b>는 증명이 아니다. 구조는
 * 컴파일 시점의 보장이고 이 검증기는 런타임의 보장이며, 둘 중 하나가 다른 하나를 대체하지 않는다.
 *
 * <p><b>화이트리스트다. 금지 목록이 아니다.</b> 이메일·생년월일 같은 <b>아는 이름</b>만 막으면
 * {@code contactMail} 처럼 처음 보는 이름이 그대로 나간다. 여기서는 <b>허용한 이름만</b> 지나가고,
 * 그래서 페이로드에 필드를 늘리면 이 선언을 함께 고치기 전까지 테스트가 빨갛게 남는다 — 그것이
 * 이 클래스의 목적이다.
 *
 * <p><b>위반이면 중단한다. 지우고 보내지 않는다</b> ({@code .claude/rules/ai.md}). 지워서 보내면
 * 요청은 성공하고 유출 경로는 다음 필드를 기다리며 남는다.
 *
 * <p><b>검사 대상은 직렬화 결과다.</b> 리플렉션으로 레코드 컴포넌트를 세지 않는 이유는, 실제로
 * 나가는 것이 필드가 아니라 <b>직렬화된 JSON</b> 이기 때문이다 — 이름을 바꾸는 애노테이션이 붙으면
 * 둘이 갈라진다.
 *
 * <p><b>S-3 — 값을 로그로 흘리지 않는다.</b> 예외 메시지에 담기는 것은 <b>필드 이름까지</b>다.
 * {@code GlobalExceptionHandler} 의 폴백이 예외를 통째로 로그에 남기므로, 값을 메시지에 넣으면
 * 막으려던 것이 로그로 나간다.
 */
public class PayloadWhitelistValidator {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * <b>값 안쪽으로 내려가지 않는 필드</b> (B-22).
	 *
	 * <p>{@code gameState} 는 작품이 정한 {@code state_schema} 대로 만들어진 자유 형태 JSON 이다
	 * (R4.1). 키가 작품마다 다르므로 <b>정적 선언으로 열거할 수 없고</b>, 그렇다고 통과시키면
	 * 화이트리스트가 아니라 통과표가 된다.
	 *
	 * <p><b>여기에 이름을 넣는 것은 예외를 선언하는 일이다.</b> 정당화는 하나뿐이다 — 그 값이
	 * <b>서버가 만든 것</b>이고 <b>이미 다른 화이트리스트를 통과했다</b>는 것. GameState 는 AI 가
	 * 준 델타를 {@code state_schema} 로 거르고 clamp 한 결과이며 (R4.1, R4.2), 회원 식별정보가
	 * 들어갈 경로가 없다. 그 성질이 깨지면 이 예외도 깨진다.
	 *
	 * <p>이름 자체는 여전히 검사한다 — 막는 것은 <b>모르는 이름이 나가는 것</b>이고,
	 * {@code gameState} 라는 이름은 선언에 있다. 안 보는 것은 그 <b>안쪽</b>뿐이다.
	 *
	 * <p><b>그래도 완전히 안 보지는 않는다</b> (#96). 안쪽에는 {@link #FORBIDDEN_IDENTITY_FIELDS}
	 * 가 걸린다 — 아래 상수의 근거를 보라.
	 */
	private static final Set<String> OPAQUE_FIELDS = Set.of("gameState");

	/**
	 * <b>불투명 서브트리 안쪽에서도 절대 나타나면 안 되는 이름</b> (I-3, #96).
	 *
	 * <p><b>왜 거부 목록인가.</b> 이 프로젝트는 금지 목록을 쓰지 않는다 — 처음 보는 이름이 그대로
	 * 나가기 때문이다. 그런데 {@code gameState} 안쪽에서는 <b>허용 목록을 만들 수가 없다.</b>
	 * 수치 그룹 이름과 그 안쪽 키는 작품이 {@code state_schema} 로 정하므로(R4.1) 정적으로 열거할
	 * 대상이 아니다.
	 *
	 * <p><b>열거할 수 없는 것은 작품 키이고, 절대 나타나면 안 되는 것은 유한하다.</b> I-3 이
	 * 지목하는 회원 식별정보가 그것이다. 화이트리스트를 못 쓰는 자리에 <b>거부 목록이라도</b>
	 * 두는 것이 아무것도 안 보는 것보다 낫다.
	 *
	 * <p><b>이것이 I-3 의 일차 방어선이 아니다.</b> 일차 방어선은 GameState 가 엔진 출력만 담는다는
	 * 성질이며(R4.1, R4.2) 그것은 {@code play} 쪽 테스트가 지킨다. 여기는 그 성질이 깨졌을 때
	 * <b>어댑터에 닿기 전에</b> 잡는 마지막 자리다.
	 *
	 * <p><b>한계 — 이름만 본다.</b> 값 안쪽에 식별정보가 들어간 경우(예: {@code flags} 에 이메일
	 * 문자열)는 잡지 못한다. 그 경로가 생기려면 {@code state_schema} 가 그것을 허용해야 하므로
	 * UGC 검수(§13-12)의 소관이다.
	 */
	private static final Set<String> FORBIDDEN_IDENTITY_FIELDS = Set.of(
			"playerRef", "player_ref", "userId", "user_id", "email", "birthDate", "birth_date",
			"socialId", "social_id", "ip", "ipAddress");

	private final Map<Class<?>, Set<String>> allowedFields;

	public PayloadWhitelistValidator(Map<Class<?>, Set<String>> allowedFields) {
		this.allowedFields = Map.copyOf(allowedFields);
	}

	/**
	 * Provider 로 나가는 페이로드의 선언.
	 *
	 * <p><b>중첩 깊이별로 나누지 않고 타입마다 한 벌로 둔다.</b> 안쪽 객체가 바깥과 같은 이름을 쓰면
	 * 통과하지만, 그 이름 역시 <b>허용 목록에 있는 이름</b>이다 — 막으려는 것은 "모르는 이름이 나가는
	 * 것"이고 그 성질은 유지된다. 깊이별 선언은 이 표를 세 배로 만들고, 커지는 표는 갱신되지 않는다.
	 */
	public static PayloadWhitelistValidator forProviderPayloads() {
		return new PayloadWhitelistValidator(Map.of(
				// B-22 로 늘어난 이름들이 여기 있다. TurnRequest 가 참조만 나르던 시절에는 셋이었다.
				// 늘어난 것은 전부 작품 데이터와 서버가 만든 상태다 — 회원 식별정보는 없다 (I-3).
				// stateVocabulary 가 나르는 것은 작품이 선언한 이름이다 (§13-76). 값이 아니라 이름이며,
				// 그 이름은 이미 state_schema 로 발행돼 매 턴 gameState 의 키로도 나가고 있다.
				TurnRequest.class, Set.of("storyVersionRef", "turnNo", "chosenChoiceOrder", "freeInput", "context",
						"worldPrompt", "characters", "name", "persona", "gameState", "stateVocabulary",
						"numerics", "flags", "inventory", "summary",
						"recentTurns", "chosenChoiceText", "paragraphs", "paragraphsDigest", "userAction"),
				SummaryRequest.class, Set.of("previousSummary", "turns", "maxTokens",
						"turnNo", "chosenChoiceText", "paragraphsDigest"),
				OutlineRequest.class, Set.of("worldPrompt", "chapterCount", "endingCount"),
				// B-30 — 판정에 넘어가는 것은 판정 대상 텍스트뿐이다. 세션·작품·사용자에 관한
				// 이름은 하나도 없고, 늘리려면 이 선언을 먼저 고쳐야 한다 (I-3).
				SafetyClassificationRequest.class, Set.of("texts")));
	}

	/**
	 * 페이로드가 선언된 필드만 담고 있는지 확인한다.
	 *
	 * @throws PayloadWhitelistViolationException 선언에 없는 필드가 있거나, 선언 자체가 없는 타입일 때
	 */
	public void validate(Object payload) {
		if (payload == null) {
			throw new IllegalArgumentException("payload is required");
		}

		Set<String> allowed = this.allowedFields.get(payload.getClass());
		if (allowed == null) {
			// 모르는 타입을 통과시키면 화이트리스트에 구멍이 하나 생긴 것과 같다.
			throw PayloadWhitelistViolationException.unknownPayloadType(payload.getClass());
		}

		List<String> offending = new ArrayList<>();
		collect(JSON.valueToTree(payload), allowed, offending);

		if (!offending.isEmpty()) {
			throw PayloadWhitelistViolationException.disallowedFields(payload.getClass(), offending);
		}
	}

	/**
	 * 선언된 필드 이름. <b>테스트가 선언과 실제 DTO 의 어긋남을 잡는 데 쓴다</b> — 페이로드에 필드가
	 * 늘었는데 선언이 그대로면 그 사실이 드러나야 한다.
	 */
	Set<String> declaredFieldsFor(Class<?> type) {
		return this.allowedFields.getOrDefault(type, Set.of());
	}

	/**
	 * 불투명 서브트리 안쪽에서 {@link #FORBIDDEN_IDENTITY_FIELDS} 를 찾는다 (#96).
	 *
	 * <p>허용 목록이 아니라 거부 목록인 이유는 그 상수의 주석에 있다.
	 */
	private static void collectForbidden(JsonNode node, List<String> offending) {
		if (node.isObject()) {
			for (Map.Entry<String, JsonNode> property : node.properties()) {
				if (FORBIDDEN_IDENTITY_FIELDS.contains(property.getKey())) {
					offending.add(property.getKey());
				}
				collectForbidden(property.getValue(), offending);
			}
		}
		else if (node.isArray()) {
			for (JsonNode element : node) {
				collectForbidden(element, offending);
			}
		}
	}

	/**
	 * 중첩 객체와 배열 안쪽까지 내려간다. 한 겹만 보면 안쪽에 담아 보내면 그만이다.
	 *
	 * <p>{@link #OPAQUE_FIELDS} 에 있는 이름은 <b>이름만 보고 값 안쪽은 보지 않는다.</b> 근거는
	 * 그 상수의 주석에 있다.
	 */
	private static void collect(JsonNode node, Set<String> allowed, List<String> offending) {
		if (node.isObject()) {
			for (Map.Entry<String, JsonNode> property : node.properties()) {
				if (!allowed.contains(property.getKey())) {
					offending.add(property.getKey());
				}
				if (OPAQUE_FIELDS.contains(property.getKey())) {
					// 안쪽은 허용 목록으로 덮을 수 없다. 대신 절대 나타나면 안 되는 이름을 본다 (#96).
					collectForbidden(property.getValue(), offending);
					continue;
				}
				collect(property.getValue(), allowed, offending);
			}
		}
		else if (node.isArray()) {
			for (JsonNode element : node) {
				collect(element, allowed, offending);
			}
		}
	}

	/**
	 * 화이트리스트 위반 (I-3).
	 *
	 * <p><b>사용자에게는 이 사실이 드러나지 않는다.</b> {@code GlobalExceptionHandler} 의 폴백이
	 * {@code INTERNAL_ERROR} 로 바꾼다 — I-3 위반은 사용자의 입력 오류가 아니라 <b>서버의 결함</b>이며,
	 * 무엇이 걸렸는지 알려 주면 그것이 통과 조건을 알려 주는 것이 된다 (S-6).
	 *
	 * <p>메시지에 <b>값을 담지 않는다</b> (S-3).
	 */
	public static class PayloadWhitelistViolationException extends RuntimeException {

		private PayloadWhitelistViolationException(String message) {
			super(message);
		}

		static PayloadWhitelistViolationException unknownPayloadType(Class<?> type) {
			return new PayloadWhitelistViolationException(
					"no field whitelist is declared for " + type.getSimpleName());
		}

		static PayloadWhitelistViolationException disallowedFields(Class<?> type, List<String> fields) {
			return new PayloadWhitelistViolationException(
					"%s carries fields that are not whitelisted: %s".formatted(type.getSimpleName(), fields));
		}
	}
}
