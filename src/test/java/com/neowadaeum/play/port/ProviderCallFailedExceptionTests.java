package com.neowadaeum.play.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ai.schema.OutlineOutputSchemaException;
import com.neowadaeum.ai.schema.TurnOutputSchemaException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * <b>벤더 장애가 한 줄로 사라지지 않게 한다</b> (§13-86, #393).
 *
 * <p>#374 의 최초 보고는 {@code ollama outline call failed} 한 줄이었고, 실제 원인은 <b>임시로
 * 원인을 붙여 다시 재현할 때까지</b> 보이지 않았다 — 그것이 그 이슈를 이틀 걸리게 했다.
 *
 * <p>그렇다고 원인 예외를 통째로 실을 수는 없다 (S-3). 벤더 예외의 {@code getMessage()} 에는
 * URL · 응답 본문 조각 · 헤더가 실린다. 그래서 남기는 것은 <b>타입 이름의 사슬</b>뿐이며,
 * 여기서 고정하는 것은 그 둘 — <b>사슬은 남고 메시지는 남지 않는다</b> — 이다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class ProviderCallFailedExceptionTests {

	/** 벤더 예외의 메시지에 심는 표식. 이것이 어디로도 새지 않아야 한다. */
	private static final String VENDOR_MARKER = "http://vendor.invalid/v1/chat key=sk-DO-NOT-LOG body=본문조각";

	/** §13-86 — 사슬은 링크마다 타입 이름을 잇는다. #374 를 첫 재현에 풀기에 이만큼이면 됐다. */
	@Test
	void S13_86_the_chain_names_every_link_of_the_cause() {
		Throwable vendorFailure = new ResourceAccessException(VENDOR_MARKER, new IOException(VENDOR_MARKER));

		assertThat(ProviderCallFailedException.typeChainOf(vendorFailure))
				.isEqualTo("ResourceAccessException -> IOException");
	}

	/**
	 * <b>S-3 — 벤더 메시지는 어느 경로로도 나가지 않는다.</b>
	 *
	 * <p>메시지만 보는 단언은 절반이다. 예외가 로그로 흐르는 방식은 스택트레이스이고, 거기에는
	 * {@code cause} 의 메시지가 함께 인쇄된다 — 그래서 {@code getCause()} 도 함께 건다.
	 */
	@Test
	void SEC3_the_vendor_message_reaches_neither_the_message_nor_a_cause() {
		Throwable vendorFailure = new ResourceAccessException(VENDOR_MARKER, new IOException(VENDOR_MARKER));

		ProviderCallFailedException failure = new ProviderCallFailedException("ollama outline call failed",
				ProviderCallFailedException.typeChainOf(vendorFailure));

		assertThat(failure).hasMessageContaining("ollama outline call failed")
				.hasMessageContaining("ResourceAccessException -> IOException")
				.hasMessageNotContaining(VENDOR_MARKER);
		assertThat(failure.getCause()).isNull();
		assertThat(failure.causeChain()).doesNotContain(VENDOR_MARKER);
	}

	/**
	 * <b>감싸도 아래가 잘리지 않는다.</b> 원인이 붙어 있지 않으므로 다시 계산할 수 없고, 들고 있는
	 * 사슬을 이어 붙이지 않으면 한 겹 감싸는 순간 §13-86 이 되돌린 그 한 줄로 돌아간다.
	 */
	@Test
	void S13_86_wrapping_a_provider_failure_keeps_the_chain_it_carries() {
		ProviderCallFailedException inner = new ProviderCallFailedException("ollama outline call failed",
				ProviderCallFailedException.typeChainOf(new ResourceAccessException(VENDOR_MARKER)));

		assertThat(ProviderCallFailedException.typeChainOf(inner))
				.isEqualTo("ProviderCallFailedException -> ResourceAccessException");
	}

	/** 원인을 받지 못한 자리는 <b>"원인이 없다"가 아니라 "모른다"</b>이다. 빈 칸으로 두지 않는다. */
	@Test
	void S13_86_a_missing_cause_is_named_rather_than_left_blank() {
		assertThat(ProviderCallFailedException.typeChainOf(null)).isEqualTo("unknown");
		assertThat(new ProviderCallFailedException("no ollama model configured").causeChain())
				.isEqualTo("unknown");
	}

	/** 자기를 원인으로 갖는 예외를 만나도 사슬은 끝난다. 로그 한 줄이 무한히 길어지지 않는다. */
	@Test
	void S13_86_a_self_referencing_cause_does_not_loop() {
		IOException loop = new IOException(VENDOR_MARKER) {
			@Override
			public synchronized Throwable getCause() {
				return this;
			}
		};

		assertThat(ProviderCallFailedException.typeChainOf(loop)).doesNotContain(VENDOR_MARKER);
		assertThat(ProviderCallFailedException.typeChainOf(loop).split(" -> ")).hasSizeLessThanOrEqualTo(8);
	}

	/**
	 * <b>S-3 — 규약이 아니라 구조로 막는다.</b>
	 *
	 * <p>{@code TurnOutputParser} 는 처음부터 파서 예외를 붙이지 않았지만 형제인
	 * {@code OutlineOutputSchemaException} 에서는 붙어 있었다 — 생성자가 열려 있는 한 <b>다음
	 * 사람이 붙인다.</b> 벤더 실패를 나르는 예외에 {@code Throwable} 생성자를 두지 않는 것이
	 * 그것을 컴파일 단계에서 끝낸다.
	 */
	@Test
	void SEC3_the_failure_types_take_a_type_chain_and_never_a_throwable() {
		List<Class<?>> carriers = List.of(ProviderCallFailedException.class, TurnOutputSchemaException.class,
				OutlineOutputSchemaException.class);

		for (Class<?> carrier : carriers) {
			for (Constructor<?> constructor : carrier.getConstructors()) {
				assertThat(constructor.getParameterTypes())
						.as("%s 의 생성자는 Throwable 을 받지 않는다 (§13-86)", carrier.getSimpleName())
						.noneMatch(Throwable.class::isAssignableFrom);
			}
		}
	}
}
