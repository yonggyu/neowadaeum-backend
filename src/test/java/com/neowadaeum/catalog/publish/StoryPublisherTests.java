package com.neowadaeum.catalog.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.catalog.query.StoryVersionFacade;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * B-53 · B-56 — <b>작품 하나는 넷이 함께 있어야 성립한다</b> (§2.3).
 *
 * <p>작품·버전·챕터·엔딩 중 하나만 들어가면 그것은 <b>플레이하다 중간에 멈추는</b> 작품이다.
 */
class StoryPublisherTests extends ContainerTestBase {

	private static final String STATE_SCHEMA = "{\"affinity\":{\"yuna\":{\"min\":0,\"max\":100,\"maxDeltaPerTurn\":5}}}";

	@Autowired
	private StoryPublisher publisher;

	@Autowired
	private StoryVersionFacade versions;

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource catalog;

	private final List<UUID> published = new java.util.ArrayList<>();

	/**
	 * <b>만든 작품을 치운다.</b>
	 *
	 * <p>컨테이너는 한 벌이고 시드 작품을 보는 테스트가 함께 돈다 — 남긴 작품이 그쪽의 카탈로그
	 * 조회에 섞인다. 남의 테스트를 실패시키는 방식으로 실재를 증명하면 안 된다.
	 */
	@org.junit.jupiter.api.AfterEach
	void clear() {
		JdbcClient jdbc = JdbcClient.create(this.catalog);
		for (UUID storyId : this.published) {
			// FK 순서대로 지운다 — 버전이 챕터·엔딩을 잡고 있고 작품이 버전을 잡고 있다.
			jdbc.sql("DELETE FROM chapter_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM ending_def WHERE story_id = ?").param(storyId).update();
			jdbc.sql("UPDATE story SET current_version_id = NULL WHERE id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story_version WHERE story_id = ?").param(storyId).update();
			jdbc.sql("DELETE FROM story WHERE id = ?").param(storyId).update();
		}
		this.published.clear();
	}

	/** 발행하고 <b>치울 목록에 적어 둔다.</b> */
	private StoryPublisher.PublishedVersion publish(StoryDefinition definition) {
		StoryPublisher.PublishedVersion result = this.publisher.publishNew(definition, STATE_SCHEMA);
		this.published.add(result.storyId());
		return result;
	}

	/** 발행하면 <b>판정에 필요한 한 벌</b>이 곧바로 읽힌다 — 파이프라인이 그것을 요구한다. */
	@Test
	void S2_3_a_published_version_is_immediately_readable() {
		var published = publish(definition());

		assertThat(this.versions.findByVersionId(published.versionId())).get().satisfies(view -> {
			assertThat(view.worldPrompt()).contains("봄의 학교");
			assertThat(view.chapters()).hasSize(2);
			assertThat(view.endings()).hasSize(2);
		});
	}

	/** <b>미리보기가 만든 작품은 라이브러리에 뜨지 않는다</b> (R2.3, I-8). */
	@Test
	void R2_3_a_new_story_starts_private_and_in_draft() {
		var published = publish(definition());

		assertThat(column(published.storyId(), "visibility")).isEqualTo("private");
		assertThat(column(published.storyId(), "review_status")).isEqualTo("draft");
		assertThat(column(published.storyId(), "author_type")).isEqualTo("user");
	}

	/** <b>발행이 곧 현재 버전이 아니다</b> (R8.8) — 검수를 통과하기 전에는 가리키지 않는다. */
	@Test
	void R8_8_publishing_does_not_make_it_current() {
		var published = publish(definition());

		assertThat(column(published.storyId(), "current_version_id")).isNull();

		this.publisher.markCurrent(published.storyId(), published.versionId());

		assertThat(column(published.storyId(), "current_version_id"))
				.isEqualTo(published.versionId().toString());
	}

	/** 새 버전은 번호가 오른다. <b>옛 버전은 그대로 남는다</b> — 진행 중 세션이 그것을 본다 (I-4). */
	@Test
	void R8_8_a_revision_adds_a_version_and_keeps_the_old_one() {
		var first = publish(definition());

		var second = this.publisher.publishRevision(first.storyId(), definition(), STATE_SCHEMA);

		assertThat(second.versionId()).isNotEqualTo(first.versionId());
		assertThat(this.versions.findByVersionId(first.versionId())).isPresent();
	}

	/** <b>슬러그가 겹치지 않는다</b> — 같은 제목이 둘이어도 발행된다. */
	@Test
	void S13_15_two_stories_with_the_same_title_both_publish() {
		publish(definition());

		assertThat(publish(definition())).isNotNull();
	}

	/** <b>기본 엔딩은 정확히 하나다</b> (R2.2) — 0개면 끝나지 못하고, 2개면 행 순서에 달린다. */
	@Test
	void R2_2_a_story_needs_exactly_one_default_ending() {
		assertThatThrownBy(() -> definitionWithEndings(
				List.of(new StoryDefinition.Ending(1, "끝", null, "{}", false, false))))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> definitionWithEndings(List.of(
				new StoryDefinition.Ending(1, "끝", null, null, true, false),
				new StoryDefinition.Ending(2, "다른 끝", null, null, true, false))))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/** 챕터가 없으면 첫 턴부터 갈 곳이 없다. */
	@Test
	void S2_3_a_story_needs_at_least_one_chapter() {
		assertThatThrownBy(() -> new StoryDefinition(UUID.randomUUID(), "제목", null, null, "세계관",
				"affinity", List.of(), defaultEndings()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/** <b>기본 엔딩의 조건은 저장되지 않는다</b> (§13-16) — 조건이 있으면 그것은 폴백이 아니다. */
	@Test
	void S13_16_the_default_ending_stores_no_condition() {
		var published = publish(definitionWithEndings(List.of(
				new StoryDefinition.Ending(1, "일반", null, "{\"gte\":1}", false, false),
				new StoryDefinition.Ending(2, "기본", null, "{\"gte\":1}", true, false))));

		assertThat(JdbcClient.create(this.catalog)
				.sql("SELECT condition::text FROM ending_def WHERE story_version_id = ? AND is_default")
				.param(published.versionId()).query(String.class).optional()).isEmpty();
	}

	/** {@code current_version_id} 는 비어 있을 수 있다 — {@code single()} 은 null 을 거부한다. */
	private String column(UUID storyId, String name) {
		return JdbcClient.create(this.catalog).sql("SELECT " + name + "::text FROM story WHERE id = ?")
				.param(storyId).query(String.class).optional().orElse(null);
	}

	private static StoryDefinition definition() {
		return definitionWithEndings(defaultEndings());
	}

	private static StoryDefinition definitionWithEndings(List<StoryDefinition.Ending> endings) {
		return new StoryDefinition(UUID.randomUUID(), "봄의 학교", "짧은 소개", "세계관 소개",
				"봄의 학교에서 시작한다.", "affinity",
				List.of(new StoryDefinition.Chapter(1, "1장", "시작", null, 1, 10),
						new StoryDefinition.Chapter(2, "2장", "전개", null, 1, 10)),
				endings);
	}

	private static List<StoryDefinition.Ending> defaultEndings() {
		return List.of(new StoryDefinition.Ending(1, "좋은 끝", "잘 끝났다.", "{\"gte\":1}", false, false),
				new StoryDefinition.Ending(2, "기본 끝", "그렇게 끝났다.", null, true, false));
	}
}
