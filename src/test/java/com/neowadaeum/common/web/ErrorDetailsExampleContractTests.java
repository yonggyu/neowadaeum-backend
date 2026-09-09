package com.neowadaeum.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.neowadaeum.authoring.UgcLimitProperties;
import com.neowadaeum.authoring.draft.DraftScaleGate;
import com.neowadaeum.authoring.draft.DraftStateSchema;
import com.neowadaeum.authoring.draft.DraftStoryDefinition;
import com.neowadaeum.authoring.draft.DraftVocabularyGate;
import com.neowadaeum.authoring.review.StoryReviewRepository;
import com.neowadaeum.authoring.review.StoryReviewTimeline;
import com.neowadaeum.authoring.review.StoryVisibilityService;
import com.neowadaeum.authoring.review.Visibility;
import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.catalog.query.StoryCatalogFacade;
import com.neowadaeum.catalog.query.StoryStatusView;
import com.neowadaeum.catalog.query.StoryVersionFacade;
import com.neowadaeum.common.error.GlobalExceptionHandler;
import com.neowadaeum.common.spi.AiNotice;
import com.neowadaeum.common.spi.AiNoticeQuery;
import com.neowadaeum.common.spi.StateVocabularyBudget;
import com.neowadaeum.common.support.RateLimitProperties;
import com.neowadaeum.common.support.RateLimiter;
import com.neowadaeum.play.api.AiNoticeText;
import com.neowadaeum.play.api.PlayTurnService;
import com.neowadaeum.play.api.TurnGuards;
import com.neowadaeum.play.api.TurnRequestBody;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SafetyVerdict;
import com.neowadaeum.play.domain.Turn;
import com.neowadaeum.play.orchestrator.TurnOutcome;
import com.neowadaeum.play.orchestrator.TurnPipeline;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

/**
 * #471 — <b>계약의 에러 예제가 실제 응답과 같은 모양인가.</b> #466 이 {@code ValidationError} 하나에
 * 세운 대조를 <b>예제 전부</b>로 넓힌다.
 *
 * <p>{@code Error.details} 는 {@code additionalProperties: true} 라 <b>어떤 모양이든 스키마를
 * 통과한다.</b> {@code OpenApiContractTests} 는 필드 선언을 보지만 예제의 <b>형태</b>는 보지 않았고,
 * 그래서 예제가 맵이고 구현이 배열인 채로 양쪽 CI 가 초록이었다 (#466 → 표류 68).
 * <b>계약을 읽는 사람은 예제를 읽는다.</b>
 *
 * <p>여기서 세우는 것은 둘이다.
 *
 * <ol>
 * <li>구동할 수 있는 코드는 <b>살아 있는 응답</b>과 예제의 {@code details} 모양을 대조한다 —
 * 값이 아니라 <b>키 경로와 타입</b>을 본다. 어느 쪽에 키가 하나 늘어도 걸린다</li>
 * <li>계약이 {@code details} 를 <b>그린</b> 예제는 <b>빠짐없이</b> 살아 있는 응답에 묶여 있다.
 * 새로 그린 예제에 구동 경로를 달지 않으면 이 검사가 먼저 깨진다 — 손으로 적은 목록이 아니라
 * 계약 파일에서 세기 때문이다</li>
 * </ol>
 *
 * <p><b>모든 코드를 구동하지는 않는다.</b> 외부 상태·관리자 게이트·인증 흐름 뒤에 있는 코드는
 * 살아 있는 응답을 만드는 값이 대조가 주는 값보다 크다. 지금 대조하지 않는 코드는
 * {@code details} 를 <b>빈 객체로</b> 그린 것들이며(2 가 그것을 강제한다), 그중 어느 하나라도
 * {@code details} 를 담기 시작하면 그때 구동 경로와 함께 여기 들어온다.
 *
 * <p>컨테이너를 띄우지 않는다 (ADR-0001). 대조를 위해 프로덕션 코드를 바꾸지 않는다 — 실제로
 * 예외를 던지는 <b>프로덕션 경로</b>를 부르고, 그 예외를 {@link GlobalExceptionHandler} 가
 * 내보낸 <b>응답 본문</b>에서 {@code details} 를 읽는다.
 */
class ErrorDetailsExampleContractTests {

	private static final Map<String, Object> SPEC = loadSpec();

