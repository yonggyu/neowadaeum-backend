package com.neowadaeum.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 용도 열거형이 <b>DB 제약과 어긋나지 않는가</b> (B-24, R3.6, §13-4).
 *
 * <p>{@code ai_call_log.purpose} 는 CHECK 제약으로 네 값을 못박는다. 코드의 열거형이 그것과
 * 갈라지면 <b>기록이 INSERT 시점에 거부되거나</b> — 더 나쁘게는 — <b>대소문자 하나 때문에 통계에서
 * 조용히 빠진다.</b> 그 어긋남은 B-25 가 기록을 시작하는 순간에야 드러난다.
 *
 * <p>컨테이너가 필요 없다. 마이그레이션 파일을 텍스트로 읽는다 (ADR-0001).
 */
class AiPurposeTests {

	private static final Path MIGRATION =
			Path.of("src/main/resources/db/migration/promptlog/V2__prompt_log_and_audit.sql");

	/** R3.6 — 네 용도가 존재한다. 하나로 합쳐지면 비용이 갈리는 축이 사라진다. */
	@Test
	void R3_6_four_purposes_exist() {
		assertThat(AiPurpose.values()).hasSize(4);
		assertThat(Arrays.stream(AiPurpose.values()).map(AiPurpose::wireValue).toList())
				.containsExactly("turn", "summary", "safety", "outline");
	}

	/**
	 * <b>열거형과 CHECK 제약이 같은 값을 말한다.</b>
	 *
	 * <p>마이그레이션에서 실제 문자열을 읽어 대조한다 — 상수를 두 곳에 적어 두고 "같다"고 믿는
	 * 것과 다르다. 한쪽만 고치면 여기서 빨갛게 남는다.
	 */
	@Test
	void S13_4_the_enum_matches_the_database_check_constraint() throws IOException {
		String migration = Files.readString(MIGRATION, StandardCharsets.UTF_8);

		int start = migration.indexOf("purpose IN (");
		assertThat(start).as("CHECK 제약을 찾지 못했다 — 마이그레이션이 바뀌었다면 이 테스트도 함께 본다")
				.isNotNegative();

		String clause = migration.substring(start, migration.indexOf(')', start));
		Set<String> declared = Arrays.stream(clause.substring(clause.indexOf('(') + 1).split(","))
				.map(value -> value.trim().replace("'", ""))
				.collect(Collectors.toSet());

		assertThat(declared)
				.as("ai_call_log.purpose 의 CHECK 와 AiPurpose 가 갈라졌다 — 기록이 INSERT 에서 거부된다")
				.isEqualTo(Arrays.stream(AiPurpose.values()).map(AiPurpose::wireValue).collect(Collectors.toSet()));
	}

	/** <b>소문자로 나간다.</b> 대소문자 하나가 CHECK 를 통과하지 못한다. */
	@Test
	void S13_4_the_wire_value_is_lower_case() {
		assertThat(AiPurpose.TURN.wireValue()).isEqualTo("turn");
		assertThat(AiPurpose.TURN.name()).isEqualTo("TURN");
	}
}
