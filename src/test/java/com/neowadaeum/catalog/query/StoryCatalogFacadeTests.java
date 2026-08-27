package com.neowadaeum.catalog.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * B-15(1/2) — <b>무엇이 보이고 무엇이 보이지 않는가</b> (§13.2, R2.3, I-8).
 *
 * <p>노출 조건이 이 파사드의 SQL 한 곳에 있다. 여기서 새면 라이브러리 · 상세 · 랜딩이 함께 샌다.
 *
 * <p>작품을 SQL 로 직접 넣는다 — {@code story} 에는 아직 엔티티가 없고(B-08 후속 후보),
 * 무엇보다 <b>파사드가 막는지</b>를 보려면 막힐 데이터를 만들 수 있어야 한다.
 */
class StoryCatalogFacadeTests extends ContainerTestBase {

	/** S-4 시드가 넣은 공식 작품. 승인·공개 상태다. */
	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final Instant PUBLISHED = Instant.parse("2026-08-20T00:00:00Z");

	@Autowired
	@Qualifier("catalogDataSource")
	private DataSource dataSource;

	@Autowired
	private StoryCatalogFacade facade;

	private final List<UUID> created = new java.util.ArrayList<>();

	@AfterEach
	void removeCreated() throws SQLException {
		try (Connection connection = this.dataSource.getConnection()) {
			for (UUID id : this.created) {
				try (PreparedStatement statement = connection
						.prepareStatement("DELETE FROM story WHERE id = ?")) {
					statement.setObject(1, id);
					statement.executeUpdate();
				}
			}
		}
		this.created.clear();
	}

	/** §13.2 — 장르는 화면 순서대로 온다. {@code display_order} 가 유일하므로 결정론이다. */
	@Test
	void S13_2_genres_come_in_display_order() {
		assertThat(this.facade.genres()).extracting(GenreView::genreId)
				.containsExactly("romance", "school", "fantasy", "action", "mystery");
	}

	/** 시드 작품이 추천 섹션에 있고, 장르 표기가 함께 온다. */
	@Test
	void S13_2_the_official_story_appears_in_recommended_with_its_genres() {
		StoryPage page = this.facade.cards(section("recommended"), null, null);

		assertThat(page.stories()).extracting(StoryCardView::storyId).contains(SEED_STORY);
		assertThat(page.stories()).filteredOn(card -> card.storyId().equals(SEED_STORY))
				.singleElement()
				.satisfies(card -> {
					assertThat(card.authorType()).isEqualTo("official");
					assertThat(card.genres()).containsExactly("romance", "school");
					assertThat(card.shortDescription()).isNotBlank();
				});
	}

	/** 장르 섹션이 그 장르의 작품만 담는다. */
	@Test
	void S13_2_a_genre_section_filters_by_genre() {
		assertThat(this.facade.cards(section("genre:school"), null, null).stories())
				.extracting(StoryCardView::storyId).contains(SEED_STORY);
		assertThat(this.facade.cards(section("genre:fantasy"), null, null).stories())
				.extracting(StoryCardView::storyId).doesNotContain(SEED_STORY);
	}

	/**
	 * <b>R13.1 — 공식과 사용자를 같은 섹션에 섞지 않는다.</b>
	 *
	 * <p>승인·공개된 사용자 작품이라도 추천·장르 섹션에는 오지 않는다.
	 */
	@Test
	void R13_1_an_approved_user_story_never_enters_an_official_section() {
		UUID userStory = insertStory("user", "public", "approved");

		assertThat(this.facade.cards(section("recommended"), null, null).stories())
				.extracting(StoryCardView::storyId).doesNotContain(userStory);
		assertThat(this.facade.cards(section("community"), null, null).stories())
				.extracting(StoryCardView::storyId).contains(userStory);
	}

