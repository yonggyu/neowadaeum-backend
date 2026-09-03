package com.neowadaeum.common.spi;

import java.util.UUID;

/**
 * 표시명 설정·변경 (#271, §13-7).
 *
 * <p><b>{@link AuthorDisplayNameQuery} 에 메서드를 더하지 않고 따로 둔다.</b> 읽기 계약은 이미
 * 읽기 전용 트랜잭션으로 구현돼 있고, 거기에 쓰기를 섞으면 <b>이름을 읽기만 하면 되는 모든
 * 호출자가 쓰는 능력을 함께 얻는다</b> — 지금 그 호출자는 작품 상세 · 커뮤니티 카드 ·
 * {@code GET /me} 셋이다. {@code common/spi} 는 이미 이 방향으로 갈라 둔 선례를 갖는다:
 * {@link AiNoticeQuery} 와 {@link AiNoticeRecorder} 가 한 데이터를 읽고 쓰면서도 서로 다른
 * 인터페이스다.
 *
 * <p><b>구현은 데이터를 소유한 catalog 에 있다</b> (ADR-0002). {@code author_profile} 은 catalog
 * 스키마에 있고, identity 가 그 표를 직접 쓰면 스토어 분리가 깨진다 (§5.3).
 *
 * <p><b>{@code playerRef} 만 넘어온다</b> (I-3). {@code user.id} · 이메일 · 소셜 식별자는 이 경계를
 * 넘지 않으며, {@code author_profile} 에는 그것을 담을 컬럼이 아예 없다.
 */
public interface AuthorDisplayNameWriter {

	/**
	 * 없으면 만들고 있으면 바꾼다.
	 *
	 * <p><b>두 경우를 한 경로로 다룬다.</b> "처음 정하기"와 "바꾸기"를 나누면 호출자가 프로필의
	 * 존재 여부를 먼저 물어야 하고, 그 두 요청 사이에 다른 요청이 끼면 <b>어느 쪽도 맞지 않는
	 * 순간</b>이 생긴다.
	 *
	 * @param playerRef 이름의 주인
	 * @param displayName 사용자가 입력한 값. 정규화는 구현이 한다
	 * @return 실제로 저장된 값. 정규화 결과이므로 입력과 다를 수 있다
	 * @throws InvalidDisplayNameException 규칙에 맞지 않는다
	 */
	String updateDisplayName(UUID playerRef, String displayName);
}