	private static final UUID PLAYER_REF = UUID.fromString("22222222-2222-4222-8222-000000000001");

	private static final UUID SESSION_ID = UUID.fromString("33333333-3333-4333-8333-000000000001");

	private static final UUID STORY_ID = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final UUID VERSION_ID = UUID.fromString("11111111-1111-4111-8111-000000000009");

	private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

	private static final String CHOICE_ID = "1-1-abcdef";

	private static final String PARAGRAPHS = """
			[{"type":"NARRATION","text":"문 앞이다."}]""";

	private static final String CHOICES = """
			[{"choiceId":"1-1-abcdef","order":1,"text":"문을 연다","disabled":false}]""";

	// ── 1. 예제 ↔ 살아 있는 응답 ─────────────────────────────

	/**
	 * §9.1 — <b>실제 응답</b>의 {@code details} 모양을 계약이 그린다.
	 *
	 * <p>비교 대상은 값이 아니라 <b>키 경로와 타입</b>이다. 값까지 묶으면 예제가 고정 데이터가 되어
	 * 계약이 읽히지 않는 문서가 되고, 키만 보면 맵과 배열이 구분되지 않는다 — 그것이 #466 이었다.
	 *
	 * <p><b>코드 하나에 모양이 여럿일 수 있다</b> (§13-96, #478). 예전에는 코드당 구동 경로가
	 * 하나라 <b>한 코드가 여러 모양으로 나가는 것</b>을 잡지 못했다 — 예제 하나와 응답 하나가
	 * 맞으면 통과했다. 그래서 이 검사는 <b>살아 있는 모양이 예제들 중 하나로 그려져 있는가</b>를
	 * 보고, 반대 방향(그린 모양은 전부 구동된다)은 아래 검사가 본다. 둘이 합쳐 같음이 된다.
	 *
	 * <p>코드 자체도 함께 확인한다. 구동 경로가 언젠가 다른 코드로 흘러가면, 빈 {@code details}
	 * 끼리 비교하며 <b>아무것도 지키지 않는 검사</b>가 되기 때문이다.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("livingErrorResponses")
	void S9_1_error_example_details_match_the_real_response(String label, String code, LiveErrorResponse live)
			throws Exception {
		String body = live.body();

		assertThat(JsonPath.<String>read(body, "$.error"))
				.as("구동 경로가 %s 가 아닌 다른 코드를 내보냈다 — 이 검사가 지키는 것이 없어진다", code)
				.isEqualTo(code);

		Set<String> liveShape = shapeOf(JsonPath.read(body, "$.details"));
		List<ErrorExample> examples = examplesOf(code);

		assertThat(examples).as("계약에 %s 예제가 없다 (#432 — 어떤 코드가 오는지 계약이 말한다)", code).isNotEmpty();
		assertThat(examples.stream().map(example -> shapeOf(example.details())).toList())
				.as("살아 있는 응답의 details 모양을 계약이 그리지 않았다 (#471, §13-96) — %s", label)
				.contains(liveShape);
	}

	/**
	 * §9.1 — <b>{@code details} 를 그린 예제는 전부 살아 있는 응답에 묶여 있다.</b>
	 *
	 * <p>이것이 이 파일을 <b>일반화</b>로 만드는 자리다. 대조 목록을 손으로 적어 두면 다음에 예제가
	 * 하나 늘 때 아무도 그것을 대조하지 않는다 — {@code ValidationError} 만 묶여 있던 동안 나머지
	 * 셋이 그랬다. 대상은 계약 파일에서 센다.
	 *
	 * <p>빈 {@code details} 를 그린 예제는 대상이 아니다. 담기 시작하는 순간 대상이 되고, 그때
	 * 구동 경로가 없으면 여기서 걸린다.
	 *
	 * <p><b>코드가 아니라 모양 단위로 센다</b> (§13-96, #478). 코드로만 세면 이미 구동 경로가
	 * 있는 코드에 <b>다른 모양의 예제</b>를 하나 더 그려도 통과한다 — 그것이 이 이슈에서 실제로
	 * 늘어난 것이다.
	 */
	@Test
	void S9_1_every_example_that_draws_details_is_pinned_to_a_live_response() throws Exception {
		Map<String, Set<Set<String>>> probed = probedShapes();

		Set<String> unpinned = new TreeSet<>();
		for (ErrorExample example : errorExamples()) {
			Set<String> shape = shapeOf(example.details());
			if (!shape.isEmpty() && !probed.getOrDefault(example.code(), Set.of()).contains(shape)) {
				unpinned.add(example.location());
			}
		}

		assertThat(unpinned)
				.as("details 를 그린 예제인데 살아 있는 응답과 대조되지 않는다 (#471). "
						+ "livingErrorResponses 에 구동 경로를 더한다")
				.isEmpty();
	}

