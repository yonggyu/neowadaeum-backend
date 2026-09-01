package com.neowadaeum.catalog.query;

import java.util.Locale;
import java.util.Optional;

/**
 * 섹션 키 (§13.2) — {@code recommended} · {@code genre:<key>} · {@code community}.
 *
 * <p><b>R13.1 — 공식과 사용자를 같은 섹션에 섞지 않는다.</b> 그래서 장르 섹션은 <b>공식 작품만</b>
 * 담는다. 사용자 작품은 {@code community} 하나로 모인다 — 장르별로 다시 쪼개면 섹션이
 * 장르 수의 두 배가 되고, 그 화면은 아직 정의되지 않았다.
 *
 * <p>파싱을 여기 두는 것은 <b>키 문법을 아는 곳을 하나로 두기 위해서</b>다. 컨트롤러와 조회가
 * 각자 알면 그중 하나가 늦게 바뀐다.
 *
 * @param kind     섹션 종류
 * @param genreKey {@link Kind#GENRE} 일 때의 장르 표기. 아니면 {@code null}
 */
public record LibrarySectionKey(Kind kind, String genreKey) {

	private static final String GENRE_PREFIX = "genre:";

	public enum Kind {

		/** 공식 작품 전체. 화면의 첫 섹션이다. */
		RECOMMENDED,

		/** 장르별 공식 작품. */
		GENRE,

		/** 사용자 작품 (R2.3 — 승인 + 비공개 아님). */
		COMMUNITY
	}

	/**
	 * @return 문법에 맞지 않으면 비어 있다 — <b>모르는 키를 빈 섹션으로 흡수하지 않는다.</b>
	 *     흡수하면 오타가 "작품이 없음"으로 보인다
	 */
	public static Optional<LibrarySectionKey> parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		String value = raw.trim().toLowerCase(Locale.ROOT);
		if ("recommended".equals(value)) {
			return Optional.of(new LibrarySectionKey(Kind.RECOMMENDED, null));
		}
		if ("community".equals(value)) {
			return Optional.of(new LibrarySectionKey(Kind.COMMUNITY, null));
		}
		if (value.startsWith(GENRE_PREFIX) && value.length() > GENRE_PREFIX.length()) {
			return Optional.of(new LibrarySectionKey(Kind.GENRE, value.substring(GENRE_PREFIX.length())));
		}
		return Optional.empty();
	}

	/** 응답에 그대로 나가는 표기. */
	public String value() {
		return (this.kind == Kind.GENRE) ? GENRE_PREFIX + this.genreKey
				: this.kind.name().toLowerCase(Locale.ROOT);
	}
}
