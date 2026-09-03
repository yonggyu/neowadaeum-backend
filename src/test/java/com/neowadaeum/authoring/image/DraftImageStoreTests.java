package com.neowadaeum.authoring.image;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #315 · §13-65 — <b>서버가 바이트를 보지 못한다는 사실을 설계가 감당한다.</b>
 *
 * <p>presigned URL 은 브라우저가 저장소로 직접 올리게 한다. 그래서 형식과 크기는 두 자리에서
 * 각각 걸린다 — <b>형식은 서명</b>이, <b>크기는 업로드 뒤 확인</b>이 건다. 둘 중 하나라도
 * 없으면 상한은 클라이언트가 지키기로 한 약속일 뿐이다.
 *
 * <p><b>실제 저장소를 부르지 않는다.</b> 고정 응답 서버가 S3 호환 엔드포인트 자리에 선다.
 * 자격증명·버킷명은 전부 테스트 전용 가짜다 (S-11).
 */
class DraftImageStoreTests {

	private static final String KEY = "drafts/00000000-0000-4000-8000-000000000001/cover/a.png";

	private WireMockServer storage;

	private DraftImageStore store;

	@BeforeEach
	void start() {
		this.storage = new WireMockServer(
				WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
		this.storage.start();
		this.store = new DraftImageStore(new ImageStorageProperties(
				"http://localhost:" + this.storage.port(), "us-east-1", "test-only-bucket",
				"test-only-access-key", "test-only-secret-key", Duration.ofMinutes(10)));
	}

	@AfterEach
	void stop() {
		this.store.close();
		this.storage.stop();
	}

	/**
	 * <b>형식은 서명이 건다.</b> {@code Content-Type} 이 서명된 헤더에 들어가므로, 브라우저가 다른
	 * 형식으로 올리면 서명이 맞지 않아 저장소가 거절한다 — 클라이언트의 선의가 아니다.
	 */
	@Test
	void S13_65_the_upload_url_binds_the_content_type() {
		URI url = this.store.presignUpload(KEY, ImageFormat.PNG);

		assertThat(url.getQuery()).contains("X-Amz-SignedHeaders");
		assertThat(url.toString().toLowerCase(java.util.Locale.ROOT)).contains("content-type");
	}

	/** <b>URL 은 짧게 산다.</b> 유출된 발급 URL 이 오래 살아 있으면 그것이 곧 업로드 권한이다. */
	@Test
	void S13_65_the_upload_url_expires() {
		assertThat(this.store.presignUpload(KEY, ImageFormat.JPEG).getQuery())
				.contains("X-Amz-Expires=600");
	}

	/** 저장소가 말한 것을 그대로 돌려준다 — 요청이 말한 것이 아니다. */
	@Test
	void S13_65_a_stored_image_reports_what_the_store_saw() {
		stubHead(200, "image/png", 1024);

		DraftImageStore.StoredImage stored = this.store.verifyStored(KEY);

		assertThat(stored.format()).isEqualTo(ImageFormat.PNG);
		assertThat(stored.sizeBytes()).isEqualTo(1024);
	}

	/**
	 * <b>상한이 실제로 걸리는 자리다.</b> 서명은 크기를 묶지 못하므로 여기서 확인하지 않으면
	 * 5 MiB 는 계약에만 적힌 숫자가 된다.
	 */
	@Test
	void S13_65_an_oversize_object_is_rejected_and_removed() {
		stubHead(200, "image/png", DraftImageStore.MAX_BYTES + 1);

		assertThatThrownBy(() -> this.store.verifyStored(KEY))
				.isInstanceOf(ApiException.class)
				.extracting(error -> ((ApiException) error).errorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR);
		this.storage.verify(deleteRequestedFor(urlMatching(".*" + KEY + ".*")));
	}

	/** 목록에 없는 형식도 같은 자리에서 걸리고, 같은 이유로 지워진다. */
	@Test
	void S13_65_an_unlisted_format_is_rejected_and_removed() {
		stubHead(200, "image/gif", 1024);

		assertThatThrownBy(() -> this.store.verifyStored(KEY)).isInstanceOf(ApiException.class);
		this.storage.verify(deleteRequestedFor(urlMatching(".*" + KEY + ".*")));
	}

	/** 올린 적이 없는 것을 확정할 수는 없다. 서버의 문제가 아니므로 4xx 다. */
	@Test
	void S13_65_a_missing_object_is_a_bad_request() {
		this.storage.stubFor(head(anyUrl()).willReturn(aResponse().withStatus(404)));

		assertThatThrownBy(() -> this.store.verifyStored(KEY))
				.isInstanceOf(ApiException.class)
				.extracting(error -> ((ApiException) error).errorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR);
	}

	/**
	 * <b>읽기 URL 을 만들지 않는다</b> (I-8).
	 *
	 * <p>버킷은 비공개이므로 이미지를 볼 수 있는 유일한 방법은 서버가 서명해 주는 것이다. 그
	 * 자리가 하나도 없다는 것이 <b>승인 전 노출 경로가 없다</b>는 말의 구조적 형태다 — 열람은
	 * 소유자·검수자에 한해 뒤에 열린다.
	 */
	@Test
	void I8_the_store_issues_no_read_url() {
		assertThat(Arrays.stream(DraftImageStore.class.getDeclaredMethods())
				.filter(method -> method.getReturnType() == URI.class)
				.map(Method::getName))
				.containsExactly("presignUpload");
	}

	private void stubHead(int status, String contentType, long length) {
		this.storage.stubFor(head(anyUrl()).willReturn(aResponse().withStatus(status)
				.withHeader("Content-Type", contentType)
				.withHeader("Content-Length", Long.toString(length))));
		this.storage.stubFor(delete(anyUrl()).willReturn(aResponse().withStatus(204)));
	}
}
