package com.neowadaeum.catalog.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

	@Autowired
	private Clock clock;

	/** #290 — 검수 시각은 authoring 이 소유한 표에서 온다. 파사드는 계약만 안다 (§13-57). */
	@Autowired
	private com.neowadaeum.common.spi.StoryReviewTimesQuery reviewTimes;

	/** #340 — 원고 id 도 같은 이유로 authoring 이 소유한 표에서 온다. */
	@Autowired
	private com.neowadaeum.common.spi.StoryDraftLinkQuery draftLinks;

	/** #378 — 승인된 UGC 커버의 서명은 버킷 설정을 가진 모듈이 한다. 파사드는 계약만 안다. */
	@Autowired
	private com.neowadaeum.common.spi.ImageReadUrlSigner coverUrls;

	/** 커버 키. <b>가상의 문자열이며</b> 그 자체로는 아무것도 열지 못한다 (S-11, #315). */
	private static final String COVER_KEY = "drafts/00000000-0000-4000-8000-0000000000d0/cover/"
			+ "00000000-0000-4000-8000-0000000000d1.jpg";

	/** 공식 시드가 이미 들고 있는 <b>실제 주소</b> 자리 (S-11 — 가상의 호스트다). */
	private static final String OFFICIAL_COVER_URL = "https://cover.invalid/official.jpg";

	/** 초상 키. 커버와 <b>같은 버킷에 같은 방식으로</b> 올라간다 (§13-65, S-11: 가상의 문자열). */
	private static final String PORTRAIT_KEY = "drafts/00000000-0000-4000-8000-0000000000d0/"
			+ "portrait/00000000-0000-4000-8000-0000000000d2.png";

	/** 공식 작품의 초상은 이미 실제 주소다 (S-11 — 가상의 호스트다). */
	private static final String OFFICIAL_PORTRAIT_URL = "https://cover.invalid/official-portrait.png";

	private final List<UUID> created = new java.util.ArrayList<>();

	private final List<UUID> createdVersions = new java.util.ArrayList<>();

	private final List<UUID> createdProfiles = new java.util.ArrayList<>();

	@AfterEach
	void removeCreated() throws SQLException {
		try (Connection connection = this.dataSource.getConnection()) {
			// 인물이 버전을, 버전이 작품을 참조한다 — 순서를 뒤집으면 FK 가 막는다.
			for (UUID id : this.createdVersions) {
				try (PreparedStatement statement = connection
						.prepareStatement("DELETE FROM character WHERE story_version_id = ?")) {
					statement.setObject(1, id);
					statement.executeUpdate();
				}
				try (PreparedStatement statement = connection
						.prepareStatement("DELETE FROM story_version WHERE id = ?")) {
					statement.setObject(1, id);
					statement.executeUpdate();
				}
			}
			for (UUID id : this.created) {
				try (PreparedStatement statement = connection
						.prepareStatement("DELETE FROM story WHERE id = ?")) {
					statement.setObject(1, id);
					statement.executeUpdate();
				}
			}
			for (UUID ref : this.createdProfiles) {
				try (PreparedStatement statement = connection
						.prepareStatement("DELETE FROM author_profile WHERE player_ref = ?")) {
					statement.setObject(1, ref);
					statement.executeUpdate();
				}
			}
		}
		this.createdVersions.clear();
		this.created.clear();
		this.createdProfiles.clear();
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

	/**
	 * <b>{@code unlisted} 는 목록에 오르지 않는다</b> (§13-54).
	 *
	 * <p>{@code unlisted} 는 사람 검수를 요구하지 않는 범위다 (R8.6). 목록에 올리면 사람이
	 * 본 적 없는 작품을 둘러보던 사람이 그냥 만나고, #306 이 그 목록을 익명에게 열었다.
	 */
	@Test
	void S13_54_unlisted_stories_are_not_listed() {
		UUID unlisted = insertStory("user", "unlisted", "approved");

		assertThat(this.facade.cards(section("community"), null, null).stories())
				.extracting(StoryCardView::storyId).doesNotContain(unlisted);
	}

	/**
	 * <b>그러나 링크로는 닿는다</b> (§13-54).
	 *
	 * <p>목록에서 뺀 것이 곧 {@code private} 이 되는 것은 아니다. 두 값이 같아지면 셋 중
	 * 하나가 뜻을 잃는다 — {@code unlisted} 는 <b>목록에 없되 주소를 아는 사람은 읽는</b>
	 * 상태이며, 그것이 {@code public} 과 갈라지는 유일한 지점이다.
	 */
	@Test
	void S13_54_unlisted_stories_stay_reachable_by_id() {
		UUID unlisted = insertStory("user", "unlisted", "approved");

		assertThat(this.facade.detail(unlisted)).isPresent();
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

	/**
	 * <b>R13.1 · #258 — 커뮤니티 카드가 작성자를 말한다.</b>
	 *
	 * <p>{@code authorType} 만으로는 "사용자 작품"까지밖에 말하지 못한다. 그 표기가 공식과
	 * 사용자를 섞지 않는 근거이므로 <b>누구인가</b>가 카드에 실려야 한다.
	 *
	 * <p>나가는 것은 닉네임뿐이다 — {@code playerRef} 는 카드 어디에도 없다 (I-3, §13-7).
	 */
	@Test
	void R13_1_a_community_card_carries_the_author_nickname() {
		UUID authorRef = UUID.randomUUID();
		insertProfile(authorRef, "연우");
		UUID story = insertStory("user", "public", "approved", authorRef);

		assertThat(this.facade.cards(section("community"), null, null).stories())
				.filteredOn(card -> card.storyId().equals(story))
				.singleElement()
				.satisfies(card -> {
					assertThat(card.authorDisplayName()).isEqualTo("연우");
					// S-11 — "있어야 할 것"만 보면 식별자가 함께 새어도 통과한다.
					assertThat(card.toString()).doesNotContain(authorRef.toString());
				});
	}

	/** <b>#258 — 공식 작품에는 작성자 표기가 없다.</b> {@code authorType} 이 이미 답이다. */
	@Test
	void R13_1_an_official_card_has_no_author_nickname() {
		assertThat(this.facade.cards(section("recommended"), null, null).stories())
				.filteredOn(card -> card.storyId().equals(SEED_STORY))
				.singleElement()
				.satisfies(card -> assertThat(card.authorDisplayName()).isNull());
	}

	/**
	 * <b>#258 · I-3 — 프로필이 없으면 비어 있다.</b>
	 *
	 * <p>표시명이 없다고 {@code playerRef} 를 대신 내보내지 않는다. 비워 두는 쪽이 답이다.
	 */
	@Test
	void R13_1_an_author_without_a_profile_yields_no_nickname() {
		UUID authorRef = UUID.randomUUID();
		UUID story = insertStory("user", "public", "approved", authorRef);

		assertThat(this.facade.cards(section("community"), null, null).stories())
				.filteredOn(card -> card.storyId().equals(story))
				.singleElement()
				.satisfies(card -> {
					assertThat(card.authorDisplayName()).isNull();
					assertThat(card.toString()).doesNotContain(authorRef.toString());
				});
	}

	/**
	 * <b>§15 — 카드가 늘어도 조회 수는 늘지 않는다.</b>
	 *
	 * <p>작성자 표시명을 카드마다 물으면 20장이 21번의 조회가 된다. 그것이 이 필드를 더하면서
	 * 가장 쉽게 저지르는 실수이므로, <b>느려졌는지</b>가 아니라 <b>몇 번 물었는지</b>를 센다 —
	 * 시간은 기계에 따라 흔들리지만 조회 수는 흔들리지 않는다.
	 */
	@Test
	void S15_reading_more_cards_does_not_cost_more_queries() {
		for (int i = 0; i < 6; i++) {
			UUID authorRef = UUID.randomUUID();
			insertProfile(authorRef, "작성자" + i);
			insertStory("user", "public", "approved", authorRef);
		}

		AtomicInteger statements = new AtomicInteger();
		StoryCatalogFacade counted = new StoryCatalogFacade(countingDataSource(statements), this.clock,
				this.reviewTimes, this.draftLinks, this.coverUrls);

		statements.set(0);
		counted.cards(section("community"), null, 2);
		int forTwo = statements.get();

		statements.set(0);
		StoryPage six = counted.cards(section("community"), null, 6);
		int forSix = statements.get();

		assertThat(six.stories()).hasSize(6);
		assertThat(six.stories()).extracting(StoryCardView::authorDisplayName).doesNotContainNull();
		// 0 == 0 으로 통과하면 세지 못한 것이지 N+1 이 없는 것이 아니다.
		assertThat(forTwo).isPositive();
		assertThat(forSix).isEqualTo(forTwo);
	}

	/** 작성자 프로필. {@code player_ref} 가 PK 다 — 이 표에 {@code user.id} 는 없다 (I-3). */
	private void insertProfile(UUID playerRef, String displayName) {
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO author_profile (player_ref, display_name) VALUES (?, ?)")) {
			statement.setObject(1, playerRef);
			statement.setString(2, displayName);
			statement.executeUpdate();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
		this.createdProfiles.add(playerRef);
	}

	/**
	 * 나간 문장 수를 세는 {@link DataSource}. 실제 커넥션에 그대로 위임하고 개수만 기록한다.
	 *
	 * <p>N+1 은 결과가 아니라 <b>횟수</b>로 드러나므로 여기서만 잡을 수 있다.
	 */
	private DataSource countingDataSource(AtomicInteger statements) {
		ClassLoader loader = getClass().getClassLoader();
		return (DataSource) Proxy.newProxyInstance(loader, new Class<?>[] { DataSource.class },
				(proxy, method, args) -> {
					Object result = invoke(method, this.dataSource, args);
					if (result instanceof Connection connection) {
						return Proxy.newProxyInstance(loader, new Class<?>[] { Connection.class },
								(connectionProxy, connectionMethod, connectionArgs) -> {
									if (connectionMethod.getName().startsWith("prepare")
											|| "createStatement".equals(connectionMethod.getName())) {
										statements.incrementAndGet();
									}
									return invoke(connectionMethod, connection, connectionArgs);
								});
					}
					return result;
				});
	}

	private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args)
			throws Throwable {
		try {
			return method.invoke(target, args);
		}
		catch (InvocationTargetException ex) {
			throw ex.getTargetException();
		}
	}

	private static LibrarySectionKey section(String raw) {
		return LibrarySectionKey.parse(raw).orElseThrow();
	}

	/** 파사드가 막는지 보려면 막힐 데이터를 만들 수 있어야 한다. */
	/**
	 * <b>#269 — {@code user} 는 {@code author_ref} 없이 존재할 수 없다.</b>
	 *
	 * <p>이 오버로드는 항상 {@code authorType = "user"}로만 불린다. 무작위 {@code UUID}는
	 * 개별 단언과 무관하고, 제약(R13.1)을 만족시키는 것이 목적이다.
	 */
	/**
	 * <b>#378 · §13-79 — 승인된 UGC 커버는 열리는 URL 로 나간다.</b>
	 *
	 * <p>이 필드에는 <b>두 종류의 값</b>이 실려 나가고 있었다 — 공식 작품은 실제 주소, UGC 는
	 * 객체 키 (#357, §13-72). 화면은 그 둘을 구분할 방법이 없으므로 <b>UGC 커버가 전부 깨진
	 * 이미지</b>가 됐고, <b>깨진 이미지는 서버에서 아무 소리도 내지 않는다.</b>
	 *
	 * <p><b>있어야 할 것만 단언하면 키가 그대로 나가도 통과한다</b> (S-11) — 그래서 키가
	 * <b>그 자체로</b> 오지 않는다는 것을 함께 건다.
	 */
	@Test
	void S13_79_an_approved_user_cover_comes_back_signed() {
		UUID storyId = insertStory("user", "public", "approved");
		setCover(storyId, COVER_KEY);

		StoryCardView card = this.facade.cards(section("community"), null, null).stories().stream()
				.filter(story -> story.storyId().equals(storyId)).findFirst().orElseThrow();

		assertThat(card.coverImage()).isNotEqualTo(COVER_KEY).startsWith("http")
				.contains("X-Amz-Signature").contains("X-Amz-Expires=900");
	}

	/**
	 * <b>공식 작품의 값은 건드리지 않는다</b> (#378).
	 *
	 * <p>그 컬럼에는 이미 <b>실제 주소</b>가 들어 있다. 서명하려 들면 주소를 객체 키로 착각한
	 * 서명이 나가고, 지금 멀쩡한 커버가 깨진다 — <b>무엇이 UGC 인지는 {@code author_type} 이
	 * 안다.</b> 값의 모양으로 갈라내는 것은 추측이며, 추측은 시드가 바뀌는 날 조용히 틀린다.
	 */
	@Test
	void S13_79_an_official_cover_is_passed_through() {
		// 공식 작품은 author_ref 를 갖지 않는다 — story_author_type_ref_check 가 그 조합을 막는다.
		UUID storyId = insertStory("official", "public", "approved", null);
		setCover(storyId, OFFICIAL_COVER_URL);

		StoryCardView card = this.facade.cards(section("recommended"), null, null).stories().stream()
				.filter(story -> story.storyId().equals(storyId)).findFirst().orElseThrow();

		assertThat(card.coverImage()).isEqualTo(OFFICIAL_COVER_URL);
	}

	/**
	 * <b>승인 전 커버에는 서명하지 않는다</b> (I-8, §13-78).
	 *
	 * <p>서명 URL 은 그 객체의 <b>출입증</b>이라 수명 동안 게이트 없이 열린다 — 경계는
	 * <i>"승인됐는가"</i> 이며 그것은 I-8 이 그은 선이다. "내 작품"은 이 파사드에서 <b>승인 전
	 * 작품이 보이는 유일한 자리</b>이므로 그 선이 실제로 갈리는 곳도 여기다.
	 *
	 * <p>키를 대신 내보내지도 않는다 — 그러면 한 필드가 다시 두 종류의 값을 나른다. 작성자는
	 * 원고 경로에서 중계로 본다 (§13-78).
	 */
	@Test
	void I8_a_cover_awaiting_review_is_not_signed() {
		UUID authorRef = UUID.randomUUID();
		UUID storyId = insertStory("user", "private", "pending", authorRef);
		setCover(storyId, COVER_KEY);

		MyStoryView mine = this.facade.mine(authorRef, null, null).stories().stream()
				.filter(story -> story.storyId().equals(storyId)).findFirst().orElseThrow();

		assertThat(mine.coverImage()).isNull();
	}

	/** 승인된 뒤에는 작성자의 목록에서도 열린다 — 같은 판정이 한 곳에 있다 (#378). */
	@Test
	void S13_79_my_stories_sign_an_approved_cover() {
		UUID authorRef = UUID.randomUUID();
		UUID storyId = insertStory("user", "public", "approved", authorRef);
		setCover(storyId, COVER_KEY);

		MyStoryView mine = this.facade.mine(authorRef, null, null).stories().stream()
				.filter(story -> story.storyId().equals(storyId)).findFirst().orElseThrow();

		assertThat(mine.coverImage()).startsWith("http").contains("X-Amz-Signature");
	}

	/**
	 * <b>이어하기 카드도 같은 값을 받는다</b> (#378).
	 *
	 * <p>세션 항목의 커버는 이 조회에서 온다 — 목록만 고치고 여기를 두면 <b>같은 작품이 화면에
	 * 따라 깨진다.</b>
	 */
	@Test
	void S13_79_the_continue_card_signs_an_approved_cover() {
		UUID storyId = insertStory("user", "public", "approved");
		setCover(storyId, COVER_KEY);
		UUID versionId = insertVersion(storyId);

		assertThat(this.facade.briefs(List.of(versionId)).get(versionId).coverImage())
				.startsWith("http").contains("X-Amz-Signature");
	}

	/**
	 * <b>#378 · §13-79 — 인물 초상도 같은 판정을 지난다.</b>
	 *
	 * <p>초상은 커버와 <b>같은 버킷에 같은 방식으로</b> 올라가고 (§13-65) 같은 컬럼 관행을
	 * 물려받았다 — 커버만 고치면 <b>같은 작품의 커버는 뜨고 인물만 깨진</b> 상태가 남으며,
	 * 그것은 "승인 후 이미지는 서명해서 낸다"가 반쪽만 참인 상태다.
	 */
	@Test
	void S13_79_an_approved_user_portrait_comes_back_signed() {
		UUID storyId = insertStory("user", "public", "approved");
		UUID versionId = insertVersion(storyId);
		setCurrentVersion(storyId, versionId);
		insertCharacter(storyId, versionId, PORTRAIT_KEY);

		CharacterCardView character = this.facade.detail(storyId).orElseThrow().characters()
				.getFirst();

		assertThat(character.portraitImage()).isNotEqualTo(PORTRAIT_KEY).startsWith("http")
				.contains("X-Amz-Signature").contains("X-Amz-Expires=900");
	}

	/**
	 * <b>공식 작품의 초상은 건드리지 않는다</b> (#378).
	 *
	 * <p>커버와 같은 이유다 — 그 값은 이미 <b>실제 주소</b>이며, 서명하려 들면 주소를 객체 키로
	 * 착각한 서명이 나가고 <b>지금 멀쩡한 초상이 깨진다.</b> 무엇이 UGC 인지는
	 * {@code author_type} 이 안다.
	 */
	@Test
	void S13_79_an_official_portrait_is_passed_through() {
		UUID storyId = insertStory("official", "public", "approved", null);
		UUID versionId = insertVersion(storyId);
		setCurrentVersion(storyId, versionId);
		insertCharacter(storyId, versionId, OFFICIAL_PORTRAIT_URL);

		assertThat(this.facade.detail(storyId).orElseThrow().characters().getFirst().portraitImage())
				.isEqualTo(OFFICIAL_PORTRAIT_URL);
	}

	/** 상세에 보이는 인물 하나. <b>초상 값은 가상의 문자열이다</b> (S-11). */
	private void insertCharacter(UUID storyId, UUID versionId, String portraitUrl) {
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO character (id, story_version_id, story_id, name, role,
								portrait_url, one_line, persona_prompt, display_order,
								is_visible_in_detail)
						VALUES (?, ?, ?, '유나', '친구', ?, '한 줄', '페르소나', 1, TRUE)
						""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, versionId);
			statement.setObject(3, storyId);
			statement.setString(4, portraitUrl);
			statement.executeUpdate();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** 상세는 {@code current_version_id} 를 기준으로 읽는다 (R2.1) — 그 자리를 실제 버전으로 맞춘다. */
	private void setCurrentVersion(UUID storyId, UUID versionId) {
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection
						.prepareStatement("UPDATE story SET current_version_id = ? WHERE id = ?")) {
			statement.setObject(1, versionId);
			statement.setObject(2, storyId);
			statement.executeUpdate();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** 커버 키를 그 작품에 붙인다. <b>가상의 문자열이다</b> (S-11). */
	private void setCover(UUID storyId, String coverUrl) {
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection
						.prepareStatement("UPDATE story SET cover_url = ? WHERE id = ?")) {
			statement.setString(1, coverUrl);
			statement.setObject(2, storyId);
			statement.executeUpdate();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** 이어하기 카드가 매달리는 버전 하나 (I-4). 세션은 이 버전에 고정된다. */
	private UUID insertVersion(UUID storyId) {
		UUID versionId = UUID.randomUUID();
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO story_version (id, story_id, version_no, world_prompt,
								choice_policy, state_schema, state_template_key, published_at)
						VALUES (?, ?, 1, '세계관', '{}'::jsonb, '{}'::jsonb, 'flag', ?)
						""")) {
			statement.setObject(1, versionId);
			statement.setObject(2, storyId);
			statement.setTimestamp(3, java.sql.Timestamp.from(PUBLISHED));
			statement.executeUpdate();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
		this.createdVersions.add(versionId);
		return versionId;
	}

	private UUID insertStory(String authorType, String visibility, String reviewStatus) {
		return insertStory(authorType, visibility, reviewStatus, UUID.randomUUID());
	}

	private UUID insertStory(String authorType, String visibility, String reviewStatus, UUID authorRef) {
		UUID id = UUID.randomUUID();
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO story (id, slug, title, short_desc, author_type, author_ref, visibility,
						                   review_status, current_version_id, published_at, created_at)
						VALUES (?, ?, '테스트 작품', '한 줄 소개', ?, ?, ?, ?, ?, ?, ?)
						""")) {
			statement.setObject(1, id);
			statement.setString(2, "test-" + id);
			statement.setString(3, authorType);
			statement.setObject(4, authorRef);
			statement.setString(5, visibility);
			statement.setString(6, reviewStatus);
			statement.setObject(7, UUID.fromString("11111111-1111-4111-8111-111111111111"));
			statement.setTimestamp(8, java.sql.Timestamp.from(PUBLISHED));
			statement.setTimestamp(9, java.sql.Timestamp.from(PUBLISHED));
			statement.executeUpdate();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
		this.created.add(id);
		return id;
	}
}
