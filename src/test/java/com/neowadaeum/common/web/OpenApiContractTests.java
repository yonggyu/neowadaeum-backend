package com.neowadaeum.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.play.api.TurnRequestBody;
import com.neowadaeum.play.api.HistoryView;
import com.neowadaeum.play.api.LandingView;
import com.neowadaeum.play.api.LibraryView;
import com.neowadaeum.play.api.MyStoriesView;
import com.neowadaeum.play.api.ResumeView;
import com.neowadaeum.play.api.StoryDetailResponse;
import com.neowadaeum.play.api.TurnView;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

/**
 * B-06 — <b>계약이 문서로만 남지 않게 한다.</b>
 *
 * <p>{@code docs/openapi.yaml} 은 런타임 진실의 원천이다(CLAUDE.md Source of Truth). 진실의 원천이
 * 검증되지 않으면 <b>가장 먼저 낡는 문서</b>가 된다 — 구현이 앞서가도 아무도 모르기 때문이다.
 * 그래서 여기서 세 가지를 못박는다.
 *
 * <ol>
 * <li>상위 문서 §13 의 <b>모든 엔드포인트</b>가 스펙에 있다 (B-06 DoD)</li>
 * <li>에러 코드 목록이 {@link ErrorCode} 와 <b>정확히 같다</b> — 스펙에 없는 코드를 만들지 않는다</li>
 * <li>이미 구현된 응답의 <b>모든 필드</b>가 스펙에 선언되어 있다 (구현 ⊆ 계약)</li>
 * </ol>
 *
 * <p><b>세 번째는 목록이 아니라 열거다</b> (#309). 검사할 스키마를 손으로 적던 동안
 * {@code authoring} 과 {@code admin} 은 한 번도 검사되지 않았고 실제로 하나가 샜다. 지금은
 * {@code @RestController} 의 반환 타입에서 응답을 <b>스스로 찾는다</b>.
 *
 * <p><b>계약이 구현보다 넓은 것은 정상이다.</b> B-06 은 계약 우선이므로 아직 구현되지 않은
 * 엔드포인트·필드가 스펙에 먼저 존재한다. 반대 방향 — 구현에는 있는데 계약에 없는 필드 — 만
 * 결함이다. 그것이 프론트가 모르는 필드이고, 계약이 거짓말을 시작하는 지점이다.
 *
 * <p>읽는 대상은 <b>클래스패스의 사본</b>이다. 빌드가 {@code docs/openapi.yaml} 을 거기로
 * 복사하므로(build.gradle.kts), 이 테스트는 계약 내용과 <b>포장 경로</b>를 함께 검증한다.
 */
class OpenApiContractTests {

	private static final Map<String, Object> SPEC = loadSpec();