	/**
	 * 살아 있는 응답을 만들 수 있는 코드와 그 구동 방법.
	 *
	 * <p><b>프로덕션 경로를 부른다.</b> 예외를 테스트가 직접 만들면 대조 대상이 계약과 테스트가 되어,
	 * 정작 구현이 갈라졌을 때 아무 일도 일어나지 않는다.
	 *
	 * <p><b>{@code VALIDATION_ERROR} 는 넷이다</b> (§13-96) — 한 코드가 자리에 따라 다른 모양으로
	 * 나가고, 그 모양들이 계약에 전부 그려져 있는지가 이 파일이 지키는 것이다.
	 */
	static Stream<Arguments> livingErrorResponses() {
		return Stream.of(
				probe("VALIDATION_ERROR", "본문이 모델로 풀리지 못했다",
						ErrorDetailsExampleContractTests::unreadableBody),
				probe("VALIDATION_ERROR", "목록 개수가 상한을 넘었다",
						() -> responseOf(() -> scaleGate().verify(oversizedDraft()))),
				probe("VALIDATION_ERROR", "승인되지 않은 작품의 공개 범위를 바꾼다",
						() -> responseOf(() -> visibilityServiceOf("in_review")
								.change(PLAYER_REF, STORY_ID, Visibility.PUBLIC))),
				probe("VALIDATION_ERROR", "선언한 이름이 프롬프트 예산을 넘었다",
						() -> responseOf(() -> overBudgetVocabularyGate()
								.verify(new DraftStateSchema(Set.of("yuna"), Set.of("met_yuna"))))),
				probe("TURN_CONFLICT", "직전 턴 번호가 아니다",
						() -> responseOf(() -> turnServiceReadyForTurnTwo(mock(TurnPipeline.class))
								.advance(PLAYER_REF, SESSION_ID, choice(0)))),
				probe("SAFETY_BLOCKED", "L2 가 막았다",
						() -> responseOf(() -> turnServiceReadyForTurnTwo(blockingPipeline())
								.advance(PLAYER_REF, SESSION_ID, choice(1)))),
				probe("CONCURRENT_GENERATION", "생성 락을 다른 요청이 잡고 있다",
						() -> responseOf(() -> guardsWithHeldLock().acquireGenerationLock(PLAYER_REF))),
				probe("RETRY_COOLDOWN", "연속 실패가 한도에 닿았다",
						() -> responseOf(() -> guardsAtFailureLimit().requireNotCoolingDown(SESSION_ID))),
				probe("RATE_LIMITED", "분당 창을 소진했다",
						() -> responseOf(
								() -> guardsOverLimit(RateLimitProperties.MINUTE).requireWithinLimits(PLAYER_REF))),
				probe("QUOTA_EXCEEDED", "일일 창을 소진했다",
						() -> responseOf(
								() -> guardsOverLimit(RateLimitProperties.DAY).requireWithinLimits(PLAYER_REF))));
	}

	/** 표시 이름에 <b>어느 자리인가</b>를 남긴다 — 코드가 같은 줄이 넷이면 번호로는 읽히지 않는다. */
	private static Arguments probe(String code, String label, LiveErrorResponse live) {
		return arguments(code + " — " + label, code, live);
	}

	/** 코드마다 <b>구동 경로가 실제로 만드는 모양들</b>. */
	private static Map<String, Set<Set<String>>> probedShapes() throws Exception {
		Map<String, Set<Set<String>>> shapes = new TreeMap<>();
		for (Arguments probe : livingErrorResponses().toList()) {
			String code = (String) probe.get()[1];
			String body = ((LiveErrorResponse) probe.get()[2]).body();
			shapes.computeIfAbsent(code, key -> new LinkedHashSet<>())
					.add(shapeOf(JsonPath.read(body, "$.details")));
		}
		return shapes;
	}

	// ── 2. details 의 모양 ──────────────────────────────────

