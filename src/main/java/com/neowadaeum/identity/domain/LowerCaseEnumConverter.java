package com.neowadaeum.identity.domain;

import jakarta.persistence.AttributeConverter;
import java.util.Locale;

/**
 * enum ↔ DB 소문자 표기. identity 의 값 컬럼들이 같은 규칙을 쓴다.
 *
 * <p><b>{@code @Enumerated(STRING)} 을 쓰지 않는 이유</b>는 그것이 {@code ACTIVE} 를 저장하기
 * 때문이다. §2.2 원문과 마이그레이션의 CHECK 제약은 {@code active} 다. 구현이 둘 이상이라
 * 공통 부모를 뒀다 — 하나였다면 두지 않았다.
 *
 * <p><b>모르는 값을 기본값으로 흡수하지 않는다.</b> 여기서 예외가 나면 마이그레이션과 enum 이
 * 어긋났다는 뜻이고 그 사실이 드러나야 한다.
 */
abstract class LowerCaseEnumConverter<E extends Enum<E>> implements AttributeConverter<E, String> {

	private final Class<E> type;

	protected LowerCaseEnumConverter(Class<E> type) {
		this.type = type;
	}

	@Override
	public String convertToDatabaseColumn(E attribute) {
		return (attribute != null) ? attribute.name().toLowerCase(Locale.ROOT) : null;
	}

	@Override
	public E convertToEntityAttribute(String dbData) {
		if (dbData == null) {
			return null;
		}
		try {
			return Enum.valueOf(this.type, dbData.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException(
					"알 수 없는 %s 값이다: %s".formatted(this.type.getSimpleName(), dbData), ex);
		}
	}
}
