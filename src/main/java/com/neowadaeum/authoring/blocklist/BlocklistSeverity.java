package com.neowadaeum.authoring.blocklist;

/**
 * 걸렸을 때 무엇을 하는가 (§2.4).
 *
 * <p><b>{@code warn} 은 세이프티 판정으로 나가지 않는다</b> (§13-31). 판정기는 걸린 항목을
 * 곧바로 차단으로 다루므로, 경고 항목을 함께 내보내면 <b>경고가 차단이 된다.</b>
 *
 * <p>경고 항목의 자리는 작성 중 안내(L0 precheck, B-50)다 — 거기서는 막지 않고 알려 준다.
 */
public enum BlocklistSeverity {

	/** 세이프티 판정에서 차단한다. */
	BLOCK,

	/** 작성자에게 알리기만 한다. 판정으로 나가지 않는다. */
	WARN;

	public String columnValue() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}