	@SuppressWarnings("unchecked")
	private static Map<String, Object> loadSpec() {
		try (InputStream in = new ClassPathResource("openapi/openapi.yaml").getInputStream()) {
			return (Map<String, Object>) new Yaml().load(in);
		}
		catch (Exception ex) {
			throw new IllegalStateException("계약 파일을 클래스패스에서 읽지 못했다. build.gradle.kts 의 복사 설정을 본다.", ex);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> paths() {
		return (Map<String, Object>) SPEC.get("paths");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> schema(String name) {
		Map<String, Object> components = (Map<String, Object>) SPEC.get("components");
		Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
		Map<String, Object> found = (Map<String, Object>) schemas.get(name);
		assertThat(found).as("스펙에 %s 스키마가 없다", name).isNotNull();
		return found;
	}

	@SuppressWarnings("unchecked")
	private static Set<String> propertiesOf(String schemaName) {
		Map<String, Object> properties = (Map<String, Object>) schema(schemaName).get("properties");
		return properties.keySet();
	}

	/** 스펙의 {@code enum} 값 목록. 코드의 열거형과 대조하는 데 쓴다. */
	@SuppressWarnings("unchecked")
	private static List<String> enumOf(String schemaName) {
		List<String> values = (List<String>) schema(schemaName).get("enum");
		assertThat(values).as("스펙의 %s 에 enum 이 없다", schemaName).isNotNull();
		return values;
	}

	private static Set<String> recordComponentsOf(Class<?> type) {
		return Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)
				.collect(Collectors.toSet());
	}

	// ── 1. §13 엔드포인트 커버리지 ────────────────────────────

	/**
	 * 상위 문서 §13.1~§13.10 의 엔드포인트가 전부 있다 (B-06 DoD).
	 *
	 * <p>목록은 원문에서 옮긴 것이다. {@code /auth/email/*} 두 개는 <b>의도적으로 빠져 있다</b> —
	 * §13-11 채택안이 MVP 를 Google OAuth 하나로 좁혔다.
	 */
	@ParameterizedTest
	@CsvSource({
		"/api/v1/auth/oauth/{provider}, post",
		"/api/v1/auth/refresh, post",
		"/api/v1/library, get",
		"/api/v1/library/sections/{sectionKey}, get",
		"/api/v1/stories/{storyId}, get",
		"/api/v1/stories/{storyId}/sessions, post",
		"/api/v1/sessions/{sessionId}/resume, get",
		"/api/v1/sessions/{sessionId}/current, get",
		"/api/v1/sessions/{sessionId}, delete",
		"/api/v1/sessions/{sessionId}/turns, post",
		"/api/v1/sessions/{sessionId}/history, get",
		"/api/v1/me/sessions, get",
		"/api/v1/me/stories, get",
		"/api/v1/authoring/drafts, post",
		"/api/v1/authoring/drafts/{draftId}, patch",
		"/api/v1/authoring/drafts/{draftId}/precheck, post",
		"/api/v1/authoring/drafts/{draftId}/outline, post",
		"/api/v1/authoring/drafts/{draftId}/preview, post",
		"/api/v1/authoring/drafts/{draftId}/submit, post",
		"/api/v1/authoring/drafts/{draftId}/review, get",
		"/api/v1/stories/{storyId}/visibility, patch",
		"/api/v1/reports, post",
		"/api/v1/landing, get" })
	@SuppressWarnings("unchecked")
	void B06_every_endpoint_of_chapter_13_exists(String path, String method) {
		Map<String, Object> operations = (Map<String, Object>) paths().get(path);
		assertThat(operations).as("§13 의 %s 가 계약에 없다", path).isNotNull();
		assertThat(operations).containsKey(method);
	}

	/** §13-11 — MVP 는 Google OAuth 하나다. 이메일 가입 경로를 계약에 만들지 않는다. */
	@ParameterizedTest
	@ValueSource(strings = { "/api/v1/auth/email/signup", "/api/v1/auth/email/login" })
	void B06_email_signup_is_out_of_scope(String path) {
		assertThat(paths()).doesNotContainKey(path);
	}

	/**
	 * R2.7 — {@code reachRate} 는 <b>0.0~1.0 의 비율</b>이다. 계약이 단위를 잃지 않게 한다.
	 *
	 * <p>타입이 {@code number} 뿐이면 {@code 0.12} 인지 {@code 12} 인지 구분되지 않는다.
	 * 표본이 임계 미만인 동안은 {@code null} 이라(R2.8) 화면에 값이 뜨지 않으므로,
	 * 단위를 잘못 읽어도 <b>임계를 넘긴 뒤에야</b> 드러난다. 설명·예시·범위를 계약이 못박는다.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void R2_7_reach_rate_unit_is_declared_in_the_contract() {
		Map<String, Object> properties = (Map<String, Object>) schema("TurnResponse").get("properties");
		Map<String, Object> reachRate = (Map<String, Object>) properties.get("reachRate");
		assertThat(reachRate).as("TurnResponse 에 reachRate 가 없다").isNotNull();

		assertThat((String) reachRate.get("description")).as("reachRate 의 단위 설명이 없다").isNotBlank();
		assertThat(reachRate).as("reachRate 에 예시가 없다 — 0.12 인지 12 인지 계약이 말하지 않는다")
				.containsKey("examples");
		assertThat((List<Object>) reachRate.get("examples")).isNotEmpty();

		assertThat(((Number) reachRate.get("minimum")).doubleValue()).isEqualTo(0.0d);
		assertThat(((Number) reachRate.get("maximum")).doubleValue()).isEqualTo(1.0d);

		// 예시도 같은 단위여야 한다 — 백분율 값이 예시로 새어 들어오는 것을 막는다.
		for (Object example : (List<Object>) reachRate.get("examples")) {
			assertThat(((Number) example).doubleValue()).isBetween(0.0d, 1.0d);
		}
	}

	// ── 2. 에러 코드 ─────────────────────────────────────────

	/**
	 * 스펙의 {@code ErrorCode} enum 과 {@link ErrorCode} 가 <b>정확히 같다</b>.
	 *
	 * <p>한쪽만 늘어나는 것이 가장 흔한 표류다. 서버가 새 코드를 내보내는데 계약에 없으면 프론트는
	 * 그 코드를 매핑할 수 없고, 계약에만 있으면 아무도 구현하지 않은 약속이 남는다.
	 */
	@Test
	void B06_error_codes_match_the_enum_exactly() {
		@SuppressWarnings("unchecked")
		List<String> declared = (List<String>) schema("ErrorCode").get("enum");
		Set<String> implemented = Arrays.stream(ErrorCode.values()).map(Enum::name).collect(Collectors.toSet());

		assertThat(declared).containsExactlyInAnyOrderElementsOf(implemented);
	}

	/** §9.1 — 모든 에러가 한 형태로 수렴한다. {@code details} 는 필수이며 {@code null} 이 되지 않는다. */
	@Test
	@SuppressWarnings("unchecked")
	void B06_error_envelope_is_one_shape() {
		assertThat(propertiesOf("Error")).containsExactlyInAnyOrder("error", "message", "details");
		assertThat((List<String>) schema("Error").get("required")).containsExactlyInAnyOrder("error", "message",
				"details");
	}

	/**
	 * <b>약관 판본을 알려 주는 경로가 계약에 있다</b> (#261, R10.2).
	 *
	 * <p>이 경로가 없던 동안 프론트는 판본을 <b>상수로 들고 있었다.</b> 약관이 개정되면 그
	 * 상수가 그대로 동의 이력에 기록된다 — 법적 증빙이 틀리는 방식이고, 서버가 판본을 검증하지
	 * 않으므로 <b>조용히 틀린다.</b>
	 *
	 * <p>{@code security: []} 를 함께 못박는다. <b>가입 전에 불리는 경로</b>이므로 토큰을 요구하면
	 * 아직 회원이 아닌 사람이 약관을 읽을 방법이 없다.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void R10_2_the_contract_declares_the_consent_terms_endpoint() {
		Map<String, Object> operations = (Map<String, Object>) paths().get("/api/v1/consents");

		assertThat(operations).as("약관 판본을 알려 주는 경로가 계약에 없다 (#261)").isNotNull();
		assertThat(operations).containsKey("get");
		assertThat(((Map<String, Object>) operations.get("get")).get("security"))
				.as("가입 전에 불리는 경로다 — 토큰을 요구하면 아직 회원이 아닌 사람이 읽지 못한다")
				.isEqualTo(List.of());
	}

	// ── 3. 구현 ⊆ 계약 ───────────────────────────────────────



	/**
	 * 턴 요청의 입력면은 {@code choiceId} 와 {@code turnNo} 둘뿐이다 (I-1, I-18).
	 *
	 * <p>{@code idempotencyKey} 는 본문이 아니라 <b>헤더</b>이므로 스키마에 없는 것이 맞다 (R6.2).
	 * 계약이 본문 필드를 하나라도 더 갖게 되면 <b>서버가 신뢰하지 않는 값을 받는 자리</b>가 생긴다.
	 */
	@Test
	void B06_turn_request_has_exactly_two_input_fields() {
		assertThat(propertiesOf("TurnRequest")).containsExactlyInAnyOrder("choiceId", "turnNo");
		assertThat(recordComponentsOf(TurnRequestBody.class)).containsExactlyInAnyOrder("choiceId", "turnNo",
				"idempotencyKey");
	}





	/**
	 * {@code sessionState} 의 다섯 값이 계약과 정확히 같다 (§4.7).
	 *
	 * <p>한쪽만 늘어나면 클라이언트가 모르는 상태를 받고, <b>모르는 상태의 기본 처리는 대개
	 * "이어하기 가능"</b>이다 — 지운 세션을 이어가게 되는 방향이다.
	 */
	@Test
	void B17_session_state_values_match_the_contract() {
		assertThat(enumOf("SessionState")).containsExactlyInAnyOrderElementsOf(
				java.util.Arrays.stream(ResumeView.State.values())
						.map(state -> state.name().toLowerCase(java.util.Locale.ROOT))
						.toList());
	}

	/**
	 * 기록 응답의 모든 필드가 계약에 선언되어 있고, <b>{@code choiceId} 가 없다</b> (B-35, §13.6).
	 *
	 * <p>계약 쪽에서도 확인한다 — 구현이 주지 않아도 계약이 약속하면 그 자리가 언젠가 채워진다.
	 */
	@Test
	void B35_history_carries_no_choice_id_on_either_side() {
		assertThat(propertiesOf("HistoryItem")).doesNotContain("choiceId");
		assertThat(recordComponentsOf(HistoryView.Item.class)).doesNotContain("choiceId");
	}


	/**
	 * {@code status} 쿼리 값이 계약과 같다 (§13-6).
	 *
	 * <p><b>{@code in_progress} 는 존재하지 않는 상태였다.</b> 계약이 그것을 되살리면 구현이
	 * 조용히 0건을 돌려주는 조회로 돌아간다.
	 */
	@Test
	void B36_my_session_status_values_match_the_contract() {
		assertThat(propertiesOf("MySessionItem")).contains("status");
		assertThat(schema("MySessionItem").toString()).contains("active").contains("completed")
				.doesNotContain("in_progress]");
	}

	/**
	 * 랜딩 응답의 모든 필드가 계약에 선언되어 있고 <b>{@code isLoggedIn} 이 없다</b> (B-37, §13.10).
	 */
	@Test
	void B37_landing_carries_no_is_logged_in_on_either_side() {
		assertThat(propertiesOf("LandingResponse")).doesNotContain("isLoggedIn");
		assertThat(recordComponentsOf(LandingView.class)).doesNotContain("isLoggedIn");
	}

	// ── 4. S-11 ──────────────────────────────────────────────

	/**
	 * <b>운영 도메인을 적지 않는다</b> (S-11). 이 파일은 공개 레포에 커밋된다.
	 *
	 * <p>{@code servers} 를 두지 않으면 상대 경로로 해석된다. 계약에 필요한 것은 경로이지 호스트가
	 * 아니다.
	 */
	@Test
	void SEC11_the_contract_names_no_host() {
		assertThat(SPEC).doesNotContainKey("servers");
	}

	/**
	 * <b>계약 파일이 자동 서빙 경로 밖에 있다.</b>
	 *
	 * <p>{@code static/} · {@code public/} 아래로 옮기면 Spring Boot 가 프로파일과 무관하게 서빙해
	 * {@code OpenApiContractController} 의 프로파일 게이트를 통째로 우회한다 (B-47 과 같은 함정).
	 */
	@Test
	void B06_the_contract_file_is_outside_auto_served_locations() {
		assertThat(new ClassPathResource("openapi/openapi.yaml").exists()).isTrue();
		assertThat(new ClassPathResource("static/openapi/openapi.yaml").exists()).isFalse();
		assertThat(new ClassPathResource("static/openapi.yaml").exists()).isFalse();
		assertThat(new ClassPathResource("public/openapi.yaml").exists()).isFalse();
	}

	// ── 3-1. 커버리지 — 손 목록에 기대지 않는다 (#309) ────────

	/**
	 * <b>위의 목록이 규칙이 아니다.</b> 여기가 규칙이다 (#309).
	 *
	 * <p>바로 위 절은 스키마 이름을 <b>손으로 적어</b> 대조한다. 27줄이 있고, 그 목록에 없는
	 * 응답은 검사되지 않으며 <b>줄을 더하는 것을 잊어도 아무 일도 일어나지 않는다.</b> 그래서
	 * {@code authoring} 과 {@code admin} 의 응답은 한 번도 검사된 적이 없었고, 실제로 하나가
	 * 샜다 — {@code OutlineResponse.conditionTemplates} 는 구현이 싣는데 계약에 없었고, 그것이
	 * 이슈 #282(<i>"조건 템플릿 목록을 주는 경로가 없다"</i>)의 원인이었다. <b>경로는 처음부터
	 * 있었고 계약이 그것을 숨기고 있었다.</b>
	 *
	 * <p>#291 이 닫은 것과 같은 모양이다 — <b>목록으로 관리하는 규칙은 목록에 줄을 더하는 것을
	 * 잊는 순간 규칙이 아니게 된다.</b> 그래서 목록을 지우고 핸들러에서 열어 본다.
	 *
	 * <p><b>대응하는 스키마를 못 찾으면 건너뛰지 않고 실패한다.</b> 건너뛰면 지금 문제가 형태만
	 * 바꿔 돌아온다 — 검사한다고 믿는데 검사하지 않는 상태가 그것이다.
	 */
	@Test
	void Issue309_every_response_type_reachable_from_a_handler_is_declared() {
		List<String> undeclared = new ArrayList<>();
		List<String> unmapped = new ArrayList<>();

		for (Class<?> type : responseRecords()) {
			String schemaName = schemaNameOf(type);
			if (schemaName == null) {
				unmapped.add(canonicalNameOf(type));
				continue;
			}
			Set<String> declared = propertiesOf(schemaName);
			for (String field : recordComponentsOf(type)) {
				if (!declared.contains(field)) {
					undeclared.add("%s.%s → 스키마 %s".formatted(canonicalNameOf(type), field, schemaName));
				}
			}
		}

		assertThat(unmapped)
				.as("이 응답 타입에 대응하는 스키마를 찾지 못했다 (#309). 규칙으로 유도되지 않으면 "
						+ "SCHEMA_ALIASES 에 이유와 함께 더한다 — 건너뛰면 검사하지 않는 것이 된다")
				.isEmpty();
		assertThat(undeclared)
				.as("구현이 내보내는데 계약에 없는 필드다 (#309). **계약을 넓혀 고친다** — "
						+ "구현을 지우지 않는다. 프론트가 모르는 필드이고 계약이 거짓말을 시작하는 지점이다")
				.isEmpty();
	}

	/**
	 * 구현 클래스와 스키마 이름이 다른 자리.
	 *
	 * <p><b>규칙으로 유도되지 않는 것만 여기 온다</b> — {@code XxxView → XxxResponse} 와 이름이
	 * 그대로인 경우는 아래 {@link #schemaNameOf} 가 처리한다. 목록이 길어지면 그것은 계약과
	 * 구현의 이름 규칙이 갈라지고 있다는 신호다.
	 */
	private static final Map<String, String> SCHEMA_ALIASES = Map.ofEntries(
			// 계약은 접미 없이 부른다 — 목록의 원소이지 응답 자체가 아니기 때문이다.
			Map.entry("BlocklistEntryResponse", "BlocklistEntry"),
			Map.entry("ReviewQueueItemResponse", "ReviewQueueItem"),
			// 계약은 관리자 판정 결과를 Result 로 부른다 (같은 경로의 다른 셋과 같은 표기).
			Map.entry("AdminReviewController.ReviewVerdictResponse", "ReviewVerdictResult"),
			// 계약이 관리자 응답에 Admin 접두를 붙인다 — 사용자 응답과 같은 표에 있기 때문이다.
			Map.entry("SessionDebugView", "AdminSessionDebugSession"),
			Map.entry("AiCallView", "AdminAiCall"),
			// **이름이 겹친다.** SessionDebugView 안의 TurnView 는 플레이의 TurnView 와 다른
			// 것인데 단순 이름이 같아, 별칭이 없으면 TurnResponse 스키마에 대조되어 통과한다 —
			// 엉뚱한 계약을 지키고 있다고 믿는 상태다. 별칭의 키가 바깥 클래스를 포함하는 이유다.
			Map.entry("SessionDebugView.TurnView", "AdminDebugTurn"),
			// 한 View 가 두 응답으로 갈린다. 중첩 이름은 바깥 클래스까지 적어야 구분된다 —
			// Sessions · Stories · Item 은 혼자서는 무엇인지 모른다.
			Map.entry("MyStoriesView.Sessions", "MySessionsResponse"),
			Map.entry("MyStoriesView.Stories", "MyStoriesResponse"),
			Map.entry("MyStoriesView.SessionItem", "MySessionItem"),
			Map.entry("MyStoriesView.StoryItem", "MyStoryItem"),
			Map.entry("LibraryView.SectionView", "LibrarySection"),
			Map.entry("HistoryView.Item", "HistoryItem"),
			Map.entry("ConsentTermsView.Term", "ConsentTerm"),
			Map.entry("OutlineResponse.Chapter", "OutlineChapter"),
			Map.entry("OutlineResponse.Ending", "OutlineEnding"),
			Map.entry("StoryDetailResponse.Story", "StoryDetail"),
			Map.entry("StoryDetailResponse.MySession", "MySessionBrief"),
			// 계약은 세이프티 판정 하나를 Finding 으로 부른다 — precheck 만의 것이 아니다.
			Map.entry("PrecheckFinding", "Finding"));

	/**
	 * 이 클래스가 계약의 어느 스키마인가. 못 찾으면 {@code null} 이고, 그것은 실패다.
	 *
	 * <p>순서가 규칙이다 — 별칭이 먼저이고, 그다음이 이름 그대로, 마지막이 {@code View → Response}.
	 */
	private static String schemaNameOf(Class<?> type) {
		String canonical = canonicalNameOf(type);
		String alias = SCHEMA_ALIASES.get(canonical);
		if (alias != null) {
			return alias;
		}
		String simple = type.getSimpleName();
		if (hasSchema(simple)) {
			return simple;
		}
		if (simple.endsWith("View")) {
			String stem = simple.substring(0, simple.length() - 4);
			if (hasSchema(stem + "Response")) {
				return stem + "Response";
			}
			if (hasSchema(stem)) {
				return stem;
			}
		}
		return null;
	}

	/** 중첩 타입은 바깥 클래스까지 적는다 — {@code Sessions} 같은 이름은 혼자서는 무엇인지 모른다. */
	private static String canonicalNameOf(Class<?> type) {
		Class<?> outer = type.getEnclosingClass();
		return (outer == null) ? type.getSimpleName() : outer.getSimpleName() + "." + type.getSimpleName();
	}

	@SuppressWarnings("unchecked")
	private static boolean hasSchema(String name) {
		Map<String, Object> components = (Map<String, Object>) SPEC.get("components");
		return ((Map<String, Object>) components.get("schemas")).containsKey(name);
	}

	/**
	 * 핸들러가 내보내는 record 전부 — 중첩된 것까지 따라간다.
	 *
	 * <p>{@code ResponseEntity} · {@code List} · {@code Optional} 같은 껍데기는 벗긴다.
	 * record 가 아닌 것(문자열 · UUID · {@code Resource})은 스키마를 갖지 않으므로 대상이 아니다.
	 */
	private static Set<Class<?>> responseRecords() {
		Set<Class<?>> found = new LinkedHashSet<>();
		ClassPathScanningCandidateComponentProvider scanner =
				new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

		for (BeanDefinition candidate : scanner.findCandidateComponents("com.neowadaeum")) {
			Class<?> controller = ClassUtils.resolveClassName(candidate.getBeanClassName(), null);
			for (Method method : controller.getDeclaredMethods()) {
				if (AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null) {
					continue;
				}
				collectRecords(method.getGenericReturnType(), found);
			}
		}
		return found;
	}

	/** 타입 하나에서 record 를 캐낸다. 이미 본 것은 다시 파고들지 않는다 — 순환에서 멈추지 않는다. */
	private static void collectRecords(java.lang.reflect.Type type, Set<Class<?>> found) {
		if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
			for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
				collectRecords(argument, found);
			}
			return;
		}
		if (!(type instanceof Class<?> raw) || !raw.isRecord() || !found.add(raw)) {
			return;
		}
		for (java.lang.reflect.RecordComponent component : raw.getRecordComponents()) {
			collectRecords(component.getGenericType(), found);
		}
	}

