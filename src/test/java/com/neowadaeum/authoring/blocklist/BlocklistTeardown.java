package com.neowadaeum.authoring.blocklist;

/**
 * 블록리스트 뒷정리 — <b>표와 캐시를 함께 되돌린다</b> (§13-31, 이슈 #211).
 *
 * <p><b>표만 비우면 지워지지 않는다.</b> {@link PersistentBlocklistQuery} 는 스냅샷을 들고 있고
 * 그것을 버리는 것은 쓰기 쪽({@link BlocklistAdminService})이다. 리포지토리로 직접 지우면 그
 * 경로를 거치지 않으므로 <b>DB 에서는 사라졌는데 최대 1분 동안 캐시에는 남아 있다.</b>
 *
 * <p>컨테이너는 한 벌이고 테스트 클래스가 나눠 쓴다. 그 사이에 도는 다른 클래스의 제출·precheck
 * 가 <b>이미 지워진 항목에 걸리고</b>, 그 실패는 블록리스트가 아니라 엉뚱한 기능의 문제처럼
 * 보인다 — PR #210 에서 실제로 그렇게 나타났다.
 *
 * <p><b>규칙을 한 곳에 둔다.</b> 클래스마다 두 줄을 적으면 새로 생기는 클래스에서 한 줄이
 * 빠지고, 빠졌다는 사실은 <b>남의 테스트가 깨질 때</b> 드러난다.
 */
public final class BlocklistTeardown {

	private BlocklistTeardown() {
	}

	/** 표를 비우고 스냅샷을 버린다. 순서가 뒤집히면 버린 직후 옛 행이 다시 읽힌다. */
	public static void clear(BlocklistEntryRepository entries, PersistentBlocklistQuery cache) {
		entries.deleteAll();
		cache.invalidate();
	}
}
