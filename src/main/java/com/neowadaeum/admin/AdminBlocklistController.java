package com.neowadaeum.admin;

import com.neowadaeum.authoring.blocklist.BlocklistAdminService;
import com.neowadaeum.authoring.blocklist.BlocklistEntryRow;
import com.neowadaeum.common.web.PlayerRefResolver;
import com.neowadaeum.identity.access.AdminAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 블록리스트 관리 (§14, B-49, R9.4).
 *
 * <p><b>등록한 값을 응답에 그대로 돌려주지 않는다.</b> 목록 조회는 관리자가 무엇이 등록돼
 * 있는지 보는 자리이므로 값을 보여 주지만, 그 응답은 <b>세 조건 뒤에</b> 있다 (S-4) — 그리고
 * 그 목록이 곧 우회 사전이므로 그 밖으로는 나가지 않는다 (S-11).
 *
 * <p><b>감사에 값을 싣지 않는다.</b> 남는 것은 "누가 언제 무엇을 등록했나"의 <b>id 와 종류</b>
 * 까지다 — 값까지 남기면 감사 로그가 그 사전이 된다 (S-3, S-11).
 */
@RestController
@RequestMapping("/api/v1/admin/blocklist")
public class AdminBlocklistController {

	private final BlocklistAdminService blocklist;

	private final AdminAccessGuard guard;

	private final PlayerRefResolver playerRefs;

	public AdminBlocklistController(BlocklistAdminService blocklist, AdminAccessGuard guard,
			PlayerRefResolver playerRefs) {
		this.blocklist = blocklist;
		this.guard = guard;
		this.playerRefs = playerRefs;
	}

	/** 등록한다. <b>정규화는 서버가 한다</b> (R2.5) — 요청은 사람이 읽는 값만 나른다. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public BlocklistEntryResponse register(@Valid @RequestBody BlocklistRegisterRequest body,
			HttpServletRequest request) {
		UUID adminUserId = this.guard.requireAdmin(this.playerRefs.currentPlayerRef(), request);

		UUID id = this.blocklist.register(body.kind(), body.value(), body.severity(), body.source());
		this.guard.recordAction(adminUserId, "admin.blocklist.register", "blocklist", id,
				Map.of("kind", body.kind().columnValue(), "severity", body.severity().columnValue()),
				request);
		return new BlocklistEntryResponse(id, body.kind().columnValue(), body.value(),
				body.severity().columnValue(), body.source());
	}

	/** 무엇이 등록돼 있는가. 이 응답 자체가 <b>세 조건 뒤에</b> 있다 (S-4, S-11). */
	@GetMapping
	public List<BlocklistEntryResponse> list(HttpServletRequest request) {
		this.guard.requireAdmin(this.playerRefs.currentPlayerRef(), request);

		return this.blocklist.list().stream().map(AdminBlocklistController::responseOf).toList();
	}

	/** 지운다. <b>없어도 성공이다</b> — 삭제는 상태를 맞추는 요청이다. */
	@DeleteMapping("/{entryId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@PathVariable UUID entryId, HttpServletRequest request) {
		UUID adminUserId = this.guard.requireAdmin(this.playerRefs.currentPlayerRef(), request);

		this.blocklist.remove(entryId);
		this.guard.recordAction(adminUserId, "admin.blocklist.remove", "blocklist", entryId, Map.of(),
				request);
	}

	private static BlocklistEntryResponse responseOf(BlocklistEntryRow row) {
		return new BlocklistEntryResponse(row.getId(), row.getKind().columnValue(), row.getValue(),
				row.getSeverity().columnValue(), row.getSource());
	}
}
