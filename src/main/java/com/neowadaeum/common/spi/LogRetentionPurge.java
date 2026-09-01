package com.neowadaeum.common.spi;

/**
 * 보관 기간이 지난 로그를 지운다 (R12.4, S-10, B-61, ADR-0003).
 *
 * <p><b>약관이 이미 그렇게 적혀 있다.</b> 지운다고 적어 두고 지우지 않으면 그것은 미구현이
 * 아니라 <b>거짓 고지</b>다 — S-10 이 "파기 배치를 <b>실제로 구현하고 테스트한다</b>" 를
 * 명시한 이유다.
 *
 * <p><b>구현은 {@code ai} 다.</b> 세 표가 전부 {@code promptlog} 스토어에 있고 그 EMF 를 가진
 * 모듈은 {@code ai} 하나다 (§5.3) — ADR-0003 의 목록은 감사 로그 파기를 {@code admin} 으로
 * 적었지만, 그 모듈은 이 스토어에 닿을 수 없다. <b>스토어 소유가 이긴다.</b>
 *
 * <p><b>{@code batch} 는 이 메서드 하나만 부른다.</b> 무엇을 얼마나 지우는지는 구현과 설정이
 * 정하고, batch 가 아는 것은 <b>언제 부르는가</b>뿐이다.
 *
 * @see SessionExpiry
 */
public interface LogRetentionPurge {

	/**
	 * 기간이 지난 로그를 한 차례 지운다.
	 *
	 * @return 지운 행 수. batch 가 구조화 로그에 남긴다 (§9.4)
	 */
	int purgeExpiredLogs();
}
