package com.neowadaeum.authoring.draft;

import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원고가 선언한 상태 화이트리스트 (R4.1, #326).
 *
 * <p><b>왜 상수가 아니어야 하는가.</b> 지금까지 UGC 작품은 {@code {"flags":[]}} 로 발행됐다.
 * 그 스키마에서는 어떤 호감도도 어떤 플래그도 <b>병합 대상이 아니므로</b>(R4.1) 값이 영영 생기지
 * 않고, {@code affinity_at_least} · {@code has_flag} 조건은 <b>영원히 거짓</b>이 된다 — 작성자는
 * 조건을 골랐는데 그 챕터·엔딩에 아무도 닿지 못하며, <b>그 사실을 알 길이 없다.</b>
 *
 * <p>그래서 조건을 저장하는 것과 스키마를 선언하는 것은 <b>따로 할 수 없다</b> (#326).
 *
 * <p><b>정본은 원고가 적은 것이다.</b> 등장인물 이름과 플래그 이름은 작성자가 쓴 값이며, 조건
 * 검증도 발행되는 스키마도 <b>같은 목록</b>을 본다 — 둘이 갈라지면 검증을 통과한 조건이 런타임에
 * 거짓이 된다.
 */
public record DraftStateSchema(Set<String> characters, Set<String> flags) {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * 호감도의 범위와 턴당 상한.
	 *
	 * <p><b>작성자가 정하지 않는다</b> (R4.4, §13-9 와 같은 판단). 값은 공식 작품 시드가 쓰는
	 * 것과 같다 — 작품마다 다른 눈금을 허용하면 <b>같은 "호감도 50"</b> 이 작품마다 다른 뜻이
	 * 되고, 델타 상한은 AI 가 상태를 한 번에 흔들지 못하게 하는 장치이므로 작성자가 낮추거나
	 * 높일 값이 아니다.
	 */
	private static final String AFFINITY_SPEC = "{\"min\":0,\"max\":100,\"maxDeltaPerTurn\":5}";

	public DraftStateSchema {
		characters = Set.copyOf(characters == null ? Set.of() : characters);
		flags = Set.copyOf(flags == null ? Set.of() : flags);
	}

	/** 원고 {@code payload} 가 선언한 것. 비어 있으면 조건에 쓸 수 있는 이름이 없다. */
	public static DraftStateSchema from(JsonNode payload) {
		Set<String> characters = new LinkedHashSet<>();
		for (JsonNode character : payload.path("characters")) {
			String name = character.path("name").asString(null);
			if (name != null && !name.isBlank()) {
				characters.add(name);
			}
		}
		Set<String> flags = new LinkedHashSet<>();
		for (JsonNode flag : payload.path("flags")) {
			String name = flag.asString(null);
			if (name != null && !name.isBlank()) {
				flags.add(name);
			}
		}
		return new DraftStateSchema(characters, flags);
	}

	/**
	 * {@code story_version.state_schema} 에 저장될 JSON.
	 *
	 * <p><b>{@code flags} 는 인물이 없어도 선언한다</b> — 빈 배열과 키 자체가 없는 것은
	 * {@link com.neowadaeum.play.engine.StateSchema} 에서 같은 뜻이지만, 있는 편이 <b>이 작품이
	 * 무엇을 쓰기로 했는지</b>를 말한다.
	 */
	public String toJson() {
		var root = JSON.createObjectNode();
		if (!this.characters.isEmpty()) {
			var affinity = root.putObject("affinity");
			for (String name : this.characters) {
				affinity.set(name, JSON.readTree(AFFINITY_SPEC));
			}
		}
		var flagArray = root.putArray("flags");
		this.flags.forEach(flagArray::add);
		return root.toString();
	}
}
