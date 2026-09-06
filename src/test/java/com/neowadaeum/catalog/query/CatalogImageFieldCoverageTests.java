package com.neowadaeum.catalog.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * §13-85 — <b>새로 생긴 이미지 필드가 판정을 지나는지 정하게 한다</b> (#395).
 *
 * <p>{@code heroImage} 가 {@link StoryCatalogFacade} 의 판정을 지나지 않은 것은 코드가 틀려서가
 * 아니라 <b>이미지 필드가 하나 더 있었는데 아무도 그것을 다시 보지 않았기 때문</b>이다. 커버와
 * 초상은 #378 이 함께 고쳤고 히어로만 남았으며, <b>깨진 이미지는 서버에서 아무 소리도 내지
 * 않으므로</b> 그 사이에 알아챌 방법이 없었다.
 *
 * <p><b>#366 이 L1 목록에 세운 것과 같은 모양이다.</b> 목록을 파생시키지 않고 손으로 적되, 뷰에
 * 이미지 성분이 하나 늘면 <b>여기서 실패한다</b> — 그때 사람이 <i>이 값이 무엇인가</i> 를 정하고
 * 이유와 함께 적는다. 이 실패는 버그가 아니라 <b>결정이 남았다</b>는 뜻이다.
 *
 * <p><b>이 테스트는 판정을 실행하지 않는다.</b> 그 확인은 {@code StoryCatalogFacadeTests} 의
 * {@code S13_79_*} 가 필드마다 컨테이너 위에서 한다 — 여기가 지키는 것은 <b>그 확인을 받지 않은
 * 필드가 조용히 생기지 못하게</b> 하는 것뿐이다. 둘을 합치면 목록이 판정의 사본이 되고, 판정이
 * 바뀌는 날 두 곳이 함께 틀린다.
 */
class CatalogImageFieldCoverageTests {

	/** 뷰가 사는 곳. <b>새 뷰도 함께 걸린다</b> — 필드만 세면 뷰 하나가 통째로 샌다. */
	private static final String VIEW_PACKAGE = "com.neowadaeum.catalog.query";

	/** 화면이 {@code <img src>} 에 넣는 값이라는 표식. */
	private static final String IMAGE_SUFFIX = "Image";

	/**
	 * {@code StoryCatalogFacade.imageUrlOf} 를 지나는 성분과 <b>그 값이 무엇인지</b>.
	 *
	 * <p>이유를 함께 적는 이유는, 목록만 있으면 다음 사람이 <b>거슬리는 실패를 지우는 자리</b>로
	 * 쓰기 때문이다 (#366 과 같다).
	 */
	private static final Map<String, String> SIGNED = Map.of(
			"StoryCardView.coverImage", "라이브러리 카드의 커버 (§13-79)",
			"MyStoryView.coverImage", "내 작품의 커버. 승인 전에는 null 이다 (I-8, §13-78)",
			"StoryBriefView.coverImage", "이어하기 카드의 커버 (§13-79)",
			"CharacterCardView.portraitImage", "상세의 인물 초상 (§13-79)",
			"StoryDetailView.heroImage", "상세의 히어로. UGC 발행은 아직 이 자리를 채우지 않는다 (#395)");

	/**
	 * <b>새 이미지 필드는 판정을 지날지 정하고 나서 생긴다.</b>
	 *
	 * <p>지나지 않는 값이 생기면 그것도 이유와 함께 여기 적는다 — 지금은 그런 값이 없다.
	 */
	@Test
	void S13_85_a_new_image_field_of_a_catalog_view_must_be_decided_here() {
		assertThat(imageComponents())
				.as("카탈로그 뷰의 이미지 성분은 imageUrlOf 판정을 지나거나, 지나지 않는 이유가 "
						+ "여기 적혀 있거나 둘 중 하나다 (#395, §13-85)")
				.containsExactlyInAnyOrderElementsOf(SIGNED.keySet());
	}

	/** {@code <뷰 이름>.<성분 이름>} 목록. 레코드가 아닌 것은 화면에 나가는 값을 담지 않는다. */
	private static List<String> imageComponents() {
		List<String> found = new ArrayList<>();
		for (Class<?> view : viewClasses()) {
			if (!view.isRecord()) {
				continue;
			}
			for (RecordComponent component : view.getRecordComponents()) {
				if (component.getName().endsWith(IMAGE_SUFFIX)) {
					found.add(view.getSimpleName() + "." + component.getName());
				}
			}
		}
		return found;
	}

	private static List<Class<?>> viewClasses() {
		var scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));
		List<Class<?>> classes = new ArrayList<>();
		for (var candidate : scanner.findCandidateComponents(VIEW_PACKAGE)) {
			try {
				classes.add(Class.forName(candidate.getBeanClassName()));
			}
			catch (ClassNotFoundException ex) {
				throw new IllegalStateException(ex);
			}
		}
		return classes;
	}
}
