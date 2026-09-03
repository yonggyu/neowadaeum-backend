package com.neowadaeum.catalog.profile;

import com.neowadaeum.catalog.domain.AuthorProfile;
import com.neowadaeum.catalog.domain.DisplayNames;
import com.neowadaeum.catalog.repository.AuthorProfileRepository;
import com.neowadaeum.common.spi.AuthorDisplayNameWriter;
import com.neowadaeum.common.spi.InvalidDisplayNameException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AuthorDisplayNameWriter} 의 catalog 쪽 구현 (#271, ADR-0002 와 같은 형태).
 *
 * <p><b>데이터를 소유한 모듈이 구현한다.</b> {@code author_profile} 을 읽고 쓸 수 있는 EMF 는
 * catalog 하나이며, 다른 모듈이 직접 쓰려면 스토어 분리를 깨야 한다 (§5.3).
 *
 * <p><b>규칙은 여기서 판정한다</b> — {@link DisplayNames} 가 정본이고 identity 는 그것을 볼 수
 * 없다. 규칙을 요청 쪽에 복사하면 <b>정본이 둘</b>이 되고, 그 둘이 갈라지는 순간 화면이 통과시킨
 * 이름을 DB 가 거절한다.
 *
 * <p><b>트랜잭션 매니저를 명시한다.</b> 후보가 넷이므로 이름 없는 {@code @Transactional} 은
 * 부팅에서 실패한다.
 */
@Component
public class CatalogAuthorDisplayNameWriter implements AuthorDisplayNameWriter {

	private final AuthorProfileRepository profiles;

	private final Clock clock;

	public CatalogAuthorDisplayNameWriter(AuthorProfileRepository profiles, Clock clock) {
		this.profiles = profiles;
		this.clock = clock;
	}

	/**
	 * <b>upsert 다.</b> 처음 정하는 것과 바꾸는 것은 사용자에게 같은 행위이며, 나누면 호출자가
	 * 프로필의 존재 여부를 먼저 물어야 한다 — 그 두 요청 사이에 다른 요청이 끼면 어느 쪽도 맞지
	 * 않는 순간이 생긴다.
	 *
	 * <p><b>검증이 저장보다 먼저다.</b> 규칙에 맞지 않는 이름은 조회조차 하지 않고 돌아간다.
	 * (트랜잭션은 이 메서드에 걸려 있으므로 열리기는 하지만, 아무 문장도 나가지 않는다 —
	 * 검증을 별도 메서드로 빼고 트랜잭션을 안쪽에 걸면 <b>자기 호출이라 프록시를 타지 않는다.</b>)
	 */
	@Override
	@Transactional(transactionManager = "catalogTransactionManager")
	public String updateDisplayName(UUID playerRef, String displayName) {
		String normalized = normalize(displayName);
		Instant now = Instant.now(this.clock);

		this.profiles.findById(playerRef)
				.ifPresentOrElse(profile -> profile.rename(normalized, now),
						() -> this.profiles.save(AuthorProfile.of(playerRef, normalized, now)));
		return normalized;
	}

	/**
	 * <b>도메인의 거절을 경계를 넘는 타입으로 바꾼다.</b> {@code IllegalArgumentException} 을 그대로
	 * 흘려보내면 호출자는 그것이 사용자의 잘못인지 우리 잘못인지 구분할 수 없다.
	 */
	private static String normalize(String displayName) {
		try {
			return DisplayNames.normalize(displayName);
		}
		catch (IllegalArgumentException ex) {
			// DisplayNames 의 문구는 어긴 규칙만 말하며 입력값을 담지 않는다 (S-3).
			throw new InvalidDisplayNameException(ex.getMessage(), ex);
		}
	}
}