	// ── 4. 계약 → 구현 ───────────────────────────────────────

	/**
	 * <b>계약에 있는 오퍼레이션은 전부 핸들러를 갖는다</b> (#245).
	 *
	 * <p>이 클래스의 나머지는 <b>구현 ⊆ 계약</b> 한 방향만 본다 — B-06 이 계약 우선이므로 아직
	 * 구현되지 않은 엔드포인트가 스펙에 먼저 존재하는 것이 정상이었다. <b>§12 의 작업이 전부
	 * 머지된 뒤에는 그 관용이 반대 방향의 공백을 덮는다:</b> 계약에만 있고 아무도 구현하지 않은
	 * 경로가 남아도 어떤 테스트도 깨지지 않는다. #245 가 그렇게 1년 가까이 남아 있었다.
	 *
	 * <p><b>경로 변수의 이름은 보지 않는다.</b> {@code {storyId}} 와 {@code {id}} 는 같은 자리다 —
	 * 계약과 구현이 다르게 불러도 라우팅은 같으며, 이름 차이로 실패하면 이 테스트는 곧 꺼진다.
	 *
	 * <p><b>반대 방향(구현에만 있는 경로)은 여기서 보지 않는다.</b> {@code dev} 콘솔(B-47)과 계약
	 * 서빙 경로는 계약의 대상이 아니고, 그 둘은 각자의 프로파일 테스트가 지킨다.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void B06_every_operation_in_the_contract_has_a_handler() {
		Set<String> handlers = handlerMappings();

		List<String> missing = new ArrayList<>();
		paths().forEach((path, node) -> ((Map<String, Object>) node).keySet().stream()
				.map(key -> key.toUpperCase(Locale.ROOT))
				.filter(HTTP_METHODS::contains)
				.map(verb -> verb + " " + samePlace(path))
				.filter(operation -> !handlers.contains(operation))
				.forEach(missing::add));

		assertThat(missing)
				.as("계약에 있는데 핸들러가 없다 — 프론트가 있다고 믿는 경로가 404 다 (#245)")
				.isEmpty();
	}

	private static final Set<String> HTTP_METHODS =
			Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

	/** {@code @RestController} 가 선언한 매핑. 컨텍스트를 띄우지 않고 애노테이션만 읽는다. */
	private static Set<String> handlerMappings() {
		ClassPathScanningCandidateComponentProvider scanner =
				new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

		Set<String> mappings = new LinkedHashSet<>();
		for (BeanDefinition candidate : scanner.findCandidateComponents("com.neowadaeum")) {
			Class<?> controller = ClassUtils.resolveClassName(candidate.getBeanClassName(), null);
			String base = firstPathOf(AnnotatedElementUtils.findMergedAnnotation(controller,
					RequestMapping.class));
			for (Method method : controller.getDeclaredMethods()) {
				RequestMapping mapping =
						AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
				if (mapping == null) {
					continue;
				}
				String full = samePlace(base + firstPathOf(mapping));
				for (RequestMethod verb : mapping.method()) {
					mappings.add(verb.name() + " " + full);
				}
			}
		}
		return mappings;
	}

