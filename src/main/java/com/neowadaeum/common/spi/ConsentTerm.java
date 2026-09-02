package com.neowadaeum.common.spi;

/**
 * 지금 동의를 받아야 할 약관 한 건 (R10.2, §13-22).
 *
 * <p><b>판본이 코드에 없다.</b> 약관은 개정되고, 개정될 때마다 배포가 필요하면 늦는다 —
 * 그리고 늦는 동안 <b>동의 이력에 옛 판본이 기록된다.</b> 그것은 법적 증빙이 틀리는 방식이며
 * 서버가 판본을 검증하지 않으면 조용히 틀린다 (이슈 #261).
 *
 * @param version     지금 유효한 판본. <b>동의 이력이 가리키는 값</b>이다 (R10.2)
 * @param documentUrl 약관 본문의 주소. <b>없을 수 있다</b> — 본문을 화면이 이미 들고 있는
 *                    종류(AI 사전 고지)와 문서가 존재하지 않는 종류(연령 확인)가 있다
 */
public record ConsentTerm(String version, String documentUrl) {

	public ConsentTerm {
		if (version == null || version.isBlank()) {
			// 판본 없는 약관은 되돌려 받아도 증빙이 되지 못한다.
			throw new IllegalArgumentException("version is required");
		}
	}
}
