package com.neowadaeum.authoring.image;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 객체 저장소 하나 (#315, §13-65). 서명하고, 확인하고, 어긋난 것을 지운다.
 *
 * <p><b>인터페이스를 두지 않는다.</b> 구현이 하나이고 바뀌는 것은 <b>엔드포인트</b>이지 코드가
 * 아니다 — 테스트도 같은 자리를 쓴다(고정 응답 서버로 돌린다).
 *
 * <p><b>버킷은 비공개다.</b> 나가는 URL 은 업로드용 하나뿐이고 짧게 산다 — 읽기 URL 을 만들지
 * 않는 것이 승인 전 노출을 막는 방법이다 (I-8).
 *
 * <p><b>모든 호출이 외부 HTTP 다.</b> 트랜잭션 안에서 부르지 않는다.
 */
@Component
public class DraftImageStore {

	/** 이미지 하나의 상한 — <b>5 MiB</b> (#315). 설정으로 두면 계약이 말한 값과 갈라진다. */
	public static final long MAX_BYTES = 5L * 1024 * 1024;

	private static final Logger log = LoggerFactory.getLogger(DraftImageStore.class);

	private final ImageStorageProperties properties;

	private final S3Client client;

	private final S3Presigner presigner;

	public DraftImageStore(ImageStorageProperties properties) {
		this.properties = properties;
		if (!properties.configured()) {
			// 부팅은 막지 않는다. 부르는 자리에서 실패한다 — 조용히 통과하는 경로는 없다.
			log.warn("image.storage.unconfigured — uploads fail until app.image-storage is set");
			this.client = null;
			this.presigner = null;
			return;
		}
		URI endpoint = URI.create(properties.endpoint());
		Region region = Region.of(properties.region());
		var credentials = StaticCredentialsProvider.create(
				AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
		// path-style 을 강제한다. 가상 호스트 방식은 버킷 이름을 DNS 이름으로 만들며,
		// 자체 호스팅 저장소는 그 이름을 갖지 않는다.
		S3Configuration s3 = S3Configuration.builder().pathStyleAccessEnabled(true).build();
		this.client = S3Client.builder().endpointOverride(endpoint).region(region)
				.credentialsProvider(credentials).serviceConfiguration(s3).build();
		this.presigner = S3Presigner.builder().endpointOverride(endpoint).region(region)
				.credentialsProvider(credentials).serviceConfiguration(s3).build();
	}

	/**
	 * 업로드 URL 하나 (§13-65). <b>{@code Content-Type} 이 서명에 들어간다</b> — 다른 형식으로
	 * 올리면 저장소가 거절하므로 형식 제한이 클라이언트의 약속이 아니다. 크기는 PUT 서명으로
	 * 묶이지 않아 {@link #verifyStored} 가 따로 있다.
	 */
	public URI presignUpload(String objectKey, ImageFormat format) {
		if (this.presigner == null) {
			throw notConfigured();
		}
		return URI.create(this.presigner.presignPutObject(request -> request
				.signatureDuration(this.properties.uploadUrlTtl())
				.putObjectRequest(put -> put.bucket(this.properties.bucket()).key(objectKey)
						.contentType(format.contentType())))
				.url().toString());
	}

	/**
	 * 올라온 바이트를 서버가 확인한다 (§13-65).
	 *
	 * <p><b>여기가 크기 제한이 실제로 걸리는 자리다.</b> 확인하지 않으면 상한은 클라이언트가
	 * 지키기로 한 약속일 뿐이다. <b>어긋나면 지운다</b> — 아무도 지우지 않는 객체를 남기지 않는다.
	 *
	 * @throws ApiException {@code VALIDATION_ERROR} — 없거나, 형식이 다르거나, 상한을 넘었다
	 */
	public StoredImage verifyStored(String objectKey) {
		if (this.client == null) {
			throw notConfigured();
		}
		HeadObjectResponse head = head(objectKey);
		ImageFormat format = ImageFormat.ofContentType(head.contentType());
		long size = (head.contentLength() != null) ? head.contentLength() : Long.MAX_VALUE;
		if (format == null || size == 0 || size > MAX_BYTES) {
			this.client.deleteObject(req -> req.bucket(this.properties.bucket()).key(objectKey));
			log.info("image.rejected reason={}", (format == null) ? "format" : "size");
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		return new StoredImage(format, size);
	}

	/** 올라온 적이 없으면 잘못된 요청이다 — 서버의 문제가 아니다. */
	private HeadObjectResponse head(String objectKey) {
		try {
			return this.client.headObject(req -> req.bucket(this.properties.bucket()).key(objectKey));
		}
		catch (NoSuchKeyException ex) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		catch (S3Exception ex) {
			if (ex.statusCode() == 404) {
				throw new ApiException(ErrorCode.VALIDATION_ERROR);
			}
			throw ex;
		}
	}

	/** 설정 없는 배포다. 요청이 잘못된 것이 아니므로 5xx 이며, 내부를 말하지 않는다 (S-6). */
	private static ApiException notConfigured() {
		return new ApiException(ErrorCode.INTERNAL_ERROR);
	}

	/** 확인된 객체. 계약이 돌려주는 값은 <b>클라이언트가 말한 것이 아니라 이것</b>이다. */
	public record StoredImage(ImageFormat format, long sizeBytes) {
	}

	/** 컨텍스트가 닫힐 때 커넥션을 놓는다. Spring 이 이름으로 찾는다. */
	public void close() {
		if (this.client != null) {
			this.client.close();
			this.presigner.close();
		}
	}
}
