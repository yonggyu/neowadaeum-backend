package com.neowadaeum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.InvocationInterceptor.Invocation;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * <b>뒷정리 실패가 원인 자리에서 터지는지</b> 값으로 확인한다 (#394).
 *
 * <p>근거는 조항이 아니라 이슈다 — 계약이나 도메인 규칙이 아니라 <b>테스트 스위트 자신의 규칙</b>이라
 * 정본이 이슈 #394 와 {@code .claude/rules/testing.md} 다. 그래서 이름의 접두어도 이슈 번호다
 * ({@code ADR0006_} · {@code B02_} 와 같은 "체계 + 번호" 꼴).
 *
 * <p>이 클래스는 {@link ContainerTestBase} 를 상속하지 않는다. 확장의 동작은 컨테이너 없이 확인되고,
 * 상속하면 확장이 자기 자신 위에서 돌아 무엇을 보는지가 흐려진다.
 */
class LoudCleanupFailuresTests {

	private final LoudCleanupFailures rule = new LoudCleanupFailures();

	@BeforeEach
	void forgetEarlierRuns() {
		LoudCleanupFailures.resetForTests();
	}

	/**
	 * <b>실패가 뒷정리라고 말한다.</b>
	 *
	 * <p>JUnit 은 이미 그 테스트를 실패시킨다. 하지 않는 것은 <i>그것이 뒷정리였다</i>고 말해 주는
	 * 일이다 — 읽는 사람이 자기 단언을 의심하며 시간을 쓰는 지점이 정확히 거기다 (#372).
	 */
	@Test
	void GH394_a_broken_cleanup_fails_the_test_that_broke_it_and_says_it_is_cleanup() {
		assertThatThrownBy(() -> this.rule.interceptAfterEachMethod(throwing(new IllegalStateException("FK")),
				invocationOf("clear"), null))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("뒷정리가 실패했다")
			.hasMessageContaining(FakeCleanup.class.getName() + "#clear")
			.hasMessageContaining("그 뒤의 뒷정리 구문은 실행되지 않았다")
			.hasRootCauseMessage("FK");
	}

	/**
	 * <b>뒤이은 실패가 앞선 뒷정리를 가리킨다.</b>
	 *
	 * <p>#371 · #386 에서 사람이 실제로 본 것은 이 두 번째 실패였다. 표지가 없으면 두 실패가 같은
	 * 사건이라는 사실이 실패 목록에 드러나지 않는다.
	 */
	@Test
	void GH394_a_failure_after_a_broken_cleanup_points_back_at_it() throws Throwable {
		swallow(() -> this.rule.interceptAfterEachMethod(throwing(new IllegalStateException("FK")),
				invocationOf("clear"), null));

		assertThatThrownBy(() -> this.rule.interceptTestMethod(throwing(new AssertionError("제출이 반려됐다")),
				invocationOf("someTest"), null))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("앞선 테스트의 뒷정리가 실패했다")
			.hasMessageContaining(FakeCleanup.class.getName() + "#clear")
			.hasRootCauseMessage("제출이 반려됐다");
	}

	/**
	 * <b>초록 스위트에서는 아무것도 바뀌지 않는다.</b> 앞선 뒷정리 실패가 없으면 원래 예외가 그대로
	 * 올라간다 — 표지를 붙이느라 평범한 실패의 모양을 바꾸지 않는다.
	 */
	@Test
	void GH394_a_test_failure_without_an_earlier_broken_cleanup_is_left_alone() {
		AssertionError original = new AssertionError("평범한 단언 실패");

		assertThatThrownBy(() -> this.rule.interceptTestMethod(throwing(original), invocationOf("someTest"), null))
			.isSameAs(original);
	}

	/**
	 * <b>규칙이 걸린 자리.</b> {@link ContainerTestBase} 에서 떨어지면 컨테이너 통합 테스트 전체가
	 * 조용해진다 — 개별 파일에는 아무것도 적혀 있지 않기 때문이다.
	 */
	@Test
	void GH394_the_rule_hangs_on_the_single_container_test_base() {
		ExtendWith registered = ContainerTestBase.class.getAnnotation(ExtendWith.class);

		assertThat(registered).isNotNull();
		assertThat(registered.value()).contains(LoudCleanupFailures.class);
	}

	private static Invocation<Void> throwing(Throwable failure) {
		return new Invocation<>() {
			@Override
			public Void proceed() throws Throwable {
				throw failure;
			}

			@Override
			public void skip() {
			}
		};
	}

	private static ReflectiveInvocationContext<Method> invocationOf(String methodName) {
		Method method;
		try {
			method = FakeCleanup.class.getDeclaredMethod(methodName);
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException(ex);
		}
		return new ReflectiveInvocationContext<>() {
			@Override
			public Class<?> getTargetClass() {
				return FakeCleanup.class;
			}

			@Override
			public Optional<Object> getTarget() {
				return Optional.empty();
			}

			@Override
			public Method getExecutable() {
				return method;
			}

			@Override
			public List<Object> getArguments() {
				return List.of();
			}
		};
	}

	private static void swallow(ThrowingCall call) {
		try {
			call.run();
		}
		catch (Throwable expected) {
			// 첫 뒷정리 실패를 기록시키기 위한 호출이다. 그 예외 자체는 앞의 테스트가 이미 본다.
		}
	}

	@FunctionalInterface
	private interface ThrowingCall {

		void run() throws Throwable;

	}

	/** 메시지에 실릴 이름을 만들기 위한 자리. {@link InvocationInterceptor} 는 여기까지만 본다. */
	private static final class FakeCleanup {

		void clear() {
		}

		void someTest() {
		}

	}

}
