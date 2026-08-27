package com.neowadaeum.common.spi;

import java.util.Optional;

/**
 * 운영 중 바뀌는 설정의 조회 계약 (R11.1, B-14).
 *
 * <p><b>왜 SPI 인가.</b> 값을 소유하는 것은 {@code catalog} 이고 읽어야 하는 것은
 * {@code identity} · {@code play} 다. 셋을 직접 잇는 대신 {@code common} 에 계약을 두고 구현을
 * 데이터 소유 모듈에 둔다 — 블록리스트와 같은 형태다 (ADR-0002).
 *
 * <p><b>구현 빈이 없으면 부팅에 실패한다.</b> 기본 구현을 두지 않는다 — "설정이 없으면 하드코딩된
 * 문구" 같은 폴백을 만드는 순간 R11.1 이 무너진다. 문구가 코드에 없다는 것이 요구사항이다.
 *
 * <p><b>값이 없는 것과 조회가 실패하는 것은 다르다.</b> 전자는 {@link Optional#empty()} 이고,
 * 후자는 예외다. 실패를 빈 값으로 흡수하면 <b>설정하지 않은 것처럼 보인다.</b>
 */
public interface ServiceConfigQuery {

	/**
	 * @param key 설정 키
	 * @return 저장된 값. 없으면 비어 있다
	 */
	Optional<String> find(String key);
}
