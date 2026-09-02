package com.neowadaeum.common.spi;

import java.util.Optional;
import java.util.UUID;

/**
 * 표시명 조회 (§13-7, #262).
 *
 * <p><b>왜 SPI 인가.</b> 표시명을 소유하는 것은 {@code catalog} 이고({@code author_profile}),
 * 읽어야 하는 것은 {@code identity} 다 — 내 계정 화면이 이름을 보여 줘야 한다. {@code identity} 의
 * 허용 의존은 {@code common} 하나이므로 catalog 를 직접 부를 수 없고, 부를 수 있게 열면 스토어
 * 경계가 무너진다. 그래서 계약을 {@code common} 에 두고 <b>구현을 데이터 소유 모듈에</b> 둔다 —
 * {@link ServiceConfigQuery} · {@link AiNoticeQuery} 와 같은 형태다 (ADR-0002).
 *
 * <p><b>{@code playerRef} 를 넣고 이름을 받는다.</b> 회원 식별자는 이 경계를 넘지 않는다 (I-3).
 * 표시명은 회원 식별정보가 아니라 <b>공개 표시명</b>이며, 그것이 catalog 에 있는 이유이기도 하다.
 *
 * <p><b>없으면 비어 있다 — 기본 이름을 만들지 않는다.</b> 프로필을 설정하지 않은 회원은 이름이
 * 없는 것이 사실이고, 서버가 "이름 없음" 같은 문구를 지어내면 화면은 <b>그것을 사용자가 정한
 * 이름과 구분하지 못한다.</b>
 */
public interface AuthorDisplayNameQuery {

	/**
	 * @param playerRef 조회 대상
	 * @return 설정된 표시명. 프로필이 없으면 비어 있다
	 */
	Optional<String> findDisplayName(UUID playerRef);
}