	/**
	 * {@code details} 를 <b>키 경로와 타입</b>의 집합으로 편다 — {@code fields[].reason:string} 처럼.
	 *
	 * <p>배열은 {@code []} 로 접는다. 맵과 배열이 다른 경로를 만들므로 #466 의 어긋남이 여기서 갈린다.
	 * 값은 담지 않는다 — 실패 메시지에 응답 내용이 실리지 않아야 한다 (S-11).
	 */
	private static Set<String> shapeOf(Object details) {
		Set<String> shape = new TreeSet<>();
		flatten("", details, shape);
		return shape;
	}

	private static void flatten(String path, Object node, Set<String> shape) {
		if (node instanceof Map<?, ?> map) {
			if (map.isEmpty()) {
				if (!path.isEmpty()) {
					shape.add(path + ":{}");
				}
				return;
			}
			map.forEach((key, value) -> flatten(path.isEmpty() ? String.valueOf(key) : path + "." + key, value, shape));
			return;
		}
		if (node instanceof List<?> list) {
			if (list.isEmpty()) {
				shape.add(path + "[]:empty");
				return;
			}
			list.forEach(element -> flatten(path + "[]", element, shape));
			return;
		}
		shape.add(path + ":" + typeOf(node));
	}

	/** 타입은 JSON 이 아는 만큼만 말한다. 자바 클래스명을 실패 메시지에 흘리지 않는다 (S-6). */
	private static String typeOf(Object leaf) {
		if (leaf == null) {
			return "null";
		}
		if (leaf instanceof Number) {
			return "number";
		}
		if (leaf instanceof Boolean) {
			return "boolean";
		}
		if (leaf instanceof CharSequence) {
			return "string";
		}
		return "scalar";
	}

	// ── 3. 계약에서 예제를 센다 ─────────────────────────────

	/** 계약 안 한 자리의 에러 예제. {@code location} 은 실패했을 때 고칠 자리를 가리킨다. */
	record ErrorExample(String location, String code, Object details) {
	}

	private static List<ErrorExample> examplesOf(String code) {
		return errorExamples().stream().filter(example -> code.equals(example.code())).toList();
	}

	/**
	 * 계약 <b>전체</b>를 훑어 에러 예제를 모은다.
	 *
	 * <p>{@code components/responses} 만 보지 않는 이유는 {@code TURN_CONFLICT} 가 경로 안에 직접
	 * 그려져 있기 때문이다. 자리를 손으로 적으면 그 자리 밖의 예제가 다시 대조 밖에 남는다.
	 */
	private static List<ErrorExample> errorExamples() {
		List<ErrorExample> found = new ArrayList<>();
		walk(SPEC, "", found);
		return found;
	}

