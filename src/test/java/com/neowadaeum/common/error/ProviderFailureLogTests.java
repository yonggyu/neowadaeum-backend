package com.neowadaeum.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.neowadaeum.ai.schema.OutlineOutputSchemaException;
import com.neowadaeum.common.spi.OutlineDraftFailedException;
import com.neowadaeum.common.web.ErrorResponse;
import com.neowadaeum.play.port.ProviderCallFailedException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

/**
 * <b>로그에 실제로 무엇이 나가는가</b> (§13-86, #393).
 *
 * <p>#393 이 정한 것은 "타입 사슬만 남긴다"이지만, 그 결정은 <b>사슬이 로그에 닿을 때만</b> 값이
 * 있고 <b>벤더 메시지가 닿지 않을 때만</b> 안전하다. 둘 다 여기서 값으로 건다 — 실제 Logger 에
 * appender 를 붙이고 <b>나간 것</b>을 읽는다. "예외에 사슬이 있다"만 확인하면 절반이다.
 *
 * <p><b>5xx 는 스택트레이스째 나간다</b> ({@code GlobalExceptionHandler.logByStatus}). 즉
 * {@code cause} 의 {@code getMessage()} 도 함께 인쇄된다 — 벤더 예외를 {@code cause} 로 붙이면
 * S-3 가 정확히 거기서 깨진다. 그래서 이 테스트는 {@code getFormattedMessage()} 만이 아니라
 * <b>렌더링된 스택트레이스</b>를 검사한다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class ProviderFailureLogTests {

	/** 벤더 예외의 메시지에 심는 표식. 이것이 로그에 닿으면 S-3 가 깨진 것이다. */
	private static final String VENDOR_MARKER = "http://vendor.invalid/v1/chat key=sk-DO-NOT-LOG body=본문조각";

	private ch.qos.logback.classic.Logger logger;

	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void attachAppender() {
		this.logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
		this.appender = new ListAppender<>();
		this.appender.start();
		this.logger.addAppender(this.appender);
		this.logger.setLevel(Level.TRACE);
	}

	@AfterEach
	void detachAppender() {
		this.logger.detachAppender(this.appender);
	}

	/**
	 * <b>#374 를 첫 재현에 풀 수 있는 만큼이 나간다.</b> 사슬은 로그에 닿고, 벤더 메시지는 닿지
	 * 않는다.
	 */
	@Test
	void SEC3_the_turn_failure_log_carries_the_type_chain_and_not_the_vendor_message() {
		ProviderCallFailedException failure = new ProviderCallFailedException("ollama outline call failed",
				ProviderCallFailedException.typeChainOf(
						new ResourceAccessException(VENDOR_MARKER, new IOException(VENDOR_MARKER))));

		String logged = handleAndRender(new ApiException(ErrorCode.PROVIDER_ERROR, failure));

		assertThat(logged).contains("ResourceAccessException -> IOException");
		assertThat(logged).doesNotContain(VENDOR_MARKER);
	}

	/**
	 * 초안 경로도 같다. 여기가 갈리면 <b>다음 장애에서 다른 자리가 같은 한 줄을 남긴다</b> —
	 * 이 PR 이 한 자리만 고치지 않은 이유다.
	 */
	@Test
	void SEC3_the_outline_failure_log_carries_the_type_chain_and_not_the_vendor_message() {
		OutlineOutputSchemaException violation = new OutlineOutputSchemaException("outline response is not json",
				ProviderCallFailedException.typeChainOf(new IllegalArgumentException(VENDOR_MARKER)));

		String logged = handleAndRender(new ApiException(ErrorCode.PROVIDER_ERROR,
				new OutlineDraftFailedException("outline draft failed", violation)));

		assertThat(logged).contains("outline draft failed").contains("IllegalArgumentException");
		assertThat(logged).doesNotContain(VENDOR_MARKER);
	}

	/**
	 * <b>S-6 — 응답에는 아무것도 늘지 않는다.</b>
	 *
	 * <p>원인을 {@code ApiException} 에 붙인 것이 이 PR 의 변경이고, 그것이 응답을 건드리지 않는
	 * 근거가 이 테스트다. 본문은 {@link ErrorCode} 로만 만들어지므로 사슬도 예외 클래스명도
	 * 담기지 않는다.
	 */
	@Test
	void SEC6_the_error_response_gains_nothing_from_the_attached_cause() {
		ProviderCallFailedException failure = new ProviderCallFailedException("ollama outline call failed",
				ProviderCallFailedException.typeChainOf(new ResourceAccessException(VENDOR_MARKER)));

		ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler()
				.handleApiException(new ApiException(ErrorCode.PROVIDER_ERROR, failure));

		ErrorResponse body = response.getBody();
		assertThat(body).isNotNull();
		assertThat(body.error()).isEqualTo(ErrorCode.PROVIDER_ERROR.code());
		assertThat(body.message()).isEqualTo(ErrorCode.PROVIDER_ERROR.defaultMessage());
		assertThat(body.details()).isEmpty();
		assertThat(body.toString()).doesNotContain(VENDOR_MARKER)
				.doesNotContain("ResourceAccessException")
				.doesNotContain("ollama outline call failed");
	}

	/** 핸들러를 통과시키고 <b>로그로 나간 것</b>을 통째로 문자열로 만든다 — 스택트레이스 포함. */
	private String handleAndRender(ApiException ex) {
		new GlobalExceptionHandler().handleApiException(ex);

		assertThat(this.appender.list).hasSize(1);
		ILoggingEvent event = this.appender.list.getFirst();
		String rendered = event.getFormattedMessage();
		if (event.getThrowableProxy() != null) {
			rendered += "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy());
		}
		return rendered;
	}
}
