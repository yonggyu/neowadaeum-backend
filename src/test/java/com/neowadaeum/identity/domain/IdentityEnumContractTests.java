package com.neowadaeum.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.common.spi.NoticeSurface;
import jakarta.persistence.AttributeConverter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * B-07(1/2) — enum 과 마이그레이션의 CHECK 제약이 <b>같은 값 목록</b>을 말하는지.
 *
 * <p>둘은 서로 다른 파일에 있고 컴파일러가 잇지 않는다. 한쪽만 늘어나면 그 사실은
 * <b>운영에서 제약 위반으로</b> 드러난다 — 새 값을 처음 저장하는 순간이고, 대개 배포 직후다.
 * 여기서 미리 빨개지게 한다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001). 파일 두 개를 대조할 뿐이다.
 */
class IdentityEnumContractTests {

	/** 값 목록이 두 마이그레이션에 나뉘어 있다 — V2 는 회원·로그인, V3 는 동의·고지다. */
	private static final List<String> MIGRATIONS = List.of(
			"db/migration/identity/V2__identity_core.sql",
			"db/migration/identity/V3__identity_consent.sql");

	/** §2.2 — {@code user.status} 3종. */
	@Test
	void S2_2_user_status_matches_the_check_constraint() {
		assertThat(checkedValues("status")).containsExactlyInAnyOrderElementsOf(dbNames(UserStatus.values()));
	}

	/** §2.2 — {@code oauth_identity.provider} 2종. */
	@Test
	void S2_2_oauth_provider_matches_the_check_constraint() {
		assertThat(checkedValues("provider")).containsExactlyInAnyOrderElementsOf(dbNames(OauthProvider.values()));
	}

	/** §2.2 — {@code consent_log.consent_type} 4종. */
	@Test
	void S2_2_consent_type_matches_the_check_constraint() {
		assertThat(checkedValues("consent_type"))
				.containsExactlyInAnyOrderElementsOf(dbNames(ConsentType.values()));
	}

	/** §2.7 — {@code ai_notice_impression.surface} 4종. */
	@Test
	void S2_7_notice_surface_matches_the_check_constraint() {
		assertThat(checkedValues("surface")).containsExactlyInAnyOrderElementsOf(dbNames(NoticeSurface.values()));
	}

	/** 변환은 한 곳에서만 한다. 모든 값이 소문자로 나갔다가 그대로 돌아온다. */
	@Test
	void every_value_round_trips_through_its_converter() {
		assertRoundTrip(new UserStatusConverter(), UserStatus.values());
		assertRoundTrip(new OauthProviderConverter(), OauthProvider.values());
		assertRoundTrip(new ConsentTypeConverter(), ConsentType.values());
		assertRoundTrip(new NoticeSurfaceConverter(), NoticeSurface.values());
	}

	/**
	 * <b>모르는 값을 기본값으로 흡수하지 않는다.</b>
	 *
	 * <p>흡수하면 마이그레이션과 enum 이 어긋난 사실이 <b>조용히 잘못된 상태</b>로 바뀐다.
	 */
	@Test
	void an_unknown_database_value_fails_loudly() {
		assertThatThrownBy(() -> new UserStatusConverter().convertToEntityAttribute("deleted"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("UserStatus");
	}

	/** {@code null} 은 값이 아니라 값 없음이다. 양방향 모두 그대로 통과한다. */
	@Test
	void null_passes_through_both_directions() {
		UserStatusConverter converter = new UserStatusConverter();
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToEntityAttribute(null)).isNull();
	}

	private static <E extends Enum<E>> void assertRoundTrip(AttributeConverter<E, String> converter, E[] values) {
		for (E value : values) {
			String stored = converter.convertToDatabaseColumn(value);
			assertThat(stored).isEqualTo(value.name().toLowerCase(Locale.ROOT));
			assertThat(converter.convertToEntityAttribute(stored)).isEqualTo(value);
		}
	}

	private static List<String> dbNames(Enum<?>[] values) {
		return Arrays.stream(values).map(value -> value.name().toLowerCase(Locale.ROOT)).toList();
	}

	/** {@code <column> IN ('a', 'b')} 에서 값 목록만 뽑는다. */
	private static List<String> checkedValues(String column) {
		Matcher matcher = Pattern.compile(column + "\\s+IN\\s*\\(([^)]*)\\)").matcher(migrations());
		assertThat(matcher.find()).as("%s 컬럼의 CHECK 제약이 마이그레이션에 없다", column).isTrue();
		return Arrays.stream(matcher.group(1).split(","))
				.map(value -> value.trim().replace("'", ""))
				.toList();
	}

	private static String migrations() {
		return MIGRATIONS.stream().map(IdentityEnumContractTests::read).collect(Collectors.joining("\n"));
	}

	private static String read(String path) {
		try {
			return new String(new ClassPathResource(path).getInputStream().readAllBytes(),
					StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
