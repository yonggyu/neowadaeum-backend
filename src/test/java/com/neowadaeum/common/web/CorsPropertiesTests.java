package com.neowadaeum.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #248 — <b>허용 오리진은 설정이고, 빠뜨리면 부팅이 멈춘다.</b>
 *
 * <p>여기서 지키는 것은 하나다 — <b>잘못된 CORS 설정은 서버가 아니라 남의 브라우저에서
 * 드러난다.</b> 그 실패는 서버 로그에 남지 않으므로, 부팅 시점으로 당겨 온다 (§7.3).
 *
 * <p><b>S-11 — 운영 오리진을 적지 않는다.</b> 픽스처는 전부 로컬 주소다.
 */
class CorsPropertiesTests {

	/** 값이 없으면 부팅이 멈춘다 (§7.3). 빈 목록을 "아무도 허용 안 함"으로 읽지 않는다. */
	@Test
	void S7_3_missing_origins_stop_the_boot() {
		assertThatThrownBy(() -> new CorsProperties(null)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new CorsProperties(List.of()))
				.isInstanceOf(IllegalStateException.class);
	}

	/**
	 * <b>와일드카드를 받지 않는다.</b>
	 *
	 * <p>{@code *} 는 "누구나"이고, 이 API 는 회원의 플레이 기록과 저작 원고를 다룬다 (I-8).
	 */
	@ParameterizedTest
	@ValueSource(strings = { "*", "http://*.example.test", "https://*" })
	void I8_wildcards_are_refused(String origin) {
		assertThatThrownBy(() -> new CorsProperties(List.of(origin)))
				.isInstanceOf(IllegalStateException.class);
	}

	/**
	 * <b>오리진에는 경로가 없다.</b>
	 *
	 * <p>브라우저가 보내는 {@code Origin} 은 스킴·호스트·포트뿐이다. 경로나 끝 슬래시를 적으면
	 * <b>어떤 요청과도 일치하지 않고</b>, 그 사실이 브라우저에서야 드러난다.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "http://localhost:5173/", "http://localhost:5173/app", "localhost:5173",
			"   " })
	void S248_an_origin_is_scheme_host_port_only(String origin) {
		assertThatThrownBy(() -> new CorsProperties(List.of(origin)))
				.isInstanceOf(IllegalStateException.class);
	}

	/** 제대로 된 값은 그대로 통과한다. 포트가 없어도 된다. */
	@Test
	void S248_concrete_origins_are_accepted() {
		CorsProperties properties =
				new CorsProperties(List.of("http://localhost:5173", "https://app.example.test"));

		assertThat(properties.allowedOrigins())
				.containsExactly("http://localhost:5173", "https://app.example.test");
	}
}
