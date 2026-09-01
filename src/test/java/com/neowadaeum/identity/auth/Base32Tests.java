package com.neowadaeum.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Base32 인코딩 (RFC 4648).
 *
 * <p>규격의 시험 벡터로 고정한다 — 이 표기를 읽는 것은 우리가 아니라 인증기 앱이다.
 */
class Base32Tests {

	/** RFC 4648 §10 의 시험 벡터. 단, 패딩('=')은 붙이지 않는다. */
	@Test
	void R14_6_matches_the_specification_test_vectors() {
		assertThat(encode("")).isEmpty();
		assertThat(encode("f")).isEqualTo("MY");
		assertThat(encode("fo")).isEqualTo("MZXQ");
		assertThat(encode("foo")).isEqualTo("MZXW6");
		assertThat(encode("foob")).isEqualTo("MZXW6YQ");
		assertThat(encode("fooba")).isEqualTo("MZXW6YTB");
		assertThat(encode("foobar")).isEqualTo("MZXW6YTBOI");
	}

	/** <b>패딩을 붙이지 않는다.</b> 인증기 앱이 '=' 를 비밀의 일부로 읽는 경우가 있다. */
	@Test
	void R14_6_never_pads() {
		assertThat(encode("f")).doesNotContain("=");
		assertThat(encode("foob")).doesNotContain("=");
	}

	/** 알파벳은 대문자와 2~7 뿐이다 — 0/O 와 1/I 를 섞어 읽는 자리를 만들지 않는다. */
	@Test
	void R14_6_uses_only_the_rfc_alphabet() {
		assertThat(encode("여기에 무엇이 오든")).matches("[A-Z2-7]*");
	}

	private String encode(String value) {
		return Base32.encode(value.getBytes(StandardCharsets.UTF_8));
	}
}
