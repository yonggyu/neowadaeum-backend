package com.neowadaeum.authoring.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.authoring.outline.ConditionParameter;
import com.neowadaeum.authoring.outline.ConditionParameterType;
import com.neowadaeum.authoring.outline.ConditionTemplate;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * 이슈 #282 — <b>조건 템플릿은 키만으로 완성되지 않는다</b> (§13-56).
 *
 * <p>#282 가 목록 경로보다 먼저 답하라고 한 물음은 이것이었다: {@code affinity_at_least} 는
 * 대상·임계가 <b>키에 접혀 있는가</b>. 답은 <b>접혀 있지 않다</b>이며, 근거는
 * {@code ConditionEvaluator} 가 읽는 형태가 {@code {"gte": ["affinity.<인물>", <임계>]}} 라는
 * 사실이다 — 두 값 중 어느 것도 키 안에 없다.
 *
 * <p>그래서 <b>계약이 템플릿마다 필요한 입력을 선언한다.</b> 여기서 못박는 것은 그 선언이
 * 사라지지 않는 것과, 코드와 계약의 어휘가 갈라지지 않는 것이다 — 갈라지면 화면은 서버가 주지
 * 않는 입력을 그리거나, 필요한 입력을 묻지 않고 <b>영영 참이 되지 않는 조건</b>을 저장한다.
 */
class AuthoringMetadataContractTests {

	private static final Map<String, Object> SPEC = loadSpec();

