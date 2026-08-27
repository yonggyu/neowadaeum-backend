package com.neowadaeum.authoring.precheck;

import com.neowadaeum.authoring.draft.DraftSafetyState;
import com.neowadaeum.common.spi.BlocklistEntry;
import com.neowadaeum.common.spi.BlocklistQuery;
import com.neowadaeum.common.spi.SafetyCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 입력 중 검수 (L0, R8.1~R8.4).
 *
 * <p><b>같은 블록리스트를 본다.</b> 여기서 통과한 것이 제출 뒤 검수에서 걸리면, 작성 중
 * 피드백은 <b>거짓 안심</b>이 된다 — 그 상태는 피드백이 없는 것보다 나쁘다.
 *
 * <p><b>막는 것이 목적이 아니라 고칠 자리를 알려 주는 것이 목적이다</b> (§8.2). 그래서 위치와
 * 분류를 함께 돌려준다.
 *
 * <p><b>fail-closed 를 뒤집지 않는다</b> (ADR-0002) — 블록리스트 조회가 실패하면 예외가 그대로
 * 올라간다. 못 읽는 상태에서 <b>깨끗하다</b>고 답하면 작성자는 그 말을 믿고 제출한다.
 */
@Component
public class PrecheckScreen {

	private static final String DEFAULT_MESSAGE = "이 표현은 사용할 수 없어요. 다른 말로 바꿔 주세요.";

	/**
	 * 분류별 안내.
	 *
	 * <p><b>{@code "부적절한 내용입니다"} 라고 쓰지 않는다</b> (R8.2). 무엇이 문제인지 드러나야
	 * 작성자가 고칠 수 있다 — 다만 <b>어떤 항목에 걸렸는지</b>는 끝까지 말하지 않는다.
	 */
	private static final Map<SafetyCategory, String> MESSAGES = Map.of(
			SafetyCategory.REAL_PERSON_HARM, "실존 인물로 보이는 이름이 포함되어 있어요.",
			SafetyCategory.IP_REPLICATION, "기존 작품의 이름으로 보이는 표현이 포함되어 있어요.",
			SafetyCategory.RATING_EXCEEDED, "15세 이용가에서 쓸 수 없는 표현이 포함되어 있어요.");

	private final BlocklistQuery blocklist;

	public PrecheckScreen(BlocklistQuery blocklist) {
		this.blocklist = blocklist;
	}

	/**
	 * 필드마다 검사한다.
	 *
	 * @param fields 필드 경로 → 값
	 * @return 걸린 자리. 없으면 빈 목록이며 그때 상태는 {@code clean} 이다
	 */
	public Result screen(Map<String, String> fields) {
		List<BlocklistEntry> entries = this.blocklist.findAll();

		List<PrecheckFinding> findings = new ArrayList<>();
		fields.forEach((field, value) -> {
			if (value == null || value.isBlank()) {
				return;
			}
			findings.addAll(findingsIn(field, value, entries));
		});
		return new Result(findings.isEmpty() ? DraftSafetyState.CLEAN : DraftSafetyState.BLOCKED,
				List.copyOf(findings));
	}

	/**
	 * <b>같은 항목이 여러 번 나와도 자리마다 남긴다.</b>
	 *
	 * <p>밑줄은 자리에 긋는 것이므로, 두 번째 등장을 빼면 그 자리는 고쳐지지 않는다.
	 */
	private static List<PrecheckFinding> findingsIn(String field, String value,
			List<BlocklistEntry> entries) {
		NormalizedText text = NormalizedText.of(value);
		List<PrecheckFinding> findings = new ArrayList<>();

		for (BlocklistEntry entry : entries) {
			int from = text.value().indexOf(entry.normalizedValue());
			while (from >= 0) {
				int to = from + entry.normalizedValue().length();
				findings.add(new PrecheckFinding(field, text.spanOf(from, to),
						entry.category().name().toLowerCase(Locale.ROOT), messageFor(entry.category())));
				from = text.value().indexOf(entry.normalizedValue(), to);
			}
		}
		return findings;
	}

	private static String messageFor(SafetyCategory category) {
		return MESSAGES.getOrDefault(category, DEFAULT_MESSAGE);
	}

	/**
	 * 검사 결과.
	 *
	 * <p><b>{@code warned} 를 지금 만들지 않는다</b> (§13-33). 경고 항목은 판정으로 나오지 않으며
	 * (§13-31), 그것을 여기서 되살리면 두 곳이 서로 다른 목록을 보게 된다.
	 */
	public record Result(DraftSafetyState state, List<PrecheckFinding> findings) {
	}
}
