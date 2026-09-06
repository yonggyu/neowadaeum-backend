package com.neowadaeum.play.port;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code stateChanges} 가 쓸 수 있는 연산자 (§5.2, §13-9, §13-82).
 *
 * <p><b>이 열거가 허용 연산자의 유일한 정본이다.</b> 응답을 읽는 파서와 모델에게 형식을 말하는
 * {@code OUTPUT SPEC} 이 <b>같은 목록을 본다</b> — 한쪽에만 적어 두면 다른 쪽이 언젠가 갈라지고,
 * 갈라진 결과는 예외가 아니라 <b>침묵</b>이다. 서버는 모르는 연산자를 병합하지 않고 경고만 남기므로
 * (R4.1), 증상은 "상태가 가끔 안 바뀐다" 로만 나타난다 (#375).
 *
 * <p><b>왜 {@code play :: port} 인가.</b> 이 목록은 턴 생성 계약의 일부다 —
 * {@link GeneratedTurn#proposedStateChanges()} 안에 들어오는 키의 어휘이며, 그 계약은 {@code play}
 * 가 소유하고 {@code ai} 가 구현한다 (ADR-0006). {@code play :: engine} 에 두면 {@code ai} 가 볼 수
 * 없어 프롬프트가 목록을 <b>복제</b>해야 하고, 그것이 이 이슈가 닫는 문제다.
 *
 * <p><b>일곱 번째 연산자는 여기에 상수가 없다</b> (§13-9). 수치 델타는 키가 고정되어 있지 않고
 * <b>선언된 수치 이름 그 자체</b>이므로 열거의 항목이 될 수 없다. 그 자리는 {@link #NUMERIC} ·
 * {@link #NUMERIC_WIRE_SHAPE} 가 채운다.
 *
 * <p><b>값 모양({@link #wireShape()})도 여기에 둔다.</b> 배열이 아닌 {@code flags.add} 나 문자열이
 * 아닌 {@code location} 은 파서가 무시한다 — 모델에게 그 모양을 말해 주는 문장과 그것을 강제하는
 * 코드가 갈라지면 같은 침묵이 다시 생긴다.
 *
 * <p><b>선언 순서가 {@code OUTPUT SPEC} 의 인쇄 순서다.</b> 순서를 바꾸는 것은 프롬프트를 바꾸는
 * 것이며, 골든 파일 테스트가 그것을 diff 로 드러낸다.
 */
public enum StateChangeOperator {

	/** 플래그를 세운다. 화이트리스트 밖 이름은 병합되지 않는다 (R4.1). */
	FLAGS_ADD("flags.add", "[string]"),

	/** 플래그를 내린다. 이미 서 있는 값을 빼는 것이라 화이트리스트를 묻지 않는다. */
	FLAGS_REMOVE("flags.remove", "[string]"),

	/** 아이템을 넣는다. 화이트리스트 밖 이름은 병합되지 않는다 (R4.1). */
	INVENTORY_ADD("inventory.add", "[string]"),

	/** 아이템을 뺀다. */
	INVENTORY_REMOVE("inventory.remove", "[string]"),

	/** 현재 장소. */
	LOCATION("location", "string"),

	/** 시간대. */
	TIME_OF_DAY("timeOfDay", "string");

	/**
	 * 수치 델타 자리의 이름 (§13-9).
	 *
	 * <p>고정된 키가 아니다 — {@code stateChanges} 의 <b>그 밖의 키</b>는 모두 수치 델타로 읽힌다.
	 * 그래서 이 문자열은 연산자가 아니라 <b>그 자리를 부르는 이름</b>이며, {@code STATE VOCABULARY}
	 * 의 머리표와 {@code OUTPUT SPEC} 의 자리표시자가 같은 말을 쓰도록 붙잡는다.
	 */
	public static final String NUMERIC = "numeric";

	/** 수치 델타의 값 모양. 정수가 아니면 파서가 무시한다 (§13-9). */
	public static final String NUMERIC_WIRE_SHAPE = "number";

	private static final Map<String, StateChangeOperator> BY_KEY = Arrays.stream(values())
			.collect(Collectors.toUnmodifiableMap(StateChangeOperator::key, Function.identity()));

	private final String key;

	private final String wireShape;

	StateChangeOperator(String key, String wireShape) {
		this.key = key;
		this.wireShape = wireShape;
	}

	/**
	 * {@code stateChanges} 객체에 실제로 실리는 키.
	 *
	 * <p><b>열거 이름이 아니라 이 값이 와이어의 진실이다.</b> {@code FLAGS_ADD} 를 그대로 소문자로
	 * 바꾸면 {@code flags_add} 가 되어 §13-9 와 어긋난다.
	 */
	public String key() {
		return this.key;
	}

	/** 값의 모양. {@code OUTPUT SPEC} 이 이 표기를 그대로 인쇄한다 (§5.2). */
	public String wireShape() {
		return this.wireShape;
	}

	/**
	 * 이 연산자가 다루는 이름 목록의 머리표. {@code flags.add} 의 머리표는 {@code flags} 다.
	 *
	 * <p>{@code STATE VOCABULARY} 가 이름을 갈래별로 인쇄할 때 쓴다 (§13-82). <b>여기서 끌어오는
	 * 이유</b>는 머리표와 연산자 키가 같은 낱말을 써야 모델이 둘을 이을 수 있기 때문이다 — 따로
	 * 적어 두면 연산자 이름을 고치는 날 어휘 레이어만 옛 낱말에 남는다.
	 */
	public String namespace() {
		int dot = this.key.indexOf('.');
		return (dot < 0) ? this.key : this.key.substring(0, dot);
	}

	/**
	 * 키로 연산자를 찾는다.
	 *
	 * @return 해당하는 연산자. 없으면 {@code null} — <b>그 키는 수치 델타 자리다</b> (§13-9)
	 */
	public static StateChangeOperator of(String key) {
		return (key == null) ? null : BY_KEY.get(key);
	}
}
