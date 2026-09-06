package com.neowadaeum;

import java.lang.reflect.Method;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * <b>뒷정리 실패를 원인 자리에서 크게 터뜨린다</b> (#394).
 *
 * <p><b>무엇이 문제였나.</b> 통합 테스트의 {@code @AfterEach} 는 대개 하나의 긴 {@code clear()} 다.
 * 그 안에서 부모 행 삭제가 FK 로 막히면 <b>그 뒤의 구문이 실행되지 않는다.</b> 지워지지 않은 행은
 * 다음 테스트로 넘어가고, 실패는 <b>엉뚱한 자리에서</b> 나타난다 — 이틀 새 두 번 그랬다
 * ({@code story_version_genre} #371 · {@code character} #386). 두 번 다 표를 처음 쓰는 테스트가
 * 들어오는 날 드러났고, 두 번째 사례에서 보인 것은 <i>"제출이 반려됐다"</i> 였다. 그것을 쓴 사람은
 * 자기 코드를 의심했다.
 *
 * <p><b>JUnit 이 이미 하는 것과 하지 않는 것.</b> {@code @AfterEach} 가 던지면 JUnit 은 그 테스트를
 * 실패시킨다 — 거기까지는 이미 된다. 하지 않는 것은 <b>그것이 뒷정리라고 말해 주는 일</b>과
 * <b>뒤이은 실패를 앞선 뒷정리에 연결해 주는 일</b>이다. 실패 목록에 {@code DataIntegrityViolationException}
 * 하나와 관계없어 보이는 실패 하나가 나란히 뜨면, 읽는 사람은 둘이 같은 사건이라는 것을 알 수 없다.
 * 그 연결이 이 확장이 더하는 전부다.
 *
 * <p><b>왜 공용 뒷정리 자리를 만들지 않았나.</b> 스키마별 표 목록을 FK 역순으로 훑는 자리를 두면
 * 그 목록도 손으로 적은 것이 되어 같은 문제가 한 칸 옮겨간다. 여기서 고르는 것은 <b>증상을 원인
 * 옆으로 옮기는 쪽</b>이다 — 뒷정리의 FK 누락 자체는 이 규칙이 앞으로 드러낸다.
 *
 * <p><b>{@link ContainerTestBase} 한 자리에 건다.</b> 컨테이너 통합 테스트는 전부 그 클래스를
 * 상속하므로 (그리고 {@code @Tag("container")} 는 그 클래스에만 있다) 개별 테스트 파일을 하나도
 * 건드리지 않고 규칙이 선다. 확장 등록은 Spring 의 컨텍스트 캐시 키에 들어가지 않으므로 컨텍스트가
 * 한 벌 더 뜨지 않는다.
 */
class LoudCleanupFailures implements InvocationInterceptor {

	/**
	 * 이 JVM 에서 <b>처음</b> 실패한 뒷정리의 자리. 순서 의존 실패의 원인이 여기 남는다.
	 *
	 * <p>처음 것만 잡아 둔다 — 뒤따르는 뒷정리 실패는 대개 첫 실패가 남긴 행의 결과이고,
	 * 읽는 사람이 가야 할 곳은 사슬의 머리다.
	 */
	private static volatile String firstCleanupFailure;

	@Override
	public void interceptAfterEachMethod(Invocation<Void> invocation,
			ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext)
			throws Throwable {
		try {
			invocation.proceed();
		}
		catch (Throwable failure) {
			String where = signatureOf(invocationContext);
			if (firstCleanupFailure == null) {
				firstCleanupFailure = where;
			}
			throw new AssertionError(cleanupFailureMessage(where), failure);
		}
	}

	@Override
	public void interceptTestMethod(Invocation<Void> invocation,
			ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext)
			throws Throwable {
		proceedAndPointAtEarlierCleanup(invocation);
	}

	@Override
	public void interceptTestTemplateMethod(Invocation<Void> invocation,
			ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext)
			throws Throwable {
		proceedAndPointAtEarlierCleanup(invocation);
	}

	/**
	 * 실패한 테스트에 <b>앞선 뒷정리 실패로 가는 표지</b>를 붙인다.
	 *
	 * <p>앞선 뒷정리 실패가 없으면 원래 예외를 그대로 던진다 — <b>초록 스위트에서는 이 확장이
	 * 아무것도 바꾸지 않는다.</b>
	 */
	private void proceedAndPointAtEarlierCleanup(Invocation<Void> invocation) throws Throwable {
		try {
			invocation.proceed();
		}
		catch (Throwable failure) {
			String earlier = firstCleanupFailure;
			if (earlier == null) {
				throw failure;
			}
			throw new AssertionError(contaminatedRunMessage(earlier), failure);
		}
	}

	private static String signatureOf(ReflectiveInvocationContext<Method> invocationContext) {
		Method method = invocationContext.getExecutable();
		return method.getDeclaringClass().getName() + "#" + method.getName();
	}

	/** 실패한 뒷정리 자신에게 붙는 문구. */
	static String cleanupFailureMessage(String where) {
		return """
				뒷정리가 실패했다 — 이 테스트의 단언이 아니다 (#394).
				  실패한 자리: %s
				  그 뒤의 뒷정리 구문은 실행되지 않았다. 지워지지 않은 행이 다음 테스트로 넘어가
				  엉뚱한 자리에서 실패로 나타난다.
				  FK 자식 표를 부모보다 먼저 지우는지 확인하라.""".formatted(where);
	}

	/** 앞선 뒷정리가 실패한 뒤에 실패한 테스트에 붙는 문구. */
	static String contaminatedRunMessage(String earlierCleanup) {
		return """
				앞선 테스트의 뒷정리가 실패했다 — 이 실패의 원인은 여기가 아닐 수 있다 (#394).
				  먼저 실패한 뒷정리: %s
				  거기서 지워지지 않은 행이 남아 있다. 그쪽을 먼저 고치고 다시 보라.""".formatted(earlierCleanup);
	}

	/** 테스트가 이 확장의 정적 상태를 되돌린다. 프로덕션 경로에는 쓰이지 않는다. */
	static void resetForTests() {
		firstCleanupFailure = null;
	}
}
