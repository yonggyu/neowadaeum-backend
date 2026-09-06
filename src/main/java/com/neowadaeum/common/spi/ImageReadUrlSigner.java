package com.neowadaeum.common.spi;

import java.util.Optional;

/**
 * 승인된 작품의 이미지를 읽을 수 있는 URL (#378, §13-79).
 *
 * <p><b>승인 후에만 쓴다.</b> 서명 URL 은 그 객체의 <b>출입증</b>이라 수명 동안 게이트 없이
 * 열린다 — 승인 전 이미지에는 내주지 않는다 (I-8). 그쪽은 서버가 바이트를 중계한다 (§13-78).
 *
 * <p><b>계약을 {@code common/spi} 에 두는 이유.</b> 버킷 설정과 서명 능력을 가진 모듈은 이미지를
 * 올리는 {@code authoring} 이고, 그것을 필요로 하는 목록은 {@code catalog} 다. 다른 모듈의
 * 구현을 직접 잡지 않고 <b>데이터를 가진 쪽이 구현한다</b> (ADR-0002 · ADR-0003 과 같은 형태).
 * 버킷 자격증명을 catalog 로 복제하면 <b>설정이 두 곳에 생기고</b> 한쪽이 조용히 낡는다.
 *
 * <p><b>수명은 설정값이다.</b> 카드가 화면에 머무는 시간보다 길어야 하고, 유출됐을 때의 창보다는
 * 짧아야 한다 — 그 균형은 배포마다 다르므로 코드가 정하지 않는다.
 */
public interface ImageReadUrlSigner {

	/**
	 * 이 객체를 읽는 URL 을 서명한다. <b>호출은 계산이며 외부 HTTP 가 아니다</b> — 목록 한 쪽에
	 * 스무 번 불러도 왕복이 늘지 않는다.
	 *
	 * @param objectKey 발급이 만든 객체 키. {@code null} 이거나 빈 값이면 비어 있다
	 * @return 서명된 URL. <b>저장소 설정이 없는 배포에서는 비어 있다</b> — 로컬과 CI 는 저장소를
	 *     갖지 않으며, 그때 목록이 통째로 실패하는 것은 이미지 하나가 없는 것보다 나쁘다
	 */
	Optional<String> signRead(String objectKey);
}