	@SuppressWarnings("unchecked")
	private static Map<String, Object> loadSpec() {
		try (InputStream in = new ClassPathResource("openapi/openapi.yaml").getInputStream()) {
			return (Map<String, Object>) new Yaml().load(in);
		}
		catch (Exception ex) {
			throw new IllegalStateException("계약 파일을 클래스패스에서 읽지 못했다", ex);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> schema(String name) {
		Map<String, Object> components = (Map<String, Object>) SPEC.get("components");
		Map<String, Object> found =
				(Map<String, Object>) ((Map<String, Object>) components.get("schemas")).get(name);
		assertThat(found).as("스펙에 %s 스키마가 없다", name).isNotNull();
		return found;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> property(String schemaName, String property) {
		Map<String, Object> properties = (Map<String, Object>) schema(schemaName).get("properties");
		Map<String, Object> found = (Map<String, Object>) properties.get(property);
		assertThat(found).as("%s 에 %s 가 없다", schemaName, property).isNotNull();
		return found;
	}

	/**
	 * <b>목록을 주는 경로가 계약에 있다</b> (#282 · #315, §13-56).
	 *
	 * <p>이 경로가 없던 동안 프론트는 장르 다섯과 조건 템플릿 넷을 <b>소스에 상수로</b> 들고
	 * 있었다. 목록이 바뀌는 날부터 옛 목록을 보여 주고, 서버가 거부할 때까지 아무도 모른다.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void S13_56_the_contract_declares_one_path_for_authoring_metadata() {
		Map<String, Object> paths = (Map<String, Object>) SPEC.get("paths");
		Map<String, Object> operations = (Map<String, Object>) paths.get("/api/v1/authoring/metadata");

		assertThat(operations).as("작성 메타데이터 경로가 계약에 없다 (#282)").isNotNull();
		assertThat(operations).containsKey("get");
		assertThat(((Map<String, Object>) operations.get("get")).get("operationId"))
				.isEqualTo("getAuthoringMetadata");
	}

	/**
	 * <b>작성자 경로이므로 토큰을 요구한다</b> (§13-56).
	 *
	 * <p>{@code security: []} 는 <b>가입 전에 불리는 경로</b>에만 붙는다({@code /api/v1/consents}).
	 * 이 경로가 그 표시를 얻으면 인증 설정이 뒤따라 열리고, 그때 열리는 것은 이 목록만이
	 * 아니다 — 작성자 경로 전체가 같은 체인 아래 있다.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void S13_56_authoring_metadata_is_not_a_public_path() {
		Map<String, Object> paths = (Map<String, Object>) SPEC.get("paths");
		Map<String, Object> get = (Map<String, Object>) ((Map<String, Object>) paths
				.get("/api/v1/authoring/metadata")).get("get");

		assertThat(get).as("가입 전에 불리는 경로가 아니다 — security: [] 를 붙이지 않는다")
				.doesNotContainKey("security");
	}

	/**
	 * <b>계약의 템플릿 키가 코드의 열거형과 같다</b> (§13-35).
	 *
	 * <p>한쪽만 늘어나는 것이 가장 흔한 표류다. 계약에만 있으면 화면은 서버가 모르는 조건을
	 * 고르게 하고, 코드에만 있으면 아무도 그것을 고를 수 없다.
	 */
	@Test
	void S13_35_the_contract_lists_exactly_the_four_templates_the_code_supports() {
		@SuppressWarnings("unchecked")
		List<String> declared = (List<String>) property("ConditionTemplateSpec", "key").get("enum");

		assertThat(declared).containsExactlyInAnyOrderElementsOf(
				Arrays.stream(ConditionTemplate.values()).map(ConditionTemplate::key).toList());
		assertThat(declared).containsExactlyInAnyOrder("affinity_at_least", "has_flag", "lacks_flag",
				"turn_at_least");
	}

	/** 파라미터 타입의 어휘도 갈라지지 않는다. {@code character} 를 화면이 모르면 인물을 못 고른다. */
	@Test
	void S13_56_the_contract_lists_exactly_the_parameter_types_the_code_supports() {
		@SuppressWarnings("unchecked")
		List<String> declared = (List<String>) property("ConditionTemplateParameter", "type").get("enum");

		assertThat(declared).containsExactlyInAnyOrderElementsOf(
				Arrays.stream(ConditionParameterType.values()).map(ConditionParameterType::key).toList());
	}

	/**
	 * <b>빈칸이 있는 템플릿은 없다</b> (R7.16, #282).
	 *
	 * <p>넷 전부가 최소 하나의 입력을 요구한다. 하나라도 입력 없이 완성되는 것처럼 보이면,
	 * 그 순간 "키 하나면 조건이 된다"는 옛 전제가 되살아난다.
	 */
	@Test
	void R7_16_every_condition_template_declares_the_input_it_needs() {
		for (ConditionTemplate template : ConditionTemplate.values()) {
			assertThat(template.parameters())
					.as("%s 가 필요한 입력을 선언하지 않는다 — 키만으로는 조건이 완성되지 않는다 (#282)",
							template.key())
					.isNotEmpty();
			assertThat(template.label()).as("%s 의 표시 문구가 없다 — 라벨의 정본은 서버다", template.key())
					.isNotBlank();
			assertThat(template.description()).isNotBlank();
		}
	}

	/**
	 * <b>{@code affinity_at_least} 는 인물과 임계값 <i>둘</i> 을 요구한다</b> (#282).
	 *
	 * <p>{@code ConditionEvaluator} 가 읽는 것은 {@code {"gte": ["affinity.<인물>", <임계>]}} 이고,
	 * 두 자리 중 어느 것도 키 안에 접혀 있지 않다. 이것이 (a) 가 아니라 <b>(b)</b> 인 근거다.
	 */
	@Test
	void R7_16_affinity_at_least_needs_a_character_and_a_threshold() {
		List<ConditionParameter> parameters = ConditionTemplate.AFFINITY_AT_LEAST.parameters();

		assertThat(parameters).extracting(ConditionParameter::type)
				.as("호감도 조건은 대상과 임계를 둘 다 받는다 — 키 하나로는 쓸 수 없다")
				.containsExactly(ConditionParameterType.CHARACTER, ConditionParameterType.INTEGER);
	}

	/** 플래그 둘은 플래그 하나만, 턴 조건은 정수 하나만 받는다. */
	@Test
	void R7_16_flag_and_turn_templates_take_exactly_one_input() {
		assertThat(ConditionTemplate.HAS_FLAG.parameters()).extracting(ConditionParameter::type)
				.containsExactly(ConditionParameterType.FLAG);
		assertThat(ConditionTemplate.LACKS_FLAG.parameters()).extracting(ConditionParameter::type)
				.containsExactly(ConditionParameterType.FLAG);
		assertThat(ConditionTemplate.TURN_AT_LEAST.parameters()).extracting(ConditionParameter::type)
				.containsExactly(ConditionParameterType.INTEGER);
	}

	/**
	 * <b>인물 선택지의 출처를 계약이 말한다</b> (#282).
	 *
	 * <p>이 응답은 인물 목록을 주지 않는다 — 원고마다 다르기 때문이다. 계약이 그 사실을 적지
	 * 않으면 화면은 서버가 줄 것이라고 기다리거나, 자유 입력을 그린다.
	 */
	@Test
	void S13_56_the_contract_says_where_character_choices_come_from() {
		assertThat((String) property("ConditionTemplateParameter", "type").get("description"))
				.as("character 선택지가 원고에서 온다는 사실이 계약에 없다 (#282)")
				.contains("원고의 캐릭터 목록");
	}

	/**
	 * <b>장르의 정본이 표라는 사실을 계약이 말한다</b> (§13-25, #315).
	 *
	 * <p>다섯을 코드에 적으면 탐색 목록과 작성 목록이 서로 다른 정본을 갖는다 — 갈라지는 날
	 * <b>작성자가 고른 장르로는 열리지 않는 섹션</b>이 생긴다.
	 */
	@Test
	void S13_25_the_contract_ties_authoring_genres_to_the_library_genre_key() {
		assertThat((String) property("AuthoringMetadataResponse", "genres").get("description"))
				.contains("genre");
		assertThat((String) property("AuthoringGenre", "key").get("description"))
				.as("작성 목록의 key 가 섹션 키·Genre.genreId 와 같은 값이라는 사실이 계약에 없다 (#315)")
				.contains("genreId");
	}

	/**
	 * <b>키만으로 조건이 완성되지 않는다는 사실을 초안 응답이 가리킨다</b> (#282).
	 *
	 * <p>{@code conditionTemplateKey} 만 보고 구현하는 사람이 여기서 멈추게 한다.
	 */
	@Test
	void S13_56_outline_condition_slots_point_at_the_metadata_endpoint() {
		assertThat((String) property("OutlineChapter", "conditionTemplateKey").get("description"))
				.contains("getAuthoringMetadata");
		assertThat((String) property("OutlineEnding", "conditionTemplateKey").get("description"))
				.contains("getAuthoringMetadata");
	}
}
