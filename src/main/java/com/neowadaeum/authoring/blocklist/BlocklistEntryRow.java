package com.neowadaeum.authoring.blocklist;

import com.neowadaeum.common.support.TextNormalizer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 블록리스트 항목 (§2.4, R2.5).
 *
 * <p><b>정규화는 여기서 한다.</b> 부르는 쪽이 정규화된 값을 넘기게 하면, 정규화를 잊은 호출자가
 * 생기고 그 항목은 <b>영원히 걸리지 않는다</b> — 조회는 정규화끼리 비교하기 때문이다.
 *
 * <p><b>이름이 {@code ...Row} 인 것은 의도다.</b> {@code common/spi} 에 같은 이름의
 * {@code BlocklistEntry} 가 있고 그쪽이 <b>계약</b>이다. 저장 형태와 계약을 같은 이름으로 두면
 * 어느 것을 임포트했는지 매번 확인해야 한다.
 */
@Entity
@Table(name = "blocklist_entry")
public class BlocklistEntryRow {

	@Id
	@GeneratedValue
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "kind", nullable = false)
	private String kind;

	@Column(name = "value", nullable = false)
	private String value;

	@Column(name = "normalized_value", nullable = false)
	private String normalizedValue;

	@Column(name = "severity", nullable = false)
	private String severity;

	@Column(name = "source")
	private String source;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected BlocklistEntryRow() {
	}

	/**
	 * 새 항목.
	 *
	 * @param value 사람이 읽는 값. 관리 화면이 보여 준다
	 * @param source 어디서 왔는가. 사후에 근거를 되짚는 데 쓴다
	 */
	public static BlocklistEntryRow of(BlocklistKind kind, String value, BlocklistSeverity severity,
			String source, Instant now) {
		BlocklistEntryRow row = new BlocklistEntryRow();
		row.kind = kind.columnValue();
		row.value = value;
		row.normalizedValue = TextNormalizer.normalize(value);
		row.severity = severity.columnValue();
		row.source = source;
		row.createdAt = now;
		row.updatedAt = now;
		return row;
	}

	public UUID getId() {
		return this.id;
	}

	public BlocklistKind getKind() {
		return BlocklistKind.valueOf(this.kind.toUpperCase(java.util.Locale.ROOT));
	}

	public String getValue() {
		return this.value;
	}

	public String getNormalizedValue() {
		return this.normalizedValue;
	}

	public BlocklistSeverity getSeverity() {
		return BlocklistSeverity.valueOf(this.severity.toUpperCase(java.util.Locale.ROOT));
	}

	public String getSource() {
		return this.source;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}
}
