package com.neowadaeum.authoring.image;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 이미지 객체 저장소 접속 정보 (#315, §13-65).
 *
 * <p><b>기본값을 주지 않는다</b> (§7.3). {@code ${VAR:실제값}} 패턴은 값이 빠진 배포를 조용히
 * 뜨게 만들고, 그때 이미지가 올라가는 곳은 <b>남의 버킷</b>이거나 <b>공개 버킷</b>이다.
 *
 * <p><b>절반만 설정된 상태는 부팅을 세운다.</b> 다섯 중 하나가 빠진 것은 "쓰지 않는다"가 아니라
 * 설정하다 만 것이다. <b>하나도 없으면 업로드 경로만 죽는다</b> — 부팅은 막지 않는다(로컬·CI 는
 * 저장소를 갖지 않는다). 그 상태에서 발급을 부르면 {@link DraftImageStore} 가 실패한다.
 *
 * <p><b>실제 엔드포인트·버킷·자격증명을 레포에 적지 않는다</b> (S-11).
 *
 * @param bucket    <b>비공개여야 한다</b> — 공개 읽기가 열린 버킷이면 I-8 이 코드 밖에서 깨진다
 * @param accessKey 접근 키. <b>로그에 남기지 않는다</b> (S-3)
 * @param secretKey 비밀 키. <b>로그에 남기지 않는다</b> (S-3)
 * @param uploadUrlTtl 업로드 URL 수명. 길수록 유출된 URL 이 오래 살아 있다
 */
@ConfigurationProperties("app.image-storage")
public record ImageStorageProperties(String endpoint, String region, String bucket,
		String accessKey, String secretKey, Duration uploadUrlTtl) {

	public ImageStorageProperties {
		uploadUrlTtl = (uploadUrlTtl != null) ? uploadUrlTtl : Duration.ofMinutes(10);
		int present = count(endpoint) + count(region) + count(bucket) + count(accessKey)
				+ count(secretKey);
		if (present != 0 && present != 5) {
			throw new IllegalStateException("app.image-storage needs endpoint, region, bucket, "
					+ "access-key and secret-key together — a partial one uploads somewhere "
					+ "nobody chose (#315)");
		}
	}

	/**
	 * 설정이 있는가. <b>해석되지 않은 플레이스홀더는 값이 아니다</b> — {@code .env} 가 없는
	 * 환경에서 {@code ${VAR}} 는 리터럴로 남고, 그것을 값으로 받으면 URI 파싱이 엉뚱한 자리에서
	 * 깨진다.
	 */
	public boolean configured() {
		return count(this.endpoint) == 1;
	}

	private static int count(String value) {
		return (value != null && !value.isBlank() && !value.startsWith("${")) ? 1 : 0;
	}
}