	private static String firstPathOf(RequestMapping mapping) {
		if (mapping == null || mapping.path().length == 0) {
			return "";
		}
		return mapping.path()[0];
	}

	/** 경로 변수의 이름을 지운다 — 같은 자리인지만 본다. */
	private static String samePlace(String path) {
		String normalized = path.replaceAll("\\{[^}]*\\}", "{}");
		return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
	}

	// ── 5. 내 계정 조회 (#262) ────────────────────────────────

	/**
	 * <b>{@code /api/v1/me} 에 읽는 경로가 있다</b> (#262).
	 *
	 * <p>이 자리에는 {@code DELETE} 하나뿐이었다 — 탈퇴는 있는데 <b>내가 누구인지 물어볼 곳이
	 * 없었다.</b> 클라이언트는 토큰을 메모리에만 두므로 새로고침하면 무엇을 들고 있는지 알 수
	 * 없고, 그것을 확인할 경로가 없으면 <b>"로그인 유지"가 구현 불가능</b>하다.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void Issue262_me_can_be_read_not_only_deleted() {
		Map<String, Object> operations = (Map<String, Object>) paths().get("/api/v1/me");
		assertThat(operations).as("§13.1 의 /api/v1/me 가 계약에 없다").isNotNull();
		assertThat(operations).containsKeys("get", "delete");
	}

	/**
	 * 내 계정 응답의 모든 필드가 계약에 선언되어 있고, <b>식별정보가 어느 쪽에도 없다</b> (#262).
	 *
	 * <p>{@code playerRef} 는 {@code TokenResponse} 가 이미 돌려주지 않기로 한 값이다 (§13-7,
	 * I-3) — 이 경로가 그것을 되살리면 그 결정이 무의미해진다. {@code isLoggedIn} 이 없는 것은
	 * {@code LandingResponse} 와 같은 이유다: 로그인 여부는 200 과 401 로 답한다.
	 */
	@Test
	void Issue262_me_response_declares_every_field_and_no_identifiers() {
		assertThat(propertiesOf("MeResponse"))
				.containsAll(recordComponentsOf(com.neowadaeum.identity.api.MeResponse.class));
		assertThat(propertiesOf("MeResponse"))
				.doesNotContain("playerRef", "email", "birthDate", "socialId", "isLoggedIn");
		assertThat(recordComponentsOf(com.neowadaeum.identity.api.MeResponse.class))
				.doesNotContain("playerRef", "email", "birthDate", "socialId", "isLoggedIn");
	}

