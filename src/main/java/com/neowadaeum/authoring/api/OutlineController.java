package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.outline.ConditionTemplate;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.OutlineDraft;
import com.neowadaeum.common.spi.OutlineDraftRequest;
import com.neowadaeum.common.spi.OutlineDrafter;
import com.neowadaeum.common.support.RateLimitProperties;
import com.neowadaeum.common.support.RateLimiter;
import com.neowadaeum.common.web.PlayerRefResolver;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 챕터·엔딩 초안 (§13.8, B-52).
 *
 * <p><b>사용자에게 챕터 여섯과 엔딩 다섯을 직접 쓰라고 요구하면 아무도 완성하지 못한다</b>
 * (R7.14). 초안을 만들어 주고 편집하게 한다.
 *
 * <p><b>조건은 템플릿 목록으로 함께 내려간다</b> (R7.16). 작성자가 조건식을 쓰는 경로는 없다.
 *
 * <p><b>일일 상한이 있다</b> (R8.12). 이 호출의 비용은 플랫폼이 부담하므로, 상한이 없으면
 * <b>한 계정이 그 비용을 정한다.</b>
 */
@RestController
@RequestMapping("/api/v1/authoring/drafts/{draftId}/outline")
public class OutlineController {

	private static final tools.jackson.databind.json.JsonMapper JSON =
			tools.jackson.databind.json.JsonMapper.builder().build();

	/** R7.14 가 정한 수. 작성자가 이 위에서 지우고 더한다. */
	private static final int CHAPTERS = 5;

	private static final int ENDINGS = 3;

	/**
	 * 계정당 일일 호출 상한 (R8.12).
	 *
	 * <p>원문은 값을 정하지 않는다 (§13-34). 초안은 <b>세계관을 고쳐 가며</b> 다시 부르는
	 * 것이므로 하루 몇 번으로는 부족하고, 수십 번이면 그것은 작성이 아니라 뽑기다.
	 */
	static final int OUTLINE_PER_DAY = 20;

	private final OutlineDrafter drafter;

	private final DraftService drafts;

	private final RateLimiter rateLimiter;

	private final PlayerRefResolver playerRefs;

	public OutlineController(OutlineDrafter drafter, DraftService drafts, RateLimiter rateLimiter,
			PlayerRefResolver playerRefs) {
		this.drafter = drafter;
		this.drafts = drafts;
		this.rateLimiter = rateLimiter;
		this.playerRefs = playerRefs;
	}

	@PostMapping
	public OutlineResponse outline(@PathVariable UUID draftId) {
		UUID authorRef = this.playerRefs.currentPlayerRef();
		requireWithinDailyLimit(authorRef);
		// 남의 원고에는 부를 수 없다 (I-8). 없는 것과 구분되지 않는다.
		String worldPrompt = worldPromptOf(this.drafts.read(authorRef, draftId).getPayload());

		OutlineDraft draft = this.drafter.draft(
				new OutlineDraftRequest(worldPrompt, CHAPTERS, ENDINGS));
		return OutlineResponse.of(draft, conditionTemplateKeys());
	}

	/**
	 * <b>세계관이 없으면 초안도 없다.</b>
	 *
	 * <p>2단계를 건너뛰고 부르면 모델은 <b>아무 세계관이나</b> 지어낸다 — 그것은 작성자의
	 * 작품이 아니다.
	 *
	 * <p>{@code payload} 를 여기서 <b>이 한 값만</b> 꺼낸다. 원고 전체를 타입으로 푸는 것은
	 * 단계가 늘 때마다 계약이 느는 길이며, 그 판단은 B-51 이 이미 했다.
	 */
	private static String worldPromptOf(String payload) {
		String worldPrompt = JSON.readTree(payload).path("worldPrompt").asString(null);
		if (worldPrompt == null || worldPrompt.isBlank()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		return worldPrompt;
	}

	private static List<String> conditionTemplateKeys() {
		return Arrays.stream(ConditionTemplate.values()).map(ConditionTemplate::key).toList();
	}

	private void requireWithinDailyLimit(UUID authorRef) {
		if (!this.rateLimiter.tryAcquire("outline-day", authorRef.toString(), OUTLINE_PER_DAY,
				Duration.ofDays(1))) {
			throw new ApiException(ErrorCode.RATE_LIMITED,
					Map.of("retryAfterSeconds", Duration.ofDays(1).toSeconds()));
		}
	}
}
