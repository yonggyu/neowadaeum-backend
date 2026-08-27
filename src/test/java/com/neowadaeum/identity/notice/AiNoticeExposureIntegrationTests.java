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
		this.configs.deleteAll();
	}

	@AfterEach
	void clear() {
		clearIdentity();
		this.configs.deleteAll();
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
	 * <b>문구가 설정되지 않았어도 플레이는 된다.</b>
	 *
	 * <p>기록은 최선 노력이다 — 고지 이력 하나 때문에 서비스가 멈추면 관측을 떼게 된다.
	 * 대신 그 상태는 로그에 남고 B-48 의 관측 대상이다.
	 */
	@Test
	void R11_1_an_unconfigured_notice_does_not_break_play() throws Exception {
		this.users.save(User.register(TEST_PLAYER_REF, null, NOW));

		startSession();

		assertThat(this.impressions.count()).isZero();
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
