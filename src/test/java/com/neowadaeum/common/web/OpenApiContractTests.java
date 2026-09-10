package com.neowadaeum.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.error.GlobalExceptionHandler;
import com.neowadaeum.common.error.ValidationReason;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

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

	/** 스키마 안 <b>한 속성</b>의 {@code enum} 값 목록. 값이 스키마로 분리돼 있지 않은 자리다. */
	@SuppressWarnings("unchecked")
	private static List<String> enumOfProperty(String schemaName, String property) {
		Map<String, Object> properties = (Map<String, Object>) schema(schemaName).get("properties");
		Map<String, Object> found = (Map<String, Object>) properties.get(property);
		assertThat(found).as("스펙의 %s 에 %s 속성이 없다", schemaName, property).isNotNull();
		List<String> values = (List<String>) found.get("enum");
		assertThat(values).as("스펙의 %s.%s 에 enum 이 없다", schemaName, property).isNotNull();
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
		// §13-58 (#290) — 원문에는 없다. 원고는 지울 수 있는데 게시된 작품은 지울 수 없던 자리를
		// 정정본이 열었고, 계약이 그 문을 잃으면 화면의 [작품 삭제] 가 다시 갈 곳을 잃는다.
		"/api/v1/stories/{storyId}, delete",
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

	/**
	 * §13-97 — 스펙의 {@code ValidationReason} 열거와 {@link ValidationReason} 이 <b>정확히 같다</b> (#483).
	 *
	 * <p><b>왜 값까지 대조하는가.</b> §13-96 이 최상위 {@code details.reason} 을 <i>화면이 분기해도
	 * 되는 고정 코드</i>로 승격시켰다. 분기해도 되는 값이면 화면은 <b>그 목록을 알아야</b> 하는데,
	 * 값이 자리마다 흩어진 문자열 리터럴이던 동안 <b>넷 중 둘이 계약에 없었다.</b>
	 *
	 * <p><b>{@code ErrorDetailsExampleContractTests} 가 이것을 보지 못하는 것은 결함이 아니다</b>
	 * (#471). 그 검사는 {@code details} 의 <b>키 경로와 타입</b>만 대조하며, 값을 비교에서 뺀 것은
	 * <i>실패 메시지에 응답 내용이 실리지 않아야 한다</i>는 옳은 이유였다 (S-11). 그래서
	 * {@code {reason: string}} 모양이 맞으면 <b>어떤 값이든</b> 통과한다 — 값의 목록은 답하지
	 * 않기로 한 물음이었고, 여기가 그 자리다.
	 *
	 * <p><b>여기서는 값을 실패 메시지에 실어도 된다.</b> 이 값들은 공개 계약이 열거로 드는 고정
	 * 서버 코드이며 카테고리 수준이다 — {@code details} 의 다른 값에 대한 #471 의 정책은 그대로다.
	 *
	 * <p>{@code ErrorCode} 와 같은 방향으로 <b>양쪽</b>을 본다: 서버가 내보내는 값이 계약에 없으면
	 * 프론트는 분기를 만들 수 없고, 계약에만 있으면 아무도 내보내지 않는 약속이 남는다.
	 */
	@Test
	void S13_97_top_level_validation_reasons_match_the_enum_exactly() {
		Set<String> implemented = Arrays.stream(ValidationReason.values()).map(ValidationReason::code)
				.collect(Collectors.toSet());

		assertThat(enumOf("ValidationReason"))
				.as("최상위 details.reason 의 값이 계약과 코드에서 갈렸다 (§13-97, #483)")
				.containsExactlyInAnyOrderElementsOf(implemented);
	}

	/**
	 * §13-97 — <b>{@code ValidationError} 응답이 그 열거를 자기 자리에 붙인다</b> (#483).
	 *
	 * <p>열거가 {@code components} 에만 있으면 <b>어느 필드가 그 값을 갖는지</b>는 계약이 말하지
	 * 않는다. 생성기가 만드는 타입이 {@code details.reason} 에 닿지 않으므로 프론트는 다시 손으로
	 * 적게 되고, 그것이 이 이슈가 막으려던 것이다.
	 */
	@Test
	void S13_97_the_validation_error_response_declares_the_reason_enum() {
		assertThat(refsOf(validationErrorSchema()))
				.as("ValidationError 응답이 details.reason 에 ValidationReason 을 붙이지 않았다 (§13-97)")
				.contains("#/components/schemas/ValidationReason");
	}

	/** {@code ValidationError} 응답의 본문 스키마. */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> validationErrorSchema() {
		Map<String, Object> components = (Map<String, Object>) SPEC.get("components");
		Map<String, Object> response =
				(Map<String, Object>) ((Map<String, Object>) components.get("responses")).get("ValidationError");
		Map<String, Object> json =
				(Map<String, Object>) ((Map<String, Object>) response.get("content")).get("application/json");
		Map<String, Object> schema = (Map<String, Object>) json.get("schema");
		assertThat(schema).as("ValidationError 응답에 스키마가 없다").isNotNull();
		return schema;
	}

	/** 스키마 안의 {@code $ref} 전부. {@code allOf} 같은 합성 안에 들어 있어도 찾는다. */
	private static Set<String> refsOf(Object node) {
		Set<String> refs = new LinkedHashSet<>();
		collectRefs(node, refs);
		return refs;
	}

	private static void collectRefs(Object node, Set<String> refs) {
		if (node instanceof Map<?, ?> map) {
			map.forEach((key, value) -> {
				if ("$ref".equals(String.valueOf(key)) && value instanceof String ref) {
					refs.add(ref);
					return;
				}
				collectRefs(value, refs);
			});
			return;
		}
		if (node instanceof List<?> list) {
			list.forEach(element -> collectRefs(element, refs));
		}
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
	 * <b>로그인 nonce 발급 경로가 계약에 있고, 인증 없이 열린다</b> (§13-87, #424).
	 *
	 * <p><b>계약이 먼저 열려야 프론트가 따라올 수 있다</b> — 그쪽은 계약에 없는 값을 보내지
	 * 않는다. {@code security: []} 를 함께 못박는 이유는 이 경로가 <b>로그인보다도 앞</b>이라
	 * 요구할 자격 증명 자체가 없기 때문이다.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void S13_87_the_contract_declares_the_login_nonce_endpoint_outside_authentication() {
		Map<String, Object> operations = (Map<String, Object>) paths().get("/api/v1/auth/nonce");
		assertThat(operations).as("§13-87 의 nonce 발급 경로가 계약에 없다").isNotNull();
		assertThat(((Map<String, Object>) operations.get("post")).get("security"))
				.as("로그인 앞의 경로다 — 토큰을 요구하면 아무도 로그인을 시작할 수 없다")
				.isEqualTo(List.of());
	}

	/**
	 * <b>요청 본문에 {@code nonce} 필드를 만들지 않는다</b> (§13-87, #424).
	 *
	 * <p>필드로 받으면 <b>같은 요청이 실어 온 값을 그 요청 안에서 비교</b>하게 되어 대조가
	 * 성립하지 않는다. 그 값은 ID 토큰의 클레임으로 온다 — <b>이 단언이 그 함정을 계약에
	 * 못박는다.</b>
	 */
	@Test
	void S13_87_the_login_request_never_takes_a_nonce_field() {
		assertThat(propertiesOf("OAuthLoginRequest"))
				.as("nonce 는 요청 본문이 아니라 ID 토큰의 클레임으로 온다 (§13-87)")
				.doesNotContain("nonce");
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

	/**
	 * <b>탐색 셋이 인증 밖에 있다</b> (§13-54, 이슈 #306).
	 *
	 * <p>랜딩이 추천 작품을 인증 없이 주는데 목록과 상세는 토큰을 요구하고 있었다 — 프론트는
	 * 계약을 그대로 따라 익명을 로그인으로 보냈고, <b>서비스를 처음 보는 사람이 무엇이 있는지
	 * 볼 수 없었다.</b>
	 *
	 * <p><b>{@code 429} 를 함께 못박는다.</b> 인증 밖으로 열린 읽기에는 IP 기준 한도가 붙으므로
	 * (S-8), 그것이 계약에 없으면 클라이언트가 <b>예상하지 못한 코드</b>를 받는다.
	 *
	 * <p><b>{@code 401} 이 남아 있지 않은 것</b>도 확인한다. 남아 있으면 계약이 두 말을 하고,
	 * 프론트의 인증 밖 화면 목록이 다시 갈린다 (#306 이 그 목록에서 시작했다).
	 */
	@ParameterizedTest
	@CsvSource({ "/api/v1/library", "/api/v1/library/sections/{sectionKey}", "/api/v1/stories/{storyId}" })
	@SuppressWarnings("unchecked")
	void S13_54_browsing_is_declared_outside_authentication(String path) {
		Map<String, Object> get = (Map<String, Object>) ((Map<String, Object>) paths().get(path)).get("get");

		assertThat(get.get("security"))
				.as("탐색은 로그인 이전의 화면이다 (§13-54) — %s", path)
				.isEqualTo(List.of());
		Map<String, Object> responses = (Map<String, Object>) get.get("responses");
		assertThat(responses)
				.as("인증 없이 열리면 IP 기준 한도가 붙는다 (S-8) — %s", path)
				.containsKey("429");
		assertThat(responses)
				.as("인증을 요구하지 않는 경로가 401 을 선언하면 계약이 두 말을 한다 — %s", path)
				.doesNotContainKey("401");
	}

	/**
	 * #466 — {@code VALIDATION_ERROR} 의 <b>예제</b>가 실제 응답과 같은 모양인가.
	 *
	 * <p>{@code Error.details} 는 {@code additionalProperties: true} 라 어떤 모양이든 스키마를 통과한다.
	 * 그래서 예제가 맵이고 구현이 배열인 채로 오래 남아 있었다 — 필드 선언만 대조하는 검사는 이것을
	 * 보지 못한다. <b>계약을 읽는 사람은 예제를 읽는다.</b>
	 *
	 * <p>배열이 정본인 이유는 <b>한 필드에 위반이 둘 이상일 수 있기 때문</b>이다. 맵이면 뒤엣것이
	 * 앞엣것을 덮어써 사용자는 한 번에 하나씩만 고치게 된다.
	 *
	 * <p><b>예제가 여럿이 됐다</b> (§13-96, #478) — {@code fields} 항목은 자리에 따라 키를 더
	 * 들 수 있으므로({@code max}) 키 집합을 하나로 못박지 않는다. 대신 <b>핸들러가 내보내는 키
	 * 집합이 그중 하나로 그려져 있는가</b>를 본다. 모양 하나하나의 대조는
	 * {@code ErrorDetailsExampleContractTests} 가 양방향으로 한다 (#471).
	 */
	@Test
	void S9_1_validation_error_example_matches_the_real_response() throws Exception {
		List<Set<String>> drawnKeySets = validationErrorFieldEntries().stream()
				.map(entry -> (Set<String>) new LinkedHashSet<>(entry.keySet()))
				.toList();

		assertThat(drawnKeySets)
				.as("계약의 예제와 실제 응답이 서로 다른 키를 쓴다 (#466)")
				.contains(fieldKeysOfRealValidationFailure());
	}

	/**
	 * #470 — 계약의 예제가 <b>항목마다 {@code field} 를 든다.</b>
	 *
	 * <p>{@code field} 는 <i>어느 자리가 어긋났는가</i>이고 화면이 자기 문구를 고르는 근거다.
	 * {@code reason} 은 진단 값이라 그 자리를 대신할 수 없으므로, {@code field} 가 빠진 항목은
	 * <b>화면에서 쓸 수 없는 항목</b>이 된다.
	 *
	 * <p>바로 위 검사는 항목들의 키를 <b>합집합</b>으로 모으므로 한 항목이 {@code field} 를 빠뜨려도
	 * 통과한다. 예제가 곧 다음 사람이 읽는 계약이다.
	 *
	 * <p><b>{@code reason} 의 문구는 보지 않는다.</b> 검증 라이브러리의 기본 메시지이며 판본을 따라
	 * 바뀐다 — 테스트가 그 문구를 베끼면 라이브러리가 오를 때 계약과 무관하게 깨진다.
	 */
	@Test
	void Issue470_validation_error_example_names_a_field_in_every_entry() {
		assertThat(validationErrorFieldEntries())
				.as("계약의 예제에 어긋난 자리를 말하지 않는 항목이 있다 (#470)")
				.isNotEmpty()
				.allSatisfy(entry -> assertThat(entry.get("field")).isInstanceOf(String.class));
	}

	/**
	 * {@code ValidationError} 예제들이 그린 {@code details.fields} 항목 전부.
	 *
	 * <p>예제가 여럿이므로(§13-96) <b>하나를 골라 읽지 않는다</b> — 골라 읽으면 나머지 예제가
	 * 아무 검사도 받지 않고, 그것이 #471 이 드러낸 상태다.
	 */
	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> validationErrorFieldEntries() {
		List<Map<String, Object>> entries = new ArrayList<>();
		for (Map<String, Object> example : examplesOf("ValidationError")) {
			Object details = example.get("details");
			if (!(details instanceof Map<?, ?> map) || map.get("fields") == null) {
				continue;
			}
			assertThat(map.get("fields")).as("계약의 details.fields 가 배열이 아니다 (#466)")
					.isInstanceOf(List.class);
			entries.addAll((List<Map<String, Object>>) map.get("fields"));
		}
		return entries;
	}

	/** {@code components/responses} 한 자리의 에러 예제 전부 — {@code example} 하나든 {@code examples} 여럿이든. */
	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> examplesOf(String responseName) {
		Map<String, Object> components = (Map<String, Object>) SPEC.get("components");
		Map<String, Object> response =
				(Map<String, Object>) ((Map<String, Object>) components.get("responses")).get(responseName);
		assertThat(response).as("스펙에 %s 응답이 없다", responseName).isNotNull();

		Map<String, Object> json =
				(Map<String, Object>) ((Map<String, Object>) response.get("content")).get("application/json");
		List<Map<String, Object>> examples = new ArrayList<>();
		if (json.get("example") instanceof Map<?, ?> single) {
			examples.add((Map<String, Object>) single);
		}
		if (json.get("examples") instanceof Map<?, ?> named) {
			named.values().stream().filter(Map.class::isInstance)
					.map(entry -> ((Map<String, Object>) entry).get("value"))
					.filter(Map.class::isInstance)
					.forEach(value -> examples.add((Map<String, Object>) value));
		}
		assertThat(examples).as("%s 응답에 예제가 없다", responseName).isNotEmpty();
		return examples;
	}

	/**
	 * 실제 핸들러가 내보내는 {@code details.fields} 항목의 키.
	 *
	 * <p>계약이 아니라 <b>살아 있는 응답</b>에서 읽는다 — 그래야 다음에 한쪽만 움직였을 때 여기서 걸린다.
	 */
	private static Set<String> fieldKeysOfRealValidationFailure() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ValidationProbeController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();

		String body = mockMvc.perform(post("/contract-probe")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"turnNo\":\"글자\"}"))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		Map<String, Object> firstField = JsonPath.read(body, "$.details.fields[0]");
		return new LinkedHashSet<>(firstField.keySet());
	}

	/** 위 검사가 실제 응답을 얻기 위한 최소 컨트롤러. 응답 타입이 없으므로 계약 커버리지에는 잡히지 않는다. */
	@RestController
	static class ValidationProbeController {

		@PostMapping("/contract-probe")
		void probe(@RequestBody ContractProbeRequest request) {
		}
	}

	record ContractProbeRequest(Integer turnNo) {
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
	 * 검수 판정 값이 계약과 정확히 같다 (§13-64, R8.7).
	 *
	 * <p>한쪽만 늘어나면 <b>관리자 화면이 보내는 값을 서버가 400 으로 돌려주거나</b>, 반대로
	 * 계약에 없는 판정이 조용히 받아들여진다 — 후자는 작품을 내리는 값이므로 더 나쁘다.
	 */
	@Test
	void S13_64_review_verdicts_match_the_contract() {
		assertThat(enumOfProperty("ReviewVerdictRequest", "verdict"))
				.containsExactlyInAnyOrderElementsOf(
						Arrays.stream(com.neowadaeum.authoring.review.ReviewVerdict.values())
								.map(Enum::name).toList());
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

	/**
	 * <b>같은 자리에 키가 두 번 오면 실패한다</b> (#341).
	 *
	 * <p>SnakeYAML 은 기본 설정에서 <b>중복 키를 허용하고 마지막 것을 남긴다.</b> 그래서 이 파일이
	 * 다른 파서에게는 이미 깨져 있는데도 이 클래스의 나머지 단언은 전부 통과할 수 있다 — 실제로
	 * 그렇게 됐다. 인접한 두 오퍼레이션을 각각 더한 PR 둘이 순서대로 머지되면서 앞엣것의
	 * {@code responses} 가 뒤엣것 안으로 접붙었고, <b>텍스트 충돌은 나지 않았다.</b>
	 *
	 * <p><b>다른 레포가 이 파일의 소비자다.</b> 프론트는 이것으로 타입을 만들며, 파싱되지 않으면
	 * 계약과 무관한 PR 까지 전부 빨간불이 된다. 여기서 막지 않으면 그쪽에서 드러난다.
	 */
	@Test
	void B06_the_contract_has_no_duplicated_keys() throws Exception {
		LoaderOptions strict = new LoaderOptions();
		strict.setAllowDuplicateKeys(false);

		try (InputStream in = new ClassPathResource("openapi/openapi.yaml").getInputStream()) {
			assertThatCode(() -> new Yaml(new SafeConstructor(strict)).load(in))
					.doesNotThrowAnyException();
		}
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
			Map.entry("SessionListView", "AdminSessionListItem"),
			// #332 — 계약은 원고 안의 조각을 Manuscript 접두로 부른다 (같은 응답의 다른 셋과 같다).
			Map.entry("ReviewManuscript.PreviewTurn", "ManuscriptPreviewTurn"),
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

	// ── 7. 단위가 있는 수치 (#275) ────────────────────────────

	/**
	 * 단위가 있는 수치 필드의 이름 — <b>이름이 단위를 들고 있는 것만 고른다.</b>
	 *
	 * <p>{@code chapterNo} · {@code turnNo} · {@code order} 같은 카운터는 단위가 없다. 그런
	 * 필드에까지 설명을 강제하면 규칙이 소음이 되고, <b>소음이 된 규칙은 형식적으로 채워진다</b> —
	 * "정수"라고 적힌 {@code description} 은 없는 것과 같다.
	 */
	private static final List<String> UNIT_SUFFIXES =
			List.of("Rate", "Percent", "Ms", "Millis", "Seconds", "Micro", "MicroKrw", "Tokens",
					"Bytes", "Cost");

	/** 비율은 범위와 예시까지 요구한다 — {@code 0.12} 인지 {@code 12} 인지가 설명만으로는 갈린다. */
	private static final List<String> RATIO_SUFFIXES = List.of("Rate", "Percent");

	/**
	 * <b>단위가 있는 수치는 단위를 말한다</b> (#275, R2.7).
	 *
	 * <p>{@code reachRate} 는 타입이 {@code number} 뿐이라 {@code 0.12} 인지 {@code 12} 인지
	 * 계약이 말하지 않았다 (#260). 표본이 적은 동안 {@code null} 이라 <b>틀렸다면 운영에서 처음
	 * 드러났을 것</b>이고, 그래서 그 필드 하나에 단위·예시·상하한을 못박았다.
	 *
	 * <p><b>방어가 그 필드에만 있었다.</b> 계약 테스트는 <i>구현 ⊆ 계약</i> 한 방향만 보므로
	 * <b>타입은 맞고 단위가 없는 필드</b>는 어느 쪽도 잡지 못한다. 컴파일도 테스트도 통과한다.
	 *
	 * <p>같은 모양이 실제로 하나 더 있었다 — {@code AdminAiCall.costMicro} 는 <b>백만분의 1
	 * 단위인데 무엇의 백만분의 1인지가 레포 어디에도 없었다.</b> 아무 Provider 도 채우지 않아
	 * 항상 {@code null} 이라 드러나지 않았을 뿐이다. #311 이 통화를 KRW 로 정하고 <b>이름이
	 * 그것을 말하게</b> 했다 ({@code costMicroKrw}) — 그래서 {@code MicroKrw} 도 단위 접미어
	 * 목록에 있다. 이름이 바뀌었다고 설명 강제가 빠지면 규칙이 이름 하나로 우회된다.
	 */
	@Test
	void Issue275_numeric_fields_with_a_unit_declare_it() {
		List<String> silent = new ArrayList<>();
		List<String> unbounded = new ArrayList<>();

		eachSchemaProperty((schemaName, property, definition) -> {
			if (!isNumeric(definition) || !endsWithAny(property, UNIT_SUFFIXES)) {
				return;
			}
			String where = schemaName + "." + property;
			if (!(definition.get("description") instanceof String text) || text.isBlank()) {
				silent.add(where);
			}
			if (endsWithAny(property, RATIO_SUFFIXES)
					&& !(definition.containsKey("examples") && definition.containsKey("minimum")
							&& definition.containsKey("maximum"))) {
				unbounded.add(where);
			}
		});

		assertThat(silent)
				.as("단위가 있는 수치인데 계약이 단위를 말하지 않는다 (#275). description 에 무엇의 "
						+ "단위인지 적는다 — 타입이 number 인 것만으로는 12 가 12%% 인지 12건인지 갈린다")
				.isEmpty();
		assertThat(unbounded)
				.as("비율 필드에 상하한과 예시가 없다 (#275, R2.7). 0.12 인지 12 인지는 설명만으로는 "
						+ "갈리지 않는다 — 범위와 예시가 함께 있어야 한다")
				.isEmpty();
	}

	/** 모든 컴포넌트 스키마의 속성을 한 번씩 준다. */
	@SuppressWarnings("unchecked")
	private static void eachSchemaProperty(SchemaPropertyVisitor visitor) {
		Map<String, Object> schemas =
				(Map<String, Object>) ((Map<String, Object>) SPEC.get("components")).get("schemas");
		schemas.forEach((schemaName, schema) -> {
			Object properties = ((Map<String, Object>) schema).get("properties");
			if (properties == null) {
				return;
			}
			((Map<String, Object>) properties).forEach((property, definition) -> {
				if (definition instanceof Map) {
					visitor.visit(schemaName, property, (Map<String, Object>) definition);
				}
			});
		});
	}

	@FunctionalInterface
	private interface SchemaPropertyVisitor {

		void visit(String schemaName, String property, Map<String, Object> definition);
	}

	/** {@code type: integer} 와 {@code type: [integer, 'null']} 을 함께 본다. */
	private static boolean isNumeric(Map<String, Object> definition) {
		Object type = definition.get("type");
		if (type instanceof String single) {
			return "integer".equals(single) || "number".equals(single);
		}
		return type instanceof List<?> union
				&& (union.contains("integer") || union.contains("number"));
	}

	private static boolean endsWithAny(String name, List<String> suffixes) {
		return suffixes.stream().anyMatch(name::endsWith);
	}

	// ── 8. 상태 코드와 그 아래의 에러 코드 (#432) ──────────────

	/**
	 * <b>계약이 선언한 에러 응답은 어떤 코드가 오는지 말하고, 그 코드는 그 상태에 속한다</b> (#432).
	 *
	 * <p>여기까지의 검사는 <b>경로 · 핸들러 · 스키마 · enum</b> 을 봤다. 그래서 코드 목록이
	 * {@link ErrorCode} 와 같은지는 알았지만 <b>어느 오퍼레이션의 어느 상태에 어떤 코드가
	 * 실리는지</b>는 아무도 대조하지 않았다 — 로그인의 {@code 403} 이 {@code AGE_RESTRICTED}
	 * 하나만 적고 있던 동안 구현은 정지·탈퇴 회원에게 {@code FORBIDDEN} 을 내고 있었고, 계약을
	 * 읽고 화면을 만드는 쪽은 그것을 알 방법이 없었다.
	 *
	 * <p><b>여기서 넓히는 것은 둘이다.</b> 4xx·5xx 응답은 <b>코드를 하나 이상 이름으로 말해야
	 * 하고</b>, 말한 코드의 HTTP 상태가 <b>선언된 상태와 같아야 한다.</b> 예시가 곧 계약의
	 * 기계 판독 가능한 부분이 된다.
	 *
	 * <p><b>여전히 보지 못하는 것이 있다</b> — <i>구현이 던지는데 계약이 적지 않은 코드</i>다.
	 * {@code ApiException} 은 컨트롤러가 아니라 서비스 깊은 곳에서 던져지고
	 * {@code GlobalExceptionHandler} 를 지나 응답이 되므로, 핸들러 시그니처에서 되짚을 면이
	 * 없다. 그 방향은 경로별 테스트가 지킨다 (아래 · {@code OAuthLoginServiceTests}).
	 */
	@Test
	void Issue432_every_error_response_names_codes_that_belong_to_its_status() {
		List<String> silent = new ArrayList<>();
		List<String> misplaced = new ArrayList<>();

		eachErrorResponse((where, status, response) -> {
			Set<String> codes = declaredCodesOf(response);
			if (codes.isEmpty()) {
				silent.add(where);
				return;
			}
			codes.stream()
					.filter(code -> !belongsTo(code, status))
					.forEach(code -> misplaced.add(where + " → " + code));
		});

		assertThat(silent)
				.as("에러 응답인데 어떤 코드가 오는지 계약이 말하지 않는다 (#432). example 또는 "
						+ "examples 로 코드를 적는다 — 상태 코드만으로는 화면이 분기를 만들 수 없다")
				.isEmpty();
		assertThat(misplaced)
				.as("계약이 그 상태에 속하지 않는 코드를 적었다 (#432). ErrorCode 의 HttpStatus 가 "
						+ "정본이다")
				.isEmpty();
	}

	/**
	 * <b>정지·탈퇴 회원의 {@code FORBIDDEN} 이 두 경로 모두에 적혀 있다</b> (#432, R12.5).
	 *
	 * <p>로그인의 {@code 403} 에는 두 뜻이 실린다 — 나이로 막힌 사람과 <b>막힌 계정</b>이다.
	 * 코드가 갈리므로 화면은 가를 수 있지만, <b>계약이 후자를 적지 않으면 그 분기가 존재하는
	 * 줄을 모른다.</b> 재발급은 {@code requireActive} 로 같은 규칙을 쓰므로 같은 코드가 나오고,
	 * <b>두 경로가 다른 말을 하고 있으면 그것이 더 나쁘다.</b>
	 */
	@Test
	void R12_5_both_token_paths_declare_the_blocked_member() {
		assertThat(declaredCodesOf(responseOf("/api/v1/auth/oauth/{provider}", "post", "403")))
				.as("로그인의 403 이 정지·탈퇴 회원의 FORBIDDEN 을 말하지 않는다 (#432)")
				.containsExactlyInAnyOrder("AGE_RESTRICTED", "FORBIDDEN");
		assertThat(declaredCodesOf(responseOf("/api/v1/auth/refresh", "post", "403")))
				.as("재발급의 403 이 FORBIDDEN 을 말하지 않는다 (#432)")
				.contains("FORBIDDEN");
	}

	/** 계약의 모든 4xx · 5xx 응답을 한 번씩 준다. {@code $ref} 는 풀어서 준다. */
	@SuppressWarnings("unchecked")
	private static void eachErrorResponse(ErrorResponseVisitor visitor) {
		paths().forEach((path, node) -> ((Map<String, Object>) node).forEach((verb, operation) -> {
			if (!HTTP_METHODS.contains(verb.toUpperCase(Locale.ROOT))) {
				return;
			}
			Object responses = ((Map<String, Object>) operation).get("responses");
			if (responses == null) {
				return;
			}
			((Map<String, Object>) responses).forEach((status, response) -> {
				String code = String.valueOf(status);
				if (!code.startsWith("4") && !code.startsWith("5")) {
					return;
				}
				visitor.visit(verb.toUpperCase(Locale.ROOT) + " " + path + " " + code,
						Integer.parseInt(code), resolve(response));
			});
		}));
	}

	@FunctionalInterface
	private interface ErrorResponseVisitor {

		void visit(String where, int status, Map<String, Object> response);
	}

	/** 한 오퍼레이션의 한 응답. 없으면 실패한다 — 못박는 자리이므로 조용히 넘기지 않는다. */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> responseOf(String path, String verb, String status) {
		Map<String, Object> operations = (Map<String, Object>) paths().get(path);
		assertThat(operations).as("계약에 %s 가 없다", path).isNotNull();
		Map<String, Object> operation = (Map<String, Object>) operations.get(verb);
		assertThat(operation).as("계약의 %s 에 %s 가 없다", path, verb).isNotNull();
		Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
		Object response = responses.get(status);
		assertThat(response).as("%s %s 에 %s 응답이 없다", verb, path, status).isNotNull();
		return resolve(response);
	}

	/** {@code $ref: '#/components/responses/X'} 를 그 컴포넌트로 바꾼다. */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> resolve(Object response) {
		Map<String, Object> node = (Map<String, Object>) response;
		if (!(node.get("$ref") instanceof String ref)) {
			return node;
		}
		String name = ref.substring(ref.lastIndexOf('/') + 1);
		Map<String, Object> responses =
				(Map<String, Object>) ((Map<String, Object>) SPEC.get("components")).get("responses");
		Map<String, Object> found = (Map<String, Object>) responses.get(name);
		assertThat(found).as("계약이 없는 응답 컴포넌트를 가리킨다 — %s", ref).isNotNull();
		return found;
	}

	/**
	 * 한 응답이 이름으로 말하는 에러 코드들.
	 *
	 * <p>{@code example} 하나든 {@code examples} 여럿이든 같게 읽는다 — <b>한 상태에 코드가
	 * 여럿인 자리</b>({@code 429} · {@code 500} · 로그인의 {@code 403})가 후자다.
	 */
	@SuppressWarnings("unchecked")
	private static Set<String> declaredCodesOf(Map<String, Object> response) {
		Object content = response.get("content");
		if (!(content instanceof Map)) {
			return Set.of();
		}
		Object json = ((Map<String, Object>) content).get("application/json");
		if (!(json instanceof Map)) {
			return Set.of();
		}
		Map<String, Object> body = (Map<String, Object>) json;
		Set<String> codes = new LinkedHashSet<>();
		codeOf(body.get("example")).ifPresent(codes::add);
		if (body.get("examples") instanceof Map<?, ?> examples) {
			examples.values().stream()
					.filter(Map.class::isInstance)
					.forEach(example -> codeOf(((Map<String, Object>) example).get("value"))
							.ifPresent(codes::add));
		}
		return codes;
	}

	@SuppressWarnings("unchecked")
	private static Optional<String> codeOf(Object example) {
		if (example instanceof Map<?, ?> body && ((Map<String, Object>) body).get("error") instanceof String code) {
			return Optional.of(code);
		}
		return Optional.empty();
	}

	/** 그 코드가 그 HTTP 상태로 나가는가. 정본은 {@link ErrorCode} 다. */
	private static boolean belongsTo(String code, int status) {
		return Arrays.stream(ErrorCode.values())
				.filter(value -> value.name().equals(code))
				.anyMatch(value -> value.status().value() == status);
	}
}
