package com.neowadaeum.authoring.review;

import com.neowadaeum.catalog.publish.PublishedStoryTexts;
import com.neowadaeum.common.spi.UgcReviewSampling;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 승인 후 무작위 샘플링 검수 (§8.4, R8.11, B-59).
 *
 * <p><b>{@code unlisted} 는 인간 검수를 거치지 않았다</b> (R8.6). 링크는 무한히 확산되므로
 * 도달 범위로는 {@code public} 과 다르지 않고, 그 격차를 메우는 것이 이 장치다 — <b>사전
 * 검수가 약한 쪽을 사후에 더 본다</b> (§13-12).
 *
 * <p><b>작품을 내리지 않는다.</b> 무작위로 뽑혔다는 것은 <b>아무 근거도 아니며</b>, 근거 없이
 * 게시된 작품을 내리면 그것은 검수가 아니라 처벌이다. 상태를 그대로 둔 채 검수 이력에
 * <b>표식</b>만 남기고, 큐가 그것을 함께 본다 (§13-42).
 *
 * <p><b>난수를 여기서만 쓴다.</b> I-15 는 판정·분기·엔딩·상태 변화량에 난수를 금지하고
 * <b>L3 랜덤 샘플링은 허용</b>한다 — 이 난수는 <b>무엇을 볼 것인가</b>를 정할 뿐 어떤 작품의
 * 운명도 정하지 않는다. 뽑힌 뒤에 일어나는 일은 사람이 정한다.
 */
@Service
public class UgcReviewSampler implements UgcReviewSampling {

	/** 한 회차가 훑는 작품 수. 남은 것은 다음 회차가 본다 — 샘플링은 누적이 아니다. */
	private static final int PAGE_SIZE = 200;

	private static final int MAX_PAGES = 20;

	/** 표식임을 사람이 알아볼 수 있게 남긴다. 작성자에게는 나가지 않는다 (R8.7). */
	private static final String SAMPLED_NOTE = "sampling";

	private static final int FULL_PERCENT = 100;

	private final PublishedStoryTexts published;

	private final SamplingRates rates;

	private final StoryReviewRepository reviews;

	private final Clock clock;

	private final TransactionTemplate transactions;

	public UgcReviewSampler(PublishedStoryTexts published, SamplingRates rates,
			StoryReviewRepository reviews, Clock clock,
			PlatformTransactionManager catalogTransactionManager) {
		this.published = published;
		this.rates = rates;
		this.reviews = reviews;
		this.clock = clock;
		this.transactions = new TransactionTemplate(catalogTransactionManager);
	}

	/**
	 * 한 차례 뽑는다.
	 *
	 * <p><b>작품마다 트랜잭션을 나눈다.</b> 한 트랜잭션으로 묶으면 마지막에서 실패했을 때 앞의
	 * 표식이 전부 사라지고, <b>한 회차가 통째로 없던 일</b>이 된다.
	 */
	@Override
	public int sample() {
		int flagged = 0;
		UUID after = null;

		for (int page = 0; page < MAX_PAGES; page++) {
			List<PublishedStoryTexts.ApprovedRef> refs = this.published.approvedRefs(PAGE_SIZE, after);
			if (refs.isEmpty()) {
				break;
			}
			for (PublishedStoryTexts.ApprovedRef ref : refs) {
				if (flagIfDrawn(ref)) {
					flagged++;
				}
			}
			after = refs.get(refs.size() - 1).storyId();
		}
		return flagged;
	}

	/**
	 * 뽑혔으면 표식을 남긴다.
	 *
	 * <p><b>이미 올라가 있는 작품은 다시 올리지 않는다.</b> 회차마다 표식이 쌓이면 큐에는 같은
	 * 작품이 한 번 뜨지만 이력은 계속 길어지고, 그 이력은 <b>왜 그렇게 됐는지</b>가 아니라
	 * 배치 실행 기록이 된다.
	 */
	private boolean flagIfDrawn(PublishedStoryTexts.ApprovedRef ref) {
		OptionalInt percent = this.rates.percentFor(ref.visibility());
		if (percent.isEmpty() || !drawn(percent.getAsInt())) {
			return false;
		}
		return Boolean.TRUE.equals(this.transactions.execute(status -> {
			if (this.reviews.isFlaggedForReview(ref.storyId())) {
				return false;
			}
			this.reviews.save(StoryReview.of(ref.storyId(), ReviewStage.AUTO, ReviewVerdict.HOLD, "[]",
					null, SAMPLED_NOTE, Instant.now(this.clock)));
			return true;
		}));
	}

	/**
	 * <b>여기가 난수를 쓰는 유일한 자리다</b> (I-15 보강 — L3 랜덤 샘플링은 허용).
	 *
	 * <p>비율이 {@code 100} 이면 전부 뽑힌다 — 경계를 특별히 다루지 않아도 그렇게 되는 식을
	 * 골랐다. 예외를 두면 <b>그 예외가 맞는지</b>를 아무도 다시 확인하지 않는다.
	 */
	private static boolean drawn(int percent) {
		return ThreadLocalRandom.current().nextInt(FULL_PERCENT) < percent;
	}
}