	/**
	 * <b>I-8 · R2.3 — 승인되지 않았거나 비공개인 사용자 작품은 어떤 섹션에도 없다.</b>
	 *
	 * <p>이것이 이 파일에서 가장 중요한 단언이다. 새는 순간 검수가 의미를 잃는다.
	 */
	@Test
	void I8_unapproved_or_private_user_stories_are_invisible() {
		UUID pending = insertStory("user", "public", "pending");
		UUID rejected = insertStory("user", "public", "rejected");
		UUID privateStory = insertStory("user", "private", "approved");

		List<UUID> community = this.facade.cards(section("community"), null, null).stories().stream()
				.map(StoryCardView::storyId).toList();

		assertThat(community).doesNotContain(pending, rejected, privateStory);
	}

	/** {@code unlisted} 는 보인다 — {@code private} 만 가린다 (R2.3). */
	@Test
	void R2_3_unlisted_is_not_private() {
		UUID unlisted = insertStory("user", "unlisted", "approved");

		assertThat(this.facade.cards(section("community"), null, null).stories())
				.extracting(StoryCardView::storyId).contains(unlisted);
	}

	/**
	 * 커서가 쪽을 잇는다 — <b>중복도 누락도 없다.</b>
	 *
	 * <p>발행 시각만으로 정렬하면 같은 시각의 작품들이 경계에서 겹치거나 사라진다. 시드처럼
	 * 한 번에 넣은 데이터에서 실제로 일어나므로 <b>같은 시각</b>으로 만들어 확인한다.
	 */
	@Test
	void S13_2_the_cursor_pages_without_duplicates_or_gaps() {
		for (int i = 0; i < 5; i++) {
			insertStory("user", "public", "approved");
		}

		StoryPage first = this.facade.cards(section("community"), null, 2);
		StoryPage second = this.facade.cards(section("community"), first.nextCursor(), 2);
		StoryPage third = this.facade.cards(section("community"), second.nextCursor(), 2);

		assertThat(first.stories()).hasSize(2);
		assertThat(first.hasMore()).isTrue();
		List<UUID> seen = java.util.stream.Stream.of(first, second, third)
				.flatMap(page -> page.stories().stream())
				.map(StoryCardView::storyId)
				.toList();
		assertThat(seen).doesNotHaveDuplicates().hasSize(5);
		assertThat(third.hasMore()).isFalse();
	}

	/** 해석되지 않는 커서는 처음부터로 본다 — 500 을 내는 것보다 낫다. */
	@Test
	void S13_2_a_broken_cursor_starts_from_the_beginning() {
		StoryPage page = this.facade.cards(section("recommended"), "not-a-cursor", null);

		assertThat(page.stories()).extracting(StoryCardView::storyId).contains(SEED_STORY);
	}

	/** 모르는 섹션 키는 빈 섹션으로 흡수되지 않는다 — 오타가 "작품 없음"으로 보이면 안 된다. */
	@Test
	void S13_2_an_unknown_section_key_does_not_parse() {
		assertThat(LibrarySectionKey.parse("genre:")).isEmpty();
		assertThat(LibrarySectionKey.parse("popular")).isEmpty();
		assertThat(LibrarySectionKey.parse(null)).isEmpty();
		assertThat(LibrarySectionKey.parse("genre:school")).get()
				.extracting(LibrarySectionKey::value).isEqualTo("genre:school");
	}

	private static LibrarySectionKey section(String raw) {
		return LibrarySectionKey.parse(raw).orElseThrow();
	}

	/** 파사드가 막는지 보려면 막힐 데이터를 만들 수 있어야 한다. */
	private UUID insertStory(String authorType, String visibility, String reviewStatus) {
		UUID id = UUID.randomUUID();
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO story (id, slug, title, short_desc, author_type, visibility,
						                   review_status, current_version_id, published_at, created_at)
						VALUES (?, ?, '테스트 작품', '한 줄 소개', ?, ?, ?, ?, ?, ?)
						""")) {
			statement.setObject(1, id);
			statement.setString(2, "test-" + id);
			statement.setString(3, authorType);
			statement.setString(4, visibility);
			statement.setString(5, reviewStatus);
			statement.setObject(6, UUID.fromString("11111111-1111-4111-8111-111111111111"));
			statement.setTimestamp(7, java.sql.Timestamp.from(PUBLISHED));
			statement.setTimestamp(8, java.sql.Timestamp.from(PUBLISHED));
			statement.executeUpdate();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
		this.created.add(id);
		return id;
	}
}
