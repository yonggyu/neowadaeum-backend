package com.neowadaeum.play.port;

import org.jspecify.annotations.Nullable;

/**
 * Provider 호출 자체가 실패했다 — 연결 · 인증 · 4xx · 5xx (B-22).
 *
 * <p><b>스키마 위반과 구분한다.</b> {@link OutputSchemaRejectedException} 은 다시 요청해 볼 만하지만
 * (R5.8), 인증 실패나 연결 실패는 같은 요청을 다시 보내 나아지지 않는다 — 재요청은 비용만 쓴다.
 *
 * <p>호출자는 이것을 {@code 502 PROVIDER_ERROR} 로 바꾼다. 시간 초과와 같은 자리에서 끊기므로
 * 세션 상태는 그대로다 (R6.6).
 *
 * <p><b>포트 패키지에 사는 이유는 다른 두 예외와 같다</b> (ADR-0006). {@code play} 가 어댑터의
 * 타입을 잡아야 한다면 그 순간 의존이 양방향이 된다.
 *
 * <p><b>벤더별로 나누지 않는다.</b> {@code play} 는 어느 벤더가 실패했는지로 분기하지 않는다 —
 * 응답은 어느 쪽이든 502 다. 벤더를 구분해야 하는 것은 fallback 체인(B-23)이고 그것은
 * {@code ai} 안쪽의 관심사다.
 *
 * <p><b>남기는 것은 예외 타입 이름의 사슬뿐이다</b> (§13-86). 무엇이 아래에 있었는지를 말하지
 * 않으면 벤더 장애가 <b>한 줄로 사라진다</b> — {@code #374} 가 그래서 이틀 걸렸다. 반대로 원인
 * 예외를 통째로 실으면 {@code getMessage()} 에 URL · 응답 본문 조각 · 헤더가 따라온다 (S-3).
 * 타입 이름은 코드의 식별자이므로 <b>벤더 데이터가 실릴 자리가 없다</b>.
 *
 * <p><b>{@code Throwable} 을 받는 생성자를 두지 않는 것이 그 보장이다.</b> 문자열 사슬만 받으므로
 * 벤더 예외를 {@code cause} 로 붙이는 실수가 <b>컴파일되지 않는다</b> — 규약이 아니라 구조다.
 */
public class ProviderCallFailedException extends RuntimeException {

	/** 사슬을 잇는 표기. 로그 한 줄에 그대로 실리므로 ASCII 로 둔다. */
	private static final String LINK = " -> ";

	/** 원인을 받지 못한 자리. <b>"원인이 없다"가 아니라 "모른다"</b>이므로 빈 문자열로 두지 않는다. */
	private static final String UNKNOWN = "unknown";

	/** 사슬 길이 상한. 서로를 감싸는 예외를 만나도 로그 한 줄이 화면을 넘기지 않는다. */
	private static final int MAX_LINKS = 8;

	private final String causeChain;

	public ProviderCallFailedException(String message) {
		super(message);
		this.causeChain = UNKNOWN;
	}

	/**
	 * @param causeChain {@link #typeChainOf(Throwable)} 이 만든 <b>타입 이름의 사슬</b>. 벤더 예외의
	 *     메시지를 여기에 손으로 이어 붙이지 않는다 (S-3)
	 */
	public ProviderCallFailedException(String message, String causeChain) {
		super(message + " cause=" + causeChain);
		this.causeChain = causeChain;
	}

	/**
	 * 구조화 로그가 사슬만 따로 실을 때의 접근자. 메시지에도 이미 들어 있다 — <b>둘 중 하나를
	 * 잊어도 사슬이 남게</b> 하려는 것이다.
	 */
	public String causeChain() {
		return this.causeChain;
	}

	/**
	 * 원인 예외를 <b>타입 이름의 사슬</b>로 바꾼다 (§13-86).
	 *
	 * <p>예: {@code ResourceAccessException -> NoHttpResponseException}. 어느 링크에서도
	 * {@code getMessage()} 를 읽지 않는다 — 그것이 이 메서드의 존재 이유다.
	 *
	 * <p><b>이 클래스가 갖는 이유.</b> 계약 패키지는 record · interface · enum · 예외만 담는다
	 * (ADR-0006, {@code PortBoundaryTests}) — 유틸리티 클래스를 새로 둘 수 없고, 사슬을 만드는
	 * 자리마다 다른 구현이 생기는 것보다 규칙의 주인이 규칙을 갖는 편이 낫다.
	 */
	public static String typeChainOf(@Nullable Throwable failure) {
		if (failure == null) {
			return UNKNOWN;
		}
		StringBuilder chain = new StringBuilder();
		Throwable link = failure;
		for (int depth = 0; link != null && depth < MAX_LINKS; depth++) {
			if (!chain.isEmpty()) {
				chain.append(LINK);
			}
			chain.append(typeNameOf(link));
			if (link instanceof ProviderCallFailedException carried && !UNKNOWN.equals(carried.causeChain)) {
				// 이미 사슬을 들고 있는 링크다. 원인이 붙어 있지 않으므로 다시 계산할 수 없고,
				// 들고 있는 것을 이어 붙이지 않으면 감싸는 순간 아래가 잘린다.
				chain.append(LINK).append(carried.causeChain);
			}
			Throwable next = link.getCause();
			link = (next != link) ? next : null;
		}
		return chain.toString();
	}

	/** 익명 클래스는 {@code getSimpleName()} 이 비므로 이진 이름의 마지막 조각을 쓴다. */
	private static String typeNameOf(Throwable link) {
		String simpleName = link.getClass().getSimpleName();
		if (!simpleName.isBlank()) {
			return simpleName;
		}
		String binaryName = link.getClass().getName();
		return binaryName.substring(binaryName.lastIndexOf('.') + 1);
	}
}
