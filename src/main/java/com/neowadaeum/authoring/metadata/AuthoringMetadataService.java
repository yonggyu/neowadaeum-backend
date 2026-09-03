package com.neowadaeum.authoring.metadata;

import com.neowadaeum.authoring.outline.ConditionTemplate;
import com.neowadaeum.catalog.query.StoryCatalogFacade;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 작품 만들기 화면이 고르게 할 값을 한 번에 모은다 (§13-56, 이슈 #282 · #315).
 *
 * <p><b>장르는 코드가 아니라 {@code genre} 표에서 온다.</b> 다섯을 상수로 적으면 라이브러리가
 * 보여 주는 목록과 작성자가 고를 수 있는 목록이 <b>서로 다른 정본</b>을 갖게 되고, 그 둘이
 * 갈라지는 날 작성자가 고른 장르로는 열리지 않는 섹션이 생긴다 (#315).
 *
 * <p><b>모듈 경계는 파사드로 넘는다</b> (§5.4). {@code catalog :: query} 의 {@link StoryCatalogFacade}
 * 를 부르며 {@code genre} 테이블을 직접 잡지 않는다.
 *
 * <p><b>조건 템플릿은 표를 보지 않는다.</b> 넷은 <b>{@code ConditionEvaluator} 가 지원하는 형태</b>
 * 이지 운영 데이터가 아니다 — 표에 한 줄을 더해도 그것을 평가할 코드가 없고, 평가기는 모르는
 * 키를 조용히 {@code false} 로 본다. 운영이 늘릴 수 없는 목록을 운영 데이터로 두면 그 사실이
 * 드러나지 않는다.
 */
@Service
public class AuthoringMetadataService {

	private final StoryCatalogFacade catalog;

	public AuthoringMetadataService(StoryCatalogFacade catalog) {
		this.catalog = catalog;
	}

	/**
	 * <b>트랜잭션을 열지 않는다.</b> {@link StoryCatalogFacade} 는 {@code catalogDataSource} 를
	 * {@code JdbcClient} 로 직접 읽으며, 여기서 {@code @Transactional} 을 붙이면 <b>다른 스토어의
	 * 트랜잭션 매니저</b>에 묶인다 — 읽기 하나에 스토어 경계를 흐리는 대가가 크다 (§5.3).
	 */
	public AuthoringMetadata read() {
		List<AuthoringMetadata.GenreOption> genres = this.catalog.genres().stream()
				.map(genre -> new AuthoringMetadata.GenreOption(genre.genreId(), genre.label()))
				.toList();
		return new AuthoringMetadata(genres, Arrays.asList(ConditionTemplate.values()));
	}
}
