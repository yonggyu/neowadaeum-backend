package com.neowadaeum.authoring.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.TestcontainersConfiguration;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.authoring.image.DraftImageStore;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * #315 · §13-65 — <b>이미지는 어떻게 올라오고, 올라온 뒤 무엇이 확인되는가.</b>
 *
 * <p>파일은 서버를 거치지 않는다. 그래서 이 경로가 지키는 것은 셋이다 — <b>키는 서버가 정하고</b>,
 * <b>남의 원고에는 발급되지 않으며</b>(I-8), <b>올라온 것을 서버가 확인한다</b>.
 *
 * <p><b>실제 저장소를 부르지 않는다.</b> 엔드포인트는 고정 응답 서버를 가리킨다
 * ({@code TestcontainersConfiguration}). 버킷명·자격증명은 테스트 전용 가짜다 (S-11).
 */
class DraftImageApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final UUID OTHER_PLAYER = UUID.fromString("00000000-0000-4000-8000-0000000000f1");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StoryDraftRepository drafts;

	@BeforeEach
	void resetStorage() {
		TestcontainersConfiguration.IMAGE_STORAGE.resetAll();
		TestcontainersConfiguration.IMAGE_STORAGE
				.stubFor(delete(anyUrl()).willReturn(aResponse().withStatus(204)));
	}

	@AfterEach
	void clearDrafts() {
		this.drafts.deleteAll();
	}

	/** <b>키를 서버가 정한다.</b> 클라이언트가 경로를 고르면 그것은 곧 남의 자리가 된다. */
	@Test
	void S13_65_the_server_chooses_the_key() throws Exception {
		UUID draftId = createDraft(ContainerTestBase.TEST_PLAYER_REF);

		this.mvc.perform(issue(draftId, "cover", "image/png", ContainerTestBase.TEST_PLAYER_REF))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.objectKey").value(org.hamcrest.Matchers
						.matchesPattern("drafts/" + draftId + "/cover/[0-9a-f-]{36}\\.png")))
				.andExpect(jsonPath("$.uploadMethod").value("PUT"))
				.andExpect(jsonPath("$.contentType").value("image/png"))
				.andExpect(jsonPath("$.maxBytes").value(DraftImageStore.MAX_BYTES));
	}

	/** <b>남의 원고는 없는 것과 구분되지 않는다</b> (I-8). 발급은 소유 판정을 지나야 한다. */
	@Test
	void I8_another_authors_draft_is_not_found() throws Exception {
		UUID draftId = createDraft(OTHER_PLAYER);

		this.mvc.perform(issue(draftId, "cover", "image/png", ContainerTestBase.TEST_PLAYER_REF))
				.andExpect(status().isNotFound());
	}

	/** 형식 목록은 서버가 갖는다. 목록 밖이면 서명 자체가 나가지 않는다. */
	@Test
	void S13_65_an_unlisted_format_is_refused() throws Exception {
		UUID draftId = createDraft(ContainerTestBase.TEST_PLAYER_REF);

		this.mvc.perform(issue(draftId, "cover", "image/gif", ContainerTestBase.TEST_PLAYER_REF))
				.andExpect(status().isBadRequest());
	}

	/**
	 * <b>확정은 이 원고의 키만 받는다.</b> 발급을 받았다는 사실이 아무 키나 확정할 수 있다는
	 * 뜻은 아니다 — 그것을 허용하면 남의 원고 이미지를 자기 것으로 끌어올 수 있다 (I-8).
	 */
	@Test
	void S13_65_a_key_of_another_draft_is_refused() throws Exception {
		UUID mine = createDraft(ContainerTestBase.TEST_PLAYER_REF);
		UUID theirs = UUID.randomUUID();

		this.mvc.perform(commit(mine, "drafts/" + theirs + "/cover/"
				+ UUID.randomUUID() + ".png", ContainerTestBase.TEST_PLAYER_REF))
				.andExpect(status().isBadRequest());
	}

	/** 크기와 형식은 <b>저장소가 말한 값</b>이다. 요청이 말한 값이 아니다. */
	@Test
	void S13_65_commit_reports_what_the_store_saw() throws Exception {
		UUID draftId = createDraft(ContainerTestBase.TEST_PLAYER_REF);
		String key = issuedKey(draftId);
		stubHead("image/png", 2048);

		this.mvc.perform(commit(draftId, key, ContainerTestBase.TEST_PLAYER_REF))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.objectKey").value(key))
				.andExpect(jsonPath("$.contentType").value("image/png"))
				.andExpect(jsonPath("$.sizeBytes").value(2048));
	}

	/**
	 * <b>5 MiB 상한이 실제로 걸린다.</b> presigned PUT 은 크기를 서명으로 묶지 못하므로, 확정이
	 * 확인하지 않으면 상한은 계약에만 적힌 숫자다.
	 */
	@Test
	void S13_65_an_oversize_upload_is_refused_at_commit() throws Exception {
		UUID draftId = createDraft(ContainerTestBase.TEST_PLAYER_REF);
		String key = issuedKey(draftId);
		stubHead("image/png", DraftImageStore.MAX_BYTES + 1);

		this.mvc.perform(commit(draftId, key, ContainerTestBase.TEST_PLAYER_REF))
				.andExpect(status().isBadRequest());
	}

	/**
	 * <b>확정 응답에 이미지 URL 이 없다</b> (I-8).
	 *
	 * <p>버킷은 비공개이고 이 PR 은 읽기 URL 을 하나도 발급하지 않는다 — 승인 전 UGC 가 URL
	 * 하나로 퍼지는 경로가 <b>존재하지 않는다</b>. 원고에 적히는 것은 키뿐이고, 키를 아는 것과
	 * 이미지를 보는 것은 다르다.
	 */
	@Test
	void I8_the_commit_response_carries_no_image_url() throws Exception {
		UUID draftId = createDraft(ContainerTestBase.TEST_PLAYER_REF);
		String key = issuedKey(draftId);
		stubHead("image/png", 2048);

		String body = this.mvc.perform(commit(draftId, key, ContainerTestBase.TEST_PLAYER_REF))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("http").doesNotContain("X-Amz");
	}

	private String issuedKey(UUID draftId) throws Exception {
		String body = this.mvc
				.perform(issue(draftId, "cover", "image/png", ContainerTestBase.TEST_PLAYER_REF))
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("objectKey").asString();
	}

	private void stubHead(String contentType, long length) {
		TestcontainersConfiguration.IMAGE_STORAGE.stubFor(head(anyUrl())
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", contentType)
						.withHeader("Content-Length", Long.toString(length))));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder issue(
			UUID draftId, String slot, String contentType, UUID player) {
		return post("/api/v1/authoring/drafts/{draftId}/images", draftId).with(asPlayer(player))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"slot\":\"%s\",\"contentType\":\"%s\"}".formatted(slot, contentType));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder commit(
			UUID draftId, String objectKey, UUID player) {
		return post("/api/v1/authoring/drafts/{draftId}/images/commit", draftId)
				.with(asPlayer(player)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"objectKey\":\"%s\"}".formatted(objectKey));
	}

	private UUID createDraft(UUID player) throws Exception {
		String body = this.mvc.perform(post("/api/v1/authoring/drafts").with(asPlayer(player)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JSON.readTree(body).get("draftId").asString());
	}
}
