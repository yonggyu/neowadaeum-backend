package com.neowadaeum.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.AuthorDisplayNameWriter;
import com.neowadaeum.common.spi.InvalidDisplayNameException;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * #271 — <b>표시명을 쓰는 경로가 지키는 것</b> (§13-7, I-3).
 *
 * <p>여기서 못박는 것은 <b>경계</b>다: 무엇이 catalog 로 넘어가는가, 도메인의 거절이 어떤 응답이
 * 되는가, 누가 이름을 바꿀 수 없는가. 표시명 규칙 자체는 {@code DisplayNames} 의 몫이며 여기서
 * 다시 세우지 않는다 — 정본이 둘이 되면 갈라진다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class MyAccountUpdateServiceTests {

	private final UserRepository users = mock(UserRepository.class);

	private final AuthorDisplayNameWriter displayNames = mock(AuthorDisplayNameWriter.class);

	private final MyAccountUpdateService service = new MyAccountUpdateService(this.users, this.displayNames);

	private final UUID playerRef = UUID.randomUUID();

	/**
	 * <b>저장된 값을 돌려준다</b> (#271).
	 *
	 * <p>정규화 때문에 <b>보낸 값과 저장된 값이 다를 수 있다.</b> 서비스가 입력을 그대로 되돌려
	 * 주면 화면은 자기가 보낸 값을 저장된 이름이라고 믿는다.
	 */
	@Test
	void Issue271_the_stored_name_is_returned_not_the_one_that_was_sent() {
		givenActiveMember();
		given(this.displayNames.updateDisplayName(this.playerRef, "  달빛  서점 ")).willReturn("달빛 서점");

		MyAccount account = this.service.updateDisplayName(this.playerRef, "  달빛  서점 ");

		assertThat(account.displayName()).isEqualTo("달빛 서점");
	}

	/**
	 * <b>{@code playerRef} 만 경계를 넘는다</b> (I-3).
	 *
	 * <p>catalog 가 받는 인자는 둘뿐이며 그중 식별자는 {@code playerRef} 하나다. {@code user.id} ·
	 * 생년월일 · 역할은 <b>넘길 자리가 없다</b> — 이 단언이 그 자리가 생기는 것을 막는다.
	 */
	@Test
	void I3_only_the_player_ref_crosses_into_catalog() {
		givenActiveMember();
		given(this.displayNames.updateDisplayName(any(), anyString())).willReturn("달빛서점");

		this.service.updateDisplayName(this.playerRef, "달빛서점");

		ArgumentCaptor<UUID> crossed = ArgumentCaptor.forClass(UUID.class);
		then(this.displayNames).should().updateDisplayName(crossed.capture(), anyString());
		assertThat(crossed.getValue()).isEqualTo(this.playerRef);

		// 넘길 자리 자체가 없다는 것이 요점이다 — 인자가 늘면 여기서 먼저 걸린다.
		assertThat(AuthorDisplayNameWriter.class.getDeclaredMethods())
				.allSatisfy(method -> assertThat(method.getParameterTypes())
						.containsExactly(UUID.class, String.class));
	}

	/**
	 * <b>규칙 위반은 400 이다</b> (§13-7, #287).
	 *
	 * <p>판정은 catalog 도메인이 하고 HTTP 로의 사상은 요청을 받은 쪽이 한다. 이것이 없으면
	 * 사용자가 잘못 쓴 이름이 <b>500</b> 이 된다 — 폴백 핸들러가 잡기 때문이다.
	 */
	@Test
	void S13_7_a_rejected_name_becomes_a_validation_error() {
		givenActiveMember();
		given(this.displayNames.updateDisplayName(any(), anyString()))
				.willThrow(new InvalidDisplayNameException("displayName is reserved", null));

		assertThatThrownBy(() -> this.service.updateDisplayName(this.playerRef, "탈퇴한 사용자"))
				.isInstanceOf(ApiException.class)
				.extracting(failure -> ((ApiException) failure).errorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR);
	}

	/**
	 * <b>거절된 입력이 응답으로 되돌아가지 않는다</b> (S-3).
	 *
	 * <p>사용자가 쓴 값이 그대로 실리면 그 값은 클라이언트 로그와 에러 리포트로 퍼진다.
	 * <b>"있어야 할 것"만 단언하면 값이 새어도 통과하므로</b> 없어야 할 것을 함께 단언한다.
	 */
	@Test
	void SEC3_the_rejected_input_is_not_echoed_back() {
		givenActiveMember();
		given(this.displayNames.updateDisplayName(any(), anyString()))
				.willThrow(new InvalidDisplayNameException("displayName has characters that are not allowed", null));

		ApiException failure = catchApiException("나쁜🙂이름");

		assertThat(failure.details().toString())
				.contains("displayName")
				.doesNotContain("나쁜🙂이름");
	}

	/**
	 * <b>탈퇴한 계정은 이름을 바꾸지 못한다</b> (R12.5).
	 *
	 * <p>액세스 토큰은 탈퇴 후에도 만료 전까지 살아 있다. 막지 않으면 파기 배치가 익명으로
	 * 바꿔 둔 이름을 <b>탈퇴한 계정이 도로 되돌린다.</b>
	 */
	@Test
	void R12_5_a_withdrawn_account_cannot_rename_itself() {
		User user = givenActiveMember();
		user.withdraw();

		assertThat(catchApiException("달빛서점").errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
		then(this.displayNames).should(never()).updateDisplayName(any(), anyString());
	}

	/** 매핑이 파기된 토큰에 답할 회원이 없다 (R12.5) — {@code MyAccountQueryService} 와 같은 규칙이다. */
	@Test
	void R12_5_a_purged_mapping_is_unauthenticated() {
		given(this.users.findByPlayerRef(this.playerRef)).willReturn(Optional.empty());

		assertThat(catchApiException("달빛서점").errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
	}

	private ApiException catchApiException(String displayName) {
		try {
			this.service.updateDisplayName(this.playerRef, displayName);
		}
		catch (ApiException ex) {
			return ex;
		}
		throw new AssertionError("ApiException 이 나오지 않았다");
	}

	private User givenActiveMember() {
		User user = User.register(this.playerRef, LocalDate.of(2000, 1, 1), Instant.now());
		given(this.users.findByPlayerRef(this.playerRef)).willReturn(Optional.of(user));
		return user;
	}
}
