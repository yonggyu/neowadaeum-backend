package com.neowadaeum.authoring.blocklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.BlocklistQuery;
import com.neowadaeum.common.support.TextNormalizer;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * B-49 — <b>등록하면 다음 판정부터 걸린다</b> (R9.4).
 *
 * <p>이것이 이 작업의 전부다. 등록한 항목이 걸리지 않으면 운영자는 등록했다고 믿는데 서비스는
 * 여전히 통과시킨다 — <b>그 간극이 이 서비스의 실제 등급</b>이 된다.
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b>
 */
class BlocklistAdminServiceTests extends ContainerTestBase {

	@Autowired
	private BlocklistAdminService service;

	@Autowired
	private BlocklistEntryRepository entries;

	@Autowired
	private BlocklistQuery query;

	@AfterEach
	void clear() {
		this.entries.deleteAll();
	}

	/** <b>등록이 곧바로 조회에 보인다.</b> 캐시가 갱신을 삼키지 않는다. */
	@Test
	void R9_4_a_registered_entry_is_visible_to_the_next_lookup() {
		String value = unique();
		// 먼저 한 번 읽어 캐시를 채운다 — 그러지 않으면 무효화가 일한 것을 볼 수 없다.
		this.query.findAll();

		this.service.register(BlocklistKind.PHRASE, value, BlocklistSeverity.BLOCK, "test");

		assertThat(this.query.findAll())
				.extracting(com.neowadaeum.common.spi.BlocklistEntry::normalizedValue)
				.contains(TextNormalizer.normalize(value));
	}

	/** <b>삭제도 곧바로 보인다.</b> 지웠는데 여전히 걸리면 지운 것이 아니다. */
	@Test
	void R9_4_a_removed_entry_disappears_from_the_next_lookup() {
		String value = unique();
		UUID id = this.service.register(BlocklistKind.PHRASE, value, BlocklistSeverity.BLOCK, "test");
		this.query.findAll();

		this.service.remove(id);

		assertThat(this.query.findAll())
				.extracting(com.neowadaeum.common.spi.BlocklistEntry::normalizedValue)
				.doesNotContain(TextNormalizer.normalize(value));
	}

	/** <b>정규화는 서버가 한다</b> (R2.5). 저장된 것은 원문이 아니다. */
	@Test
	void R2_5_the_server_normalizes_the_value() {
		String value = unique();

		UUID id = this.service.register(BlocklistKind.PHRASE, value, BlocklistSeverity.BLOCK, "test");

		assertThat(this.entries.findById(id)).get().satisfies(row -> {
			assertThat(row.getValue()).isEqualTo(value);
			assertThat(row.getNormalizedValue()).isEqualTo(TextNormalizer.normalize(value));
		});
	}

	/**
	 * <b>{@code warn} 은 판정으로 나가지 않는다</b> (§13-31).
	 *
	 * <p>판정기는 걸린 항목을 곧바로 차단으로 다룬다 — 경고를 함께 내보내면 경고가 차단이 된다.
	 */
	@Test
	void S13_31_a_warning_entry_does_not_reach_the_judge() {
		String value = unique();

		this.service.register(BlocklistKind.PHRASE, value, BlocklistSeverity.WARN, "test");

		assertThat(this.query.findAll())
				.extracting(com.neowadaeum.common.spi.BlocklistEntry::normalizedValue)
				.doesNotContain(TextNormalizer.normalize(value));
	}

	/** 같은 정규화 값은 하나뿐이다 (R2.5). 운영자에게 <b>왜</b> 실패했는지 알린다. */
	@Test
	void R2_5_a_duplicate_normalized_value_is_a_conflict() {
		String value = unique();
		this.service.register(BlocklistKind.PHRASE, value, BlocklistSeverity.BLOCK, "test");

		assertThatThrownBy(() -> this.service.register(BlocklistKind.REAL_PERSON, value,
				BlocklistSeverity.BLOCK, "test"))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.ALREADY_EXISTS);
	}

	/**
	 * 정규화하면 빈 값이 되는 항목은 등록되지 않는다.
	 *
	 * <p>문장부호만으로 이뤄진 항목은 <b>모든 문자열에 걸리거나 아무것에도 걸리지 않는다</b> —
	 * 어느 쪽이든 등록한 사람의 의도가 아니다.
	 */
	@Test
	void R2_5_an_entry_that_normalizes_to_nothing_is_refused() {
		assertThatThrownBy(() -> this.service.register(BlocklistKind.PHRASE, "!!! ??? ...",
				BlocklistSeverity.BLOCK, "test"))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR);
	}

	/** 없는 항목을 지워도 성공이다 — 삭제는 상태를 맞추는 요청이다. */
	@Test
	void R9_4_removing_an_unknown_entry_succeeds() {
		this.service.remove(UUID.randomUUID());
	}

	/** 가상의 값. 테스트끼리 겹치지 않게 한다 (S-11). */
	private static String unique() {
		return "가상항목" + UUID.randomUUID().toString().substring(0, 8);
	}
}
