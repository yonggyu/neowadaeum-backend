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
 */
@ConfigurationProperties("app.ugc")
public record UgcLimitProperties(Integer outlinePerDay, Integer previewsPerDay,
		Integer storiesPerAuthor) {

	public UgcLimitProperties {
		outlinePerDay = (outlinePerDay != null) ? outlinePerDay : 20;
		previewsPerDay = (previewsPerDay != null) ? previewsPerDay : 10;
		storiesPerAuthor = (storiesPerAuthor != null) ? storiesPerAuthor : 30;
	}

	/** 설정을 띄우지 않는 테스트가 쓴다. */
	public static UgcLimitProperties defaults() {
		return new UgcLimitProperties(null, null, null);
	}
}