	// ── 6. AI 고지 Footer (#291) ──────────────────────────────

	/** 면제 표시. 값은 <b>왜 면제인지</b>를 적은 문장이며, {@code true} 로 갈음하지 않는다. */
	private static final String NOTICE_EXEMPT = "x-notice-exempt";

	/** 계약이 쓰는 표기는 소문자다. 위 {@code HTTP_METHODS} 는 컨트롤러 쪽 대문자 표기라 따로 둔다. */
	private static final Set<String> SPEC_METHODS = Set.of("get", "post", "put", "patch", "delete");

	/**
	 * <b>응답은 고지 문구를 싣거나, 왜 싣지 않는지 말하거나 둘 중 하나다</b> (#291, R11.1).
	 *
	 * <p>AI 고지 Footer 를 그리는 화면의 응답에 {@code noticeText} 가 빠지는 일이 <b>세 번</b>
	 * 있었다 — #257(라이브러리 · 작품 상세) · #284(내 이야기 · 내 작품 · 플레이 · 기록) ·
	 * #289(섹션 전체 보기). 매번 <b>프론트가 화면을 붙이다 발견했다.</b> 응답을 하나씩 채우는
	 * 방식은 열 번째 화면에서 같은 이슈를 다시 부른다.
	 *
	 * <p><b>어려운 쪽은 "무엇을 검사할 것인가"였다.</b> "Footer 를 그리는 화면"은 계약에 표시되어
	 * 있지 않아 판정 기준이 없었다. 표시를 <b>대상 쪽</b>에 두는 안(Footer 응답에 표시하고 표시된
	 * 것만 강제)은 <b>표시를 잊으면 그대로 샌다</b> — #257 → #284 → #289 가 정확히 그 모양이었다.
	 * 그래서 뒤집었다. <b>기본이 "싣는다"</b>이고, 싣지 않는 응답이 면제를 밝힌다. 빠뜨리면
	 * 여기서 깨지므로 침묵하지 않는다.
	 *
	 * <p>값을 {@code true} 가 아니라 <b>문장</b>으로 받는 이유는, 면제가 늘어날 때 그것이
	 * 판단이었는지 관성이었는지를 나중에 구분할 수 있어야 하기 때문이다.
	 *
	 * <p>본문이 없는 응답({@code 204}, {@code 202})은 화면이 아니므로 보지 않는다.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void Issue291_every_success_response_carries_the_notice_text_or_says_why_not() {
		List<String> silent = new ArrayList<>();
		paths().forEach((path, operations) -> ((Map<String, Object>) operations).forEach((method, operation) -> {
			if (!SPEC_METHODS.contains(method)) {
				return;
			}
			Map<String, Object> responses = (Map<String, Object>) ((Map<String, Object>) operation).get("responses");
			if (responses == null) {
				return;
			}
			responses.forEach((code, response) -> {
				if (!code.startsWith("2")) {
					return;
				}
				Map<String, Object> body = jsonBodyOf(response);
				if (body == null || carriesNoticeText(body) || declaresExemption(body)) {
					return;
				}
				silent.add("%s %s (%s) → %s".formatted(method.toUpperCase(Locale.ROOT), path, code,
						schemaNameOf(response)));
			});
		}));

		assertThat(silent)
				.as("이 응답들은 고지 문구를 싣지도, 왜 싣지 않는지 말하지도 않는다 (#291). "
						+ "Footer 를 그리는 화면이면 noticeText 를 required 로 더하고, 아니면 응답 스키마에 "
						+ "%s: '<왜 면제인지>' 를 적는다", NOTICE_EXEMPT)
				.isEmpty();
	}

	/**
	 * 면제 표시가 <b>이유를 적고 있다</b> (#291).
	 *
	 * <p>{@code true} 나 빈 문자열을 받아 주면 표시는 남지만 근거가 사라진다. 그러면 면제 목록이
	 * 늘어날 때 그것이 판단이었는지 관성이었는지 알 수 없다.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void Issue291_every_exemption_says_why() {
		Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) SPEC.get("components"))
				.get("schemas");
		schemas.forEach((name, schema) -> {
			Object reason = ((Map<String, Object>) schema).get(NOTICE_EXEMPT);
			if (reason == null) {
				return;
			}
			assertThat(reason).as("%s 의 %s 는 이유를 적은 문장이어야 한다", name, NOTICE_EXEMPT)
					.isInstanceOf(String.class);
			assertThat((String) reason).as("%s 의 면제 이유가 비어 있다", name).isNotBlank();
		});
	}

	/**
	 * 성공 응답의 JSON 본문 스키마. 본문이 없으면 {@code null} 이다.
	 *
	 * <p>{@code $ref} 는 끝까지 따라간다 — 응답 자체가 {@code components/responses} 를 가리킬 수도,
	 * 그 안의 스키마가 {@code components/schemas} 를 가리킬 수도 있다.
	 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> jsonBodyOf(Object response) {
		Map<String, Object> resolved = resolve(response, "responses");
		Map<String, Object> content = (Map<String, Object>) resolved.get("content");
		if (content == null) {
			return null;
		}
		Map<String, Object> json = (Map<String, Object>) content.get("application/json");
		if (json == null || json.get("schema") == null) {
			return null;
		}
		return resolve(json.get("schema"), "schemas");
	}

	/** {@code $ref} 하나를 푼다. 참조가 아니면 그대로 돌려준다. */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> resolve(Object node, String bucket) {
		Map<String, Object> map = (Map<String, Object>) node;
		Object ref = map.get("$ref");
		if (!(ref instanceof String pointer)) {
			return map;
		}
		String name = pointer.substring(pointer.lastIndexOf('/') + 1);
		Map<String, Object> components = (Map<String, Object>) SPEC.get("components");
		Map<String, Object> found = (Map<String, Object>) ((Map<String, Object>) components.get(bucket)).get(name);
		assertThat(found).as("스펙이 없는 %s 를 가리킨다: %s", bucket, pointer).isNotNull();
		return found;
	}

	/** 표시를 어디에 붙여야 하는지 실패 메시지가 스스로 말하게 한다. */
	@SuppressWarnings("unchecked")
	private static String schemaNameOf(Object response) {
		Map<String, Object> resolved = resolve(response, "responses");
		Map<String, Object> json = (Map<String, Object>) ((Map<String, Object>) resolved.get("content"))
				.get("application/json");
		Object ref = ((Map<String, Object>) json.get("schema")).get("$ref");
		return (ref instanceof String pointer) ? pointer.substring(pointer.lastIndexOf('/') + 1) : "(인라인 스키마)";
	}

	@SuppressWarnings("unchecked")
	private static boolean carriesNoticeText(Map<String, Object> schema) {
		List<String> required = (List<String>) schema.get("required");
		return required != null && required.contains("noticeText");
	}

	private static boolean declaresExemption(Map<String, Object> schema) {
		return schema.get(NOTICE_EXEMPT) != null;
	}
}
