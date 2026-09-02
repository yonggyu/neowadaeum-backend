package com.neowadaeum.identity.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.catalog.domain.ServiceConfig;
import com.neowadaeum.catalog.repository.ServiceConfigRepository;
import com.neowadaeum.common.spi.NoticeSurface;
import com.neowadaeum.identity.domain.AiNoticeImpression;
import com.neowadaeum.identity.domain.ConsentType;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.repository.AiNoticeImpressionRepository;
import com.neowadaeum.identity.repository.ConsentLogRepository;
import com.neowadaeum.identity.repository.OauthIdentityRepository;
import com.neowadaeum.identity.repository.UserRepository;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * B-14 — <b>플레이를 시작하면 고지 노출이 남는다</b> (R11.3, §11).
 *
 * <p>§11 은 노출 이력을 <b>입증 책임 대비</b>로 요구한다. 보여 줬다는 주장과 보여 준 기록은 다르며,
 * 여기서 확인하는 것은 <b>기록이 실제로 생기는가</b>다.
 *
 * <p>세 스토어가 한 흐름에 걸린다 — 문구는 catalog, 회원과 이력은 identity, 시작은 play 다.
 * <b>어느 모듈도 다른 모듈의 저장소를 직접 보지 않는다</b>: play 는 {@code playerRef} 만 넘긴다 (I-3).
 */
class AiNoticeExposureIntegrationTests extends ContainerTestBase {

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final Instant NOW = Instant.parse("2026-08-27T04:05:06Z");

	@Autowired
	private org.springframework.test.web.servlet.MockMvc mockMvc;

	@Autowired
	private ServiceConfigRepository configs;

	@Autowired
	private UserRepository users;

	@Autowired
	private ConsentLogRepository consents;

	@Autowired
	private OauthIdentityRepository oauthIdentities;

	@Autowired
	private AiNoticeImpressionRepository impressions;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	@BeforeEach
	void reset() {
		this.snapshots.deleteAll();
		this.turns.deleteAll();
		this.sessions.deleteAll();
		clearIdentity();
		// 이 클래스는 "문구가 없는 상태" 를 직접 만든다. 베이스가 심은 키만 지운다 —
		// 표 전체를 비우면 다른 테스트가 원인 없이 깨진다 (이슈 #272).
		this.configs.deleteById(NOTICE_KEY);
	}

	@AfterEach
	void clear() {
		clearIdentity();
		this.configs.deleteById(NOTICE_KEY);
	}

	/**
	 * identity 를 비운다 — <b>FK 순서대로</b>.
	 *
	 * <p>{@code user} 를 먼저 지우면 {@code oauth_identity} 가 막는다. 그 FK 는 identity 안에만
	 * 있으므로 (§5.3) DB 가 순서를 강제하는 것이 정상이다.
	 */
	private void clearIdentity() {
		this.impressions.deleteAll();
		this.consents.deleteAll();
		this.oauthIdentities.deleteAll();
		this.users.deleteAll();
	}

	/** R11.3 — 세션을 시작하면 그 판본을 봤다는 사실이 남는다. */
	@Test
	void R11_3_starting_a_session_records_the_notice_exposure() throws Exception {
		givenConfiguredNotice("2026-07-21");
		User user = this.users.save(User.register(TEST_PLAYER_REF, null, NOW));

		startSession();

		assertThat(this.impressions.findAll()).singleElement().satisfies(impression -> {
			assertThat(impression.getUserId()).isEqualTo(user.getId());
			assertThat(impression.getNoticeVersion()).isEqualTo("2026-07-21");
			assertThat(impression.getSurface()).isEqualTo(NoticeSurface.PLAY);
		});
	}

	/**
	 * <b>§13-8 — 노출은 동의가 아니다.</b>
	 *
	 * <p>고지를 보여 준 사실이 {@code consent_log} 로 새면 동의 이력의 법적 증빙력이 흐려진다.
	 */
	@Test
	void S13_8_an_exposure_is_not_written_as_a_consent() throws Exception {
		givenConfiguredNotice("2026-07-21");
		this.users.save(User.register(TEST_PLAYER_REF, null, NOW));

		startSession();

		assertThat(this.impressions.count()).isEqualTo(1);
		assertThat(this.consents.count()).as("노출이 동의로 새면 안 된다").isZero();
	}

	/**
	 * <b>문구가 설정되지 않으면 플레이가 시작되지 않는다</b> (§13-27 개정, #281).
	 *
	 * <p><b>이 테스트는 반대를 단언하고 있었다.</b> B-14 시점의 §13-27 은 플레이 경로를 예외로
	 * 두었고 그 근거는 <b>거기서 고지가 하는 일이 기록뿐</b>이라는 것이었다 — "기록을 못 남기는
	 * 것과 고지를 안 보여 주는 것은 무게가 다르다".
	 *
	 * <p>#281 이 그 전제를 바꿨다. 턴 응답이 문구를 <b>싣게</b> 되면서 플레이 경로도 표시 경로가
	 * 됐고, 그러면 다른 화면과 같은 규칙을 받는다 — <b>문구 없이 내보내지 않는다.</b> 구분은
	 * 여전히 "기록이냐 표시냐"이며, 바뀐 것은 규칙이 아니라 <b>플레이가 어느 쪽인가</b>다.
	 *
	 * <p><b>대가를 적어 둔다</b> — 설정을 빠뜨리면 둘러보기만이 아니라 <b>플레이 전체가 멈춘다.</b>
	 * 그래서 배포 절차의 0번 단계가 그만큼 더 중요해졌다 (`docs/deployment.md`).
	 */
	@Test
	void R11_1_play_does_not_start_without_a_configured_notice() throws Exception {
		this.users.save(User.register(TEST_PLAYER_REF, null, NOW));

		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY).with(asPlayer()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(500));

		assertThat(this.impressions.count()).as("시작되지 않았으므로 노출도 남지 않는다").isZero();
	}

	/** 동의 타입이 늘지 않았는지도 함께 본다 — 이 흐름은 동의를 만들지 않는다. */
	@Test
	void S13_8_no_ai_notice_consent_is_created_by_playing() throws Exception {
		givenConfiguredNotice("2026-07-21");
		User user = this.users.save(User.register(TEST_PLAYER_REF, null, NOW));

		startSession();

		assertThat(this.consents.countByUserIdAndConsentType(user.getId(), ConsentType.AI_NOTICE)).isZero();
	}

	private void givenConfiguredNotice(String version) {
		this.configs.save(ServiceConfig.of("ai.notice",
				"{\"version\":\"%s\",\"text\":\"이 이야기는 AI가 생성합니다.\"}".formatted(version), NOW));
	}

	private void startSession() throws Exception {
		this.mockMvc.perform(post("/api/v1/stories/{storyId}/sessions", SEED_STORY).with(asPlayer()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));
	}
}
