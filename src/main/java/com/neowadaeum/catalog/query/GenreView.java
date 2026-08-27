package com.neowadaeum.catalog.query;

/**
 * 장르 하나 (§13.2 의 {@code Genre}).
 *
 * <p><b>{@code genreId} 는 API 표기({@code key}) 다.</b> 계약이 문자열로 두었고, 섹션 키
 * {@code genre:<key>} 가 같은 값을 쓴다 — UUID 를 주면 클라이언트가 두 식별자를 들고 다녀야 한다.
 */
public record GenreView(String genreId, String label) {
}
