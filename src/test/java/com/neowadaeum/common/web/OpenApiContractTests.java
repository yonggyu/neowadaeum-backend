package com.neowadaeum.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.play.api.PlayController;
import com.neowadaeum.play.api.TurnRequestBody;
import com.neowadaeum.catalog.query.CharacterCardView;
import com.neowadaeum.catalog.query.GenreView;
import com.neowadaeum.catalog.query.StoryCardView;
import com.neowadaeum.play.api.LibraryView;
import com.neowadaeum.play.api.StoryDetailResponse;
import com.neowadaeum.play.api.TurnView;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
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

	// ── 3. 구현 ⊆ 계약 ───────────────────────────────────────

	/**
	 * 이미 구현된 턴 응답의 모든 필드가 계약에 선언되어 있다.
	 *
	 * <p>반대 방향은 검사하지 않는다 — 계약이 구현보다 넓은 것은 계약 우선의 정상 상태다.
	 */
	@Test
	void B06_implemented_turn_response_fields_are_all_declared() {
		assertThat(propertiesOf("TurnResponse")).containsAll(recordComponentsOf(TurnView.class));
	}

	@Test
	void B06_implemented_paragraph_and_choice_fields_are_all_declared() {
		assertThat(propertiesOf("Paragraph")).containsAll(recordComponentsOf(TurnView.Paragraph.class));
		assertThat(propertiesOf("Choice")).containsAll(recordComponentsOf(TurnView.Choice.class));
	}

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

	@Test
	void B06_implemented_start_session_response_fields_are_all_declared() {
		assertThat(propertiesOf("StartSessionResponse"))
				.containsAll(recordComponentsOf(PlayController.StartSessionResponse.class));
	}

	/**
	 * 라이브러리 응답의 모든 필드가 계약에 선언되어 있다 (B-15).
	 *
	 * <p>여기서 어긋나면 <b>프론트가 모르는 필드</b>가 나가고, 그 지점이 계약이 거짓말을
	 * 시작하는 자리다.
	 */
	@Test
	void B15_implemented_library_response_fields_are_all_declared() {
		assertThat(propertiesOf("LibraryResponse")).containsAll(recordComponentsOf(LibraryView.class));
		assertThat(propertiesOf("LibrarySection"))
				.containsAll(recordComponentsOf(LibraryView.SectionView.class));
		assertThat(propertiesOf("ContinueSession"))
				.containsAll(recordComponentsOf(LibraryView.ContinueSessionView.class));
		assertThat(propertiesOf("StoryCard")).containsAll(recordComponentsOf(StoryCardView.class));
		assertThat(propertiesOf("Genre")).containsAll(recordComponentsOf(GenreView.class));
	}

	/**
	 * 작품 상세 응답의 모든 필드가 계약에 선언되어 있다 (B-16).
	 *
	 * <p><b>{@code ageRating} 이 계약에서 {@code const} 다.</b> 구현이 상수를 돌려주는지는
	 * {@code StoryDetailApiIntegrationTests} 가 값으로 확인한다 (I-19).
	 */
	@Test
	void B16_implemented_story_detail_fields_are_all_declared() {
		assertThat(propertiesOf("StoryDetailResponse"))
				.containsAll(recordComponentsOf(StoryDetailResponse.class));
		assertThat(propertiesOf("StoryDetail"))
				.containsAll(recordComponentsOf(StoryDetailResponse.Story.class));
		assertThat(propertiesOf("MySessionBrief"))
				.containsAll(recordComponentsOf(StoryDetailResponse.MySession.class));
		assertThat(propertiesOf("CharacterCard")).containsAll(recordComponentsOf(CharacterCardView.class));
	}

	// ── 4. S-11 ──────────────────────────────────────────────

	/**
	 * <b>운영 도메인을 적지 않는다</b> (S-11). 이 파일은 공개 레포에 커밋된다.
	 *
	 * <p>{@code servers} 를 두지 않으면 상대 경로로 해석된다. 계약에 필요한 것은 경로이지 호스트가
	 * 아니다.
	 */
	@Test
	void S11_the_contract_names_no_host() {
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
}
