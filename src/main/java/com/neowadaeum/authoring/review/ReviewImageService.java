package com.neowadaeum.authoring.review;

import com.neowadaeum.authoring.draft.StoryDraft;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.authoring.image.DraftImageKey;
import com.neowadaeum.authoring.image.DraftImageService;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.AccessAuditRecorder;
import com.neowadaeum.common.spi.AuditedResource;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 검수자가 승인 전 이미지를 보는 문 (#377, §13-78).
 *
 * <p><b>커버는 15세 등급 판정의 대상이다</b> (R8.5). 그리고 이미지는 블록리스트가 볼 수 없는
 * 종류라 <b>사람 말고는 판정할 주체가 없다</b> — 그 문이 없으면 검수자는 객체 키만 보고 승인한다.
 *
 * <p><b>서명 URL 을 내주지 않는다.</b> 그것은 그 객체의 출입증이라 수명 동안 관리자 게이트(S-4)
 * 밖에서 열리고, 승인 전 UGC 에 그것을 허용하면 I-8 이 지키던 것이 URL 한 줄로 샌다. 경계는
 * <b>"승인됐는가"</b> 이며 그것은 I-8 이 이미 그은 선이다 — 그래서 승인 전은 <b>중계</b>다.
 *
 * <p><b>이 작품의 원고에 속한 키만 연다.</b> 키가 원고 id 를 들고 있으므로 (§13-65) 그 판정은
 * 문법 판정이 된다 — 관리자라는 사실이 <b>아무 객체나 꺼낼 수 있다</b>는 뜻은 아니다.
 *
 * <p><b>열람은 이미지마다 남는다</b> (R12.3, S-5). 원고 열람과 <b>다른 자원</b>인 것이 결정이다
 * (§13-78) — 같게 두면 검수 상세 한 번이 원고 열람 여러 줄로 부풀어 정작 그 사실이 묻힌다.
 *
 * <p><b>기록이 바이트보다 먼저다.</b> 순서를 뒤집으면 <b>읽고 나서 기록에 실패한</b> 열람이
 * 생기고, 그것이 곧 기록되지 않는 열람 경로다 ({@link ReviewManuscriptService} 와 같은 판단).
 *
 * <p><b>트랜잭션을 열지 않는다.</b> 원고 조회는 짧게 끝나고 저장소 호출은 그 밖에서 일어난다.
 */
@Service
public class ReviewImageService {

	private final StoryDraftRepository drafts;

	private final DraftImageService images;

	private final AccessAuditRecorder access;

	public ReviewImageService(StoryDraftRepository drafts, DraftImageService images,
			AccessAuditRecorder access) {
		this.drafts = drafts;
		this.images = images;
		this.access = access;
	}

	/**
	 * 이 작품의 이미지 한 장을 연다.
	 *
	 * @param adminUserId 읽는 사람. <b>{@code playerRef} 가 아니다</b> — 감사는 사람을 가리킨다
	 * @throws ApiException {@code NOT_FOUND} — 원고 없는 작품이거나, 그 자리에 객체가 없다.
	 *     <b>커버를 올리지 않은 원고가 정상이며</b>, 그 404 는 이 한 장에만 걸린다 — 검수 상세는
	 *     자기 응답을 그대로 내놓는다 (§13-68 이 미리보기 턴에 대해 세운 판단과 같다)
	 * @throws ApiException {@code VALIDATION_ERROR} — 이 작품의 원고가 만든 키가 아니다
	 */
	public ReviewImage read(UUID adminUserId, UUID storyId, String objectKey) {
		UUID draftId = this.drafts.findFirstByStoryIdOrderByUpdatedAtDesc(storyId)
				.map(StoryDraft::getId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		DraftImageKey key = DraftImageKey.of(draftId, objectKey);

		this.access.record(adminUserId, AuditedResource.DRAFT_IMAGE, key.imageId());

		DraftImageService.RelayedImage relayed = this.images.read(key);
		return new ReviewImage(relayed.format().contentType(), relayed.bytes());
	}

	/**
	 * 검수 화면으로 나가는 이미지 한 장.
	 *
	 * <p><b>{@code contentType} 을 이미 문자열로 들고 나온다.</b> 이미지 형식 열거는
	 * {@code authoring :: image} 의 것이고 그 패키지는 {@code admin} 에게 열려 있지 않다 —
	 * 열어 주면 관리자 화면이 업로드 경로의 타입까지 보게 된다 (§5.4).
	 *
	 * <p>그 값은 <b>버킷이 말한 것이 아니라 발급이 서명한 형식</b>이다 (§13-65).
	 */
	public record ReviewImage(String contentType, byte[] bytes) {
	}
}
