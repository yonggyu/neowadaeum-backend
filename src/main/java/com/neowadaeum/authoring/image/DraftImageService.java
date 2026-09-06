package com.neowadaeum.authoring.image;

import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 원고에 붙는 이미지의 업로드 (#315, §13-65).
 *
 * <p><b>키는 서버가 정한다.</b> 클라이언트가 경로를 고르면 남의 자리에 덮어쓸 수 있고, 그때 잃는
 * 것은 남의 원고다. 키에 원고 id 가 들어 있으므로 <b>소유 판정이 곧 경로 판정</b>이 된다.
 *
 * <p><b>발급이 보장하는 것은 자리와 형식뿐이다.</b> 바이트는 브라우저가 직접 올리므로 서버가
 * 보지 못한다 — 그래서 {@link #commit} 이 있다.
 *
 * <p><b>읽기 URL 을 발급하지 않는다</b> (I-8). 버킷은 비공개이고 영구 URL 이 없다 — 승인 전
 * 이미지는 <b>서버가 바이트를 중계</b>해서만 닿는다 (#377, §13-78). 서명 URL 은 그 객체의
 * 출입증이라 수명 동안 게이트 밖에서 열리므로, 승인 전 UGC 에 그것을 내주면 I-8 이 지키던 것이
 * URL 한 줄로 샌다.
 *
 * <p><b>트랜잭션을 열지 않는다.</b> 소유 판정은 {@link DraftService} 안에서 짧게 끝나고 저장소
 * 호출은 그 밖에서 일어난다.
 */
@Service
public class DraftImageService {

	private final DraftService drafts;

	private final DraftImageStore store;

	private final Clock clock;

	private final ImageStorageProperties properties;

	public DraftImageService(DraftService drafts, DraftImageStore store, Clock clock,
			ImageStorageProperties properties) {
		this.drafts = drafts;
		this.store = store;
		this.clock = clock;
		this.properties = properties;
	}

	/**
	 * 업로드 URL 을 발급한다.
	 *
	 * @throws ApiException {@code NOT_FOUND} — 없거나 <b>남의</b> 원고 (I-8)
	 * @throws ApiException {@code VALIDATION_ERROR} — 자리나 형식이 목록에 없다
	 */
	public IssuedUpload issue(UUID authorRef, UUID draftId, String slotName, String contentType) {
		this.drafts.read(authorRef, draftId);
		DraftImageSlot slot = DraftImageSlot.of(slotName);
		ImageFormat format = ImageFormat.ofContentType(contentType);
		if (slot == null || format == null) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		String objectKey = "drafts/%s/%s/%s.%s".formatted(draftId, slot.segment(),
				UUID.randomUUID(), format.extension());
		return new IssuedUpload(objectKey, this.store.presignUpload(objectKey, format), format,
				Instant.now(this.clock).plus(this.properties.uploadUrlTtl()));
	}

	/**
	 * 올라온 것을 서버가 확인한다. 통과해야 이 키가 <b>원고에 적을 수 있는 값</b>이 된다.
	 *
	 * @throws ApiException {@code NOT_FOUND} — 없거나 남의 원고
	 * @throws ApiException {@code VALIDATION_ERROR} — 이 원고의 키가 아니거나, 올라온 것이
	 *     형식·상한을 벗어났다
	 */
	public CommittedImage commit(UUID authorRef, UUID draftId, String objectKey) {
		this.drafts.read(authorRef, draftId);
		DraftImageKey key = DraftImageKey.of(draftId, objectKey);
		DraftImageStore.StoredImage stored = this.store.verifyStored(key.objectKey());
		return new CommittedImage(key.objectKey(), stored.format(), stored.sizeBytes());
	}

	/**
	 * 작성자가 자기 원고의 이미지를 본다 (#377, §13-78).
	 *
	 * <p><b>판정은 원고 소유권이다.</b> 작성자는 관리자가 아니므로 (S-4 밖) 게이트가 될 수 있는
	 * 사실은 그것 하나뿐이고, 그 자리는 {@link DraftService} 가 세운 <i>남의 원고는 없는 것과
	 * 구분되지 않는다</i> 와 <b>같아야 한다</b> — 여기서만 다른 코드를 내면 어느 원고가 존재하는지가
	 * 이미지 응답으로 새어 나간다 (I-8).
	 *
	 * @throws ApiException {@code NOT_FOUND} — 없거나 남의 원고이거나, 그 자리에 객체가 없다
	 * @throws ApiException {@code VALIDATION_ERROR} — 이 원고의 키가 아니다
	 */
	public RelayedImage readForAuthor(UUID authorRef, UUID draftId, String objectKey) {
		this.drafts.read(authorRef, draftId);
		return read(DraftImageKey.of(draftId, objectKey));
	}

	/**
	 * 확인된 키의 바이트를 읽는다 (#377, §13-78).
	 *
	 * <p><b>게이트를 걸지 않는다</b> — 부르는 쪽이 이미 걸었다. 검수자 경로는 관리자 게이트(S-4)와
	 * <b>열람 감사</b>를 자기 자리에서 세우며, 감사는 <b>바이트보다 먼저</b> 남아야 하므로 (S-5)
	 * 그 순서를 여기서 대신 정하지 않는다.
	 */
	public RelayedImage read(DraftImageKey key) {
		return new RelayedImage(key.format(), this.store.readObject(key.objectKey()));
	}

	/** 발급 결과. 원고에 적히는 값은 {@code objectKey} 이며 URL 이 아니다. */
	public record IssuedUpload(String objectKey, URI uploadUrl, ImageFormat format, Instant expiresAt) {
	}

	/** 확인 결과. 형식과 크기는 <b>저장소가 말한 것</b>이지 클라이언트가 말한 것이 아니다. */
	public record CommittedImage(String objectKey, ImageFormat format, long sizeBytes) {
	}

	/**
	 * 중계되는 이미지 한 장 (#377).
	 *
	 * <p><b>바이트를 그대로 흘린다.</b> 내부 참조 토큰을 주면 그것이 다시 출입증이 되어, 승인 전에
	 * 서명 URL 을 쓰지 않기로 한 이유가 절반 돌아온다 (§13-78).
	 */
	public record RelayedImage(ImageFormat format, byte[] bytes) {
	}
}
