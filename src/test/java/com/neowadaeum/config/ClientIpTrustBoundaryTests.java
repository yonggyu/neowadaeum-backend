package com.neowadaeum.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 신뢰 경계가 <b>두 파일에 같은 곳을 가리키는가</b> (§13-45 · §13-93, 이슈 #458).
 *
 * <p><b>같은 사실이 두 표기로 적혀 있다.</b> {@code docker-compose.deploy.yml} 은 네트워크 대역을
 * <b>CIDR</b> 로 고정하고, {@code application.yml.template} 의 {@code prod} 문서는 Tomcat
 * {@code RemoteIpValve} 에 <b>정규식</b>으로 준다 — 밸브가 CIDR 을 받지 않기 때문이다.
 *
 * <p><b>갈라지면 조용히 반대 방향으로 틀린다.</b> 정규식이 넓으면 신뢰하지 않아야 할 곳에서 온
 * 전달 헤더를 읽어 <b>S-8 의 IP 한도와 S-4 의 관리자 IP 제한이 헤더 한 줄로 우회</b>되고,
 * 좁으면 진짜 프록시의 헤더가 버려져 <b>모두가 같은 IP 로 보인다</b>. 어느 쪽이든 부팅은
 * 성공하고 테스트도 초록이다 — 그래서 이 대조가 필요하다.
 *
 * <p><b>값을 읽지 않고 관계를 본다.</b> 대역이 바뀌어도 이 검사는 그대로 통과해야 하고,
 * 한쪽만 바뀌었을 때만 실패해야 한다.
 *
 * <p>컨테이너를 쓰지 않는다 — 파일 둘만 본다.
 */
class ClientIpTrustBoundaryTests {

	private static final String TEMPLATE = "/application.yml.template";

	private static final String COMPOSE = "docker-compose.deploy.yml";

	/**
	 * §13-93 — <b>compose 가 고정한 대역과 밸브가 신뢰하는 대역이 같아야 한다.</b>
	 *
	 * <p>대조 방법은 <b>대역 안의 주소를 실제로 정규식에 물려 보는 것</b>이다. 문자열을 비교하면
	 * 표기가 달라 언제나 다르고, 정규식을 CIDR 로 되돌리는 것은 그 자체가 또 하나의 해석이다.
	 */
	@Test
	void S13_93_the_valve_trusts_exactly_the_pinned_compose_subnet() {
		String cidr = pinnedSubnet();
		Pattern trusted = Pattern.compile(internalProxies());

		String prefix = cidr.substring(0, cidr.indexOf('/'));
		String[] octets = prefix.split("\\.");
		String network = octets[0] + "." + octets[1];

		assertThat(trusted.matcher(network + ".0.2").matches())
				.as("""
						compose 가 고정한 대역(%s) 안의 주소를 밸브가 신뢰하지 않는다.

						그러면 진짜 프록시가 보낸 전달 헤더가 버려지고 모두가 같은 IP 로 보인다 — \
						S-8 의 IP 기준 한도가 서비스 전체에 창 하나가 된다 (#458).

						두 자리를 함께 고친다: docker-compose.deploy.yml 의 subnet 과 \
						application.yml.template 의 prod 문서에 있는 internal-proxies.""", cidr)
				.isTrue();
		assertThat(trusted.matcher(network + ".255.254").matches())
				.as("대역의 다른 끝(%s.255.254)이 신뢰 목록 밖이다 — 정규식이 대역보다 좁다", network)
				.isTrue();
	}

	/**
	 * <b>대역 밖은 신뢰하지 않는다</b> (§13-45).
	 *
	 * <p>*"비어 있음을 전부 신뢰로 읽지 않는다"* 의 반대편이다 — 넓게 적어 두면 그 자체가
	 * 우회 통로가 된다. 공인 주소와 <b>이웃한 사설 대역</b>을 함께 본다: 같은 호스트의 다른
	 * 도커 네트워크가 신뢰 목록에 들어오면 <b>그 컨테이너가 헤더를 위조할 수 있다.</b>
	 */
	@Test
	void S13_45_the_valve_does_not_trust_addresses_outside_the_pinned_subnet() {
		Pattern trusted = Pattern.compile(internalProxies());
		String network = pinnedSubnet().split("\\.")[0] + "." + pinnedSubnet().split("\\.")[1];

		List<String> outside = List.of(
				// 문서용 주소 대역(TEST-NET-3). 실재하는 호스트를 적지 않는다 (S-11).
				"203.0.113.7",
				// 도커가 기본으로 쓰는 앞쪽 대역 — 같은 호스트의 다른 프로젝트가 여기 뜬다.
				"172.17.0.2", "172.18.0.2",
				// 다른 사설 대역.
				"10.0.0.2", "192.168.0.2");

		assertThat(outside)
				.as("고정 대역은 %s 다 — 그 밖의 주소가 신뢰 목록에 들어오면 전달 헤더 위조가 통한다", network)
				.allSatisfy(address -> assertThat(trusted.matcher(address).matches())
						.as("%s 를 신뢰한다", address)
						.isFalse());
	}

	/**
	 * <b>{@code prod} 에서만 켠다</b> (§13-93).
	 *
	 * <p>로컬·dev 에는 앞단이 없다. 없는데 전달 헤더를 읽으면 <b>그것이 곧 위조 통로다</b> —
	 * 아무나 헤더를 붙여 자기 주소를 바꿀 수 있다. 프로파일이 지정되지 않은 배포도 같다.
	 */
	@Test
	void S13_93_forwarded_headers_are_read_only_under_the_prod_profile() {
		List<Map<String, Object>> documents = documents(TEMPLATE);

		assertThat(documents)
				.filteredOn(document -> flatten(document).containsKey("server.forward-headers-strategy"))
				.as("전달 헤더 전략이 어느 문서에도 없거나 둘 이상에 있다 (#458)")
				.singleElement()
				.satisfies(document -> assertThat(flatten(document).get("spring.config.activate.on-profile"))
						.as("""
								전달 헤더 전략이 prod 조건 밖에 있다 (#458).

								앞단이 없는 환경에서 전달 헤더를 읽으면 아무나 헤더 한 줄로 자기 \
								주소를 바꿀 수 있다 — S-8 의 IP 한도와 S-4 의 관리자 IP 제한이 \
								그 순간 무의미해진다.""")
						.isEqualTo("prod"));
	}

	/** compose 가 고정한 대역. <b>없으면 도커가 순차 할당해 배포마다 달라진다.</b> */
	private static String pinnedSubnet() {
		Map<String, Object> compose = documents(COMPOSE).getFirst();
		Object subnet = flatten(compose).get("networks.default.ipam.config[0].subnet");
		assertThat(subnet)
				.as("%s 가 네트워크 대역을 고정하지 않았다 — 도커가 정하면 배포마다 달라진다 (#458)", COMPOSE)
				.isNotNull();
		return String.valueOf(subnet);
	}

	/** {@code prod} 문서가 밸브에 주는 정규식. */
	private static String internalProxies() {
		for (Map<String, Object> document : documents(TEMPLATE)) {
			Object value = flatten(document).get("server.tomcat.remoteip.internal-proxies");
			if (value != null) {
				return String.valueOf(value);
			}
		}
		throw new AssertionError(
				TEMPLATE + " 에 server.tomcat.remoteip.internal-proxies 가 없다 — 밸브가 무엇을 신뢰할지 모른다 (#458)");
	}

	/**
	 * <b>클래스패스와 작업 디렉터리를 갈라 읽는다.</b> 템플릿은 리소스이므로 클래스패스에 있고,
	 * compose 는 레포 루트의 파일이라 그 길이 없다.
	 */
	private static List<Map<String, Object>> documents(String resourceOrPath) {
		List<Map<String, Object>> documents = new java.util.ArrayList<>();
		try (InputStream stream = open(resourceOrPath)) {
			for (Object loaded : new Yaml().loadAll(stream)) {
				if (loaded instanceof Map<?, ?> document) {
					Map<String, Object> typed = new LinkedHashMap<>();
					document.forEach((key, value) -> typed.put(String.valueOf(key), value));
					documents.add(typed);
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		assertThat(documents).as("%s 에서 읽은 YAML 문서가 없다", resourceOrPath).isNotEmpty();
		return documents;
	}

	private static InputStream open(String resourceOrPath) throws IOException {
		if (resourceOrPath.startsWith("/")) {
			InputStream stream = ClientIpTrustBoundaryTests.class.getResourceAsStream(resourceOrPath);
			assertThat(stream).as("%s 가 클래스패스에 없다", resourceOrPath).isNotNull();
			return stream;
		}
		java.nio.file.Path path = java.nio.file.Path.of(resourceOrPath);
		assertThat(java.nio.file.Files.exists(path)).as("%s 가 없다", resourceOrPath).isTrue();
		return java.nio.file.Files.newInputStream(path);
	}

	/** 중첩 맵과 리스트를 점 표기 경로로 편다. 리스트는 {@code [i]} 로 센다. */
	private static Map<String, Object> flatten(Map<String, Object> document) {
		Map<String, Object> leaves = new LinkedHashMap<>();
		flatten("", document, leaves);
		return leaves;
	}

	private static void flatten(String prefix, Object node, Map<String, Object> leaves) {
		if (node instanceof Map<?, ?> map && !map.isEmpty()) {
			map.forEach((key, value) -> flatten(prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key,
					value, leaves));
			return;
		}
		if (node instanceof List<?> list && !list.isEmpty()) {
			for (int index = 0; index < list.size(); index++) {
				flatten(prefix + "[" + index + "]", list.get(index), leaves);
			}
			return;
		}
		leaves.put(prefix, node);
	}
}
