package com.neowadaeum.authoring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 작품 만들기의 비용 상한 (R8.12, B-60).
 *
 * <p>R8.12 는 <b>"작품 만들기 과정의 AI 호출 비용은 플랫폼이 부담한다"</b> 로 시작한다.
 * 부담하는 쪽이 상한을 정하지 않으면 <b>한 계정이 그 비용을 정한다.</b>
 *
 * <p><b>왜 한곳인가.</b> 세 상한이 각자 다른 컨트롤러의 상수로 흩어져 있었다 — 그러면 "지금
 * 상한이 얼마인가"를 답하려면 세 파일을 열어야 하고, 조정할 때 <b>하나를 빠뜨렸는지</b>를
 * 아무도 알 수 없다.
 *
 * <p><b>설정으로 두는 이유는 {@code RateLimitProperties} 와 같다</b> — B-46 이 실측한 뒤
 * 조정할 값이다. 기본값은 코드에 있다: 이 값들은 세이프티 임계가 아니므로 <b>알아도 그 아래로
 * 관리할 것이 없다</b> (S-11 이 가리는 것은 검수 비율과 정지 임계다).
 *
 * @param outlinePerDay 계정당 일일 {@code draftOutline} 호출 (§13-34). 세계관을 고쳐 가며 다시
 *     부르는 것이므로 하루 몇 번으로는 부족하고, 수십 번이면 그것은 작성이 아니라 뽑기다
 * @param previewsPerDay 계정당 일일 미리보기. 한 번이 <b>임시 작품 하나와 AI 호출 셋</b>이다
 * @param storiesPerAuthor 계정당 작품 개수. <b>제출된 것만 센다</b> — 미리보기가 만드는
 *     {@code draft} 작품은 매번 늘어나고(§13-37) 파기는 B-61 이 가져간다
 * @param chaptersPerStory 작품 하나의 챕터 개수 (§13-81, #380). {@code storiesPerAuthor} 가
 *     <b>작품 수</b>를 세는 값이라면 이것은 <b>작품 하나의 크기</b>를 센다 — L1 은 챕터마다
 *     필드를 펼쳐 한 번에 판정기에 넘기므로, 여기가 비면 원고 하나가 제출 한 번의 비용을 정한다
 * @param endingsPerStory 작품 하나의 엔딩 개수 (§13-81, #380). 챕터와 같은 이유이며 같은 축이다
 */
@ConfigurationProperties("app.ugc")
public record UgcLimitProperties(Integer outlinePerDay, Integer previewsPerDay,
		Integer storiesPerAuthor, Integer chaptersPerStory, Integer endingsPerStory) {

	public UgcLimitProperties {
		outlinePerDay = (outlinePerDay != null) ? outlinePerDay : 20;
		previewsPerDay = (previewsPerDay != null) ? previewsPerDay : 10;
		storiesPerAuthor = (storiesPerAuthor != null) ? storiesPerAuthor : 30;
		// §13-81 `[결정 필요]` — 원문 대조를 하지 못했다 (`docs/internal/` 이 이 환경에 없다).
		// 32 는 기본 채택안이다: 마법사로 사람이 쓰는 원고가 닿지 않는 자리에 울타리를 두되,
		// **울타리가 있다는 사실이 코드에 적혀 있게** 한다 — 지금 없는 것은 값이 아니라 그 사실이다.
		chaptersPerStory = (chaptersPerStory != null) ? chaptersPerStory : 32;
		endingsPerStory = (endingsPerStory != null) ? endingsPerStory : 32;
	}

	/** 설정을 띄우지 않는 테스트가 쓴다. */
	public static UgcLimitProperties defaults() {
		return new UgcLimitProperties(null, null, null, null, null);
	}
}