	@SuppressWarnings("unchecked")
	private static void walk(Object node, String path, List<ErrorExample> found) {
		if (node instanceof Map<?, ?> map) {
			map.forEach((rawKey, value) -> {
				String key = String.valueOf(rawKey);
				if ("example".equals(key)) {
					errorBody(value).ifPresent(body -> found.add(
							new ErrorExample(path + "/example", String.valueOf(body.get("error")), body.get("details"))));
				}
				else if ("examples".equals(key) && value instanceof Map<?, ?> named) {
					named.forEach((name, wrapper) -> {
						if (wrapper instanceof Map<?, ?> entry) {
							errorBody(((Map<String, Object>) entry).get("value")).ifPresent(body -> found.add(
									new ErrorExample(path + "/examples/" + name, String.valueOf(body.get("error")),
											body.get("details"))));
						}
					});
				}
				walk(value, path + "/" + key, found);
			});
			return;
		}
		if (node instanceof List<?> list) {
			for (int index = 0; index < list.size(); index++) {
				walk(list.get(index), path + "/" + index, found);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Optional<Map<String, Object>> errorBody(Object value) {
		if (value instanceof Map<?, ?> body && body.containsKey("error") && body.containsKey("details")) {
			return Optional.of((Map<String, Object>) body);
		}
		return Optional.empty();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> loadSpec() {
		try (InputStream in = new ClassPathResource("openapi/openapi.yaml").getInputStream()) {
			return (Map<String, Object>) new Yaml().load(in);
		}
		catch (Exception ex) {
			throw new IllegalStateException("계약 파일을 클래스패스에서 읽지 못했다. build.gradle.kts 의 복사 설정을 본다.", ex);
		}
	}

	// ── 4. 살아 있는 응답을 얻는 자리 ───────────────────────

	/** 프로덕션 경로가 던진 예외를 {@link GlobalExceptionHandler} 가 내보낸 <b>응답 본문</b>. */
	@FunctionalInterface
	interface LiveErrorResponse {

		String body() throws Exception;
	}

	private static String responseOf(Runnable productionCall) throws Exception {
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingProbeController(productionCall))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();

		return bodyOf(mockMvc.perform(get("/error-details-probe")).andReturn());
	}

	/** 본문이 DTO 로 풀리지 못한 실패 (#466). 던지는 것이 아니라 <b>요청</b>이 만드는 응답이다. */
	private static String unreadableBody() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new UnreadableBodyProbeController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();

		return bodyOf(mockMvc.perform(post("/error-details-probe")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"turnNo\":\"글자\"}")).andReturn());
	}

	private static String bodyOf(MvcResult result) throws Exception {
		result.getResponse().setCharacterEncoding(StandardCharsets.UTF_8.name());
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	/** 응답 타입이 없으므로 {@code OpenApiContractTests} 의 커버리지 열거에는 잡히지 않는다. */
	@RestController
	static class ThrowingProbeController {

		private final Runnable productionCall;

		ThrowingProbeController(Runnable productionCall) {
			this.productionCall = productionCall;
		}

		@GetMapping("/error-details-probe")
		void probe() {
			this.productionCall.run();
		}
	}

	@RestController
	static class UnreadableBodyProbeController {

		@PostMapping("/error-details-probe")
		void probe(@RequestBody ProbeRequest request) {
		}
	}

	record ProbeRequest(Integer turnNo) {
	}

	// ── 5. 프로덕션 경로를 부르기 위한 최소 상태 ─────────────

	/**
	 * 턴 1 을 마친 진행 중 세션. 요청이 검증을 전부 통과하는 상태다.
	 *
	 * <p>{@code PlayTurnNoticeOrderTests} 와 같은 모양이지만 <b>합치지 않는다</b> — 그쪽이 잠그는 것은
	 * 판정의 <b>순서</b>이고 여기가 보는 것은 응답의 <b>모양</b>이다. 한쪽 사정으로 픽스처가 바뀌면
	 * 다른 쪽이 조용히 다른 것을 재게 된다.
	 */
	private static PlayTurnService turnServiceReadyForTurnTwo(TurnPipeline pipeline) {
		PlaySessionRepository sessions = mock(PlaySessionRepository.class);
		TurnRepository turns = mock(TurnRepository.class);
		IdempotencyStore idempotency = mock(IdempotencyStore.class);
		StoryCatalogFacade stories = mock(StoryCatalogFacade.class);
		AiNoticeQuery notices = mock(AiNoticeQuery.class);

		PlaySession session = PlaySession.start(PLAYER_REF, STORY_ID, VERSION_ID, "fixed", "scenario", false, NOW);
		session.recordTurn(1, 1, NOW);

		given(sessions.findById(SESSION_ID)).willReturn(Optional.of(session));
		given(stories.status(STORY_ID)).willReturn(Optional.of(new StoryStatusView(VERSION_ID, "published")));
		given(turns.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(SESSION_ID))
				.willReturn(Optional.of(Turn.create(new Turn.TurnDraft(SESSION_ID, 1, 1, PARAGRAPHS, CHOICES,
						null, false, false, null, SafetyVerdict.PASS, true, false), NOW)));
		given(notices.current()).willReturn(Optional.of(new AiNotice("v1", "고지 문구")));
		given(idempotency.reserve(anyString())).willReturn(true);

		return new PlayTurnService(sessions, turns, mock(StoryVersionFacade.class), pipeline,
				mock(TurnGuards.class), idempotency, stories, new AiNoticeText(notices));
	}

	/**
	 * 상한을 넘긴 원고 (§13-81). 게이트가 보는 것은 개수뿐이므로 세 값만 세운다.
	 *
	 * <p><b>{@code DraftScaleGateTests} 와 합치지 않는다</b> — 그쪽이 잠그는 것은 <b>몇 개까지
	 * 지나가는가</b> 이고 여기가 보는 것은 응답의 <b>모양</b>이다.
	 */
	private static DraftStoryDefinition.Declared oversizedDraft() {
		UgcLimitProperties limits = UgcLimitProperties.defaults();
		return new DraftStoryDefinition.Declared(new DraftStateSchema(Set.of(), Set.of()),
				limits.chaptersPerStory() + 1, 1);
	}

	private static DraftScaleGate scaleGate() {
		return new DraftScaleGate(UgcLimitProperties.defaults());
	}

	/**
	 * 예산을 넘겼다고 답하는 판정기 (§13-76).
	 *
	 * <p><b>경계를 실제로 계산하지 않는다.</b> 여기서 보는 것은 게이트가 <b>어떤 모양으로</b>
	 * 거절하는가이고, 계산기의 경계가 어디인가는 {@code DraftVocabularyGateTests} 의 것이다 —
	 * 그 경계가 움직일 때 이 파일까지 빨개지면 실패가 무엇을 말하는지 흐려진다.
	 */
	private static DraftVocabularyGate overBudgetVocabularyGate() {
		return new DraftVocabularyGate(
				(numericPaths, flags, inventory) -> new StateVocabularyBudget.Usage(137));
	}

	/**
	 * 승인되지 않은 남의 손이 닿지 않은 작품 (R8.6). <b>칸이 아니라 전제가 맞지 않는 자리</b>다.
	 *
	 * <p>트랜잭션 관리자는 흉내만 낸다 — 여기서 도는 것은 커밋이 아니라 <b>전제 판정</b>이고,
	 * 그 판정은 저장소를 만나기 전에 끝난다.
	 */
	private static StoryVisibilityService visibilityServiceOf(String reviewStatus) {
		StoryPublisher publisher = mock(StoryPublisher.class);
		given(publisher.ownerStatusOf(STORY_ID))
				.willReturn(Optional.of(new StoryPublisher.OwnedStory(PLAYER_REF, reviewStatus, "unlisted")));

		PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
		given(transactions.getTransaction(any())).willReturn(mock(TransactionStatus.class));

		return new StoryVisibilityService(publisher, mock(StoryReviewRepository.class),
				mock(StoryReviewTimeline.class), Clock.systemUTC(), transactions);
	}

	/** L2 가 막은 턴 (§9.2). 세션 상태는 직전 턴 그대로다 (R6.6). */
	private static TurnPipeline blockingPipeline() {
		TurnPipeline pipeline = mock(TurnPipeline.class);
		given(pipeline.advance(SESSION_ID, 1, CHOICE_ID)).willReturn(new TurnOutcome(
				TurnOutcome.TurnStatus.SAFETY_BLOCKED, null, 1, false, 1, null, null, 0, Set.of()));
		return pipeline;
	}

	/** 이미 다른 요청이 잡고 있는 동시 생성 락 (§4.3-2). */
	private static TurnGuards guardsWithHeldLock() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = valueOperations(redis);
		given(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(false);
		return new TurnGuards(redis, mock(RateLimiter.class), RateLimitProperties.defaults());
	}

	/** 연속 실패가 한도에 닿은 세션 (R6.5). */
	private static TurnGuards guardsAtFailureLimit() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		given(valueOperations(redis).get(anyString())).willReturn("3");
		return new TurnGuards(redis, mock(RateLimiter.class), RateLimitProperties.defaults());
	}

	/** 창 하나를 소진한 계정 (§15). 일일 창을 소진하면 {@code QUOTA_EXCEEDED} 다. */
	private static TurnGuards guardsOverLimit(Duration exhausted) {
		RateLimiter rateLimiter = mock(RateLimiter.class);
		given(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any(Duration.class)))
				.willReturn(true);
		given(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), eq(exhausted)))
				.willReturn(false);
		given(rateLimiter.retryAfterSeconds(exhausted)).willReturn(42L);
		return new TurnGuards(mock(StringRedisTemplate.class), rateLimiter, RateLimitProperties.defaults());
	}

	@SuppressWarnings("unchecked")
	private static ValueOperations<String, String> valueOperations(StringRedisTemplate redis) {
		ValueOperations<String, String> values = mock(ValueOperations.class);
		given(redis.opsForValue()).willReturn(values);
		return values;
	}

	private static TurnRequestBody choice(int turnNo) {
		return new TurnRequestBody(CHOICE_ID, turnNo, null);
	}
}
