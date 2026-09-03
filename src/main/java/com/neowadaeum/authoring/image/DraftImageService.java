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
 * <p><b>읽기 URL 을 발급하지 않는다.</b> 버킷은 비공개이고 영구 URL 이 없으므로 승인 전 이미지가
 * 타인에게 닿는 경로가 없다 (I-8). 열람은 소유자·검수자에 한해 뒤에 연다.
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
		requireKeyOf(draftId, objectKey);
		DraftImageStore.StoredImage stored = this.store.verifyStored(objectKey);
		return new CommittedImage(objectKey, stored.format(), stored.sizeBytes());
	}

	/**
	 * <b>이 원고의 키인가.</b> 발급이 만든 모양 그대로여야 한다 — 접두어만 보면
	 * {@code drafts/<id>/../<남의 원고>} 가 통과한다.
	 */
	private static void requireKeyOf(UUID draftId, String objectKey) {
		String[] parts = (objectKey == null) ? new String[0] : objectKey.split("/");
		boolean shaped = parts.length == 4 && "drafts".equals(parts[0])
				&& parts[1].equals(draftId.toString()) && DraftImageSlot.of(parts[2]) != null
				&& parts[3].matches("[0-9a-f-]{36}\\.[a-z]{3,4}");
		if (!shaped) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
	}

	/** 발급 결과. 원고에 적히는 값은 {@code objectKey} 이며 URL 이 아니다. */
	public record IssuedUpload(String objectKey, URI uploadUrl, ImageFormat format, Instant expiresAt) {
	}

	/** 확인 결과. 형식과 크기는 <b>저장소가 말한 것</b>이지 클라이언트가 말한 것이 아니다. */
	public record CommittedImage(String objectKey, ImageFormat format, long sizeBytes) {
	}
}
