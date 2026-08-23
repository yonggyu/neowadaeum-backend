package com.neowadaeum.play.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@link SessionStatus} ↔ DB 소문자 표기.
 *
 * <p>{@code @Enumerated(STRING)} 을 쓰지 않는 이유는 그것이 {@code ACTIVE} 를 저장하기 때문이다.
 * §13-6 과 마이그레이션의 CHECK 제약은 {@code active} 다. 한쪽을 대문자로 맞추면 API 표기까지
 * 끌려가므로, 변환을 한 곳에 두고 양쪽 표기를 각자 유지한다.
 */
@Converter(autoApply = true)
public class SessionStatusConverter implements AttributeConverter<SessionStatus, String> {

	@Override
	public String convertToDatabaseColumn(SessionStatus attribute) {
		return (attribute != null) ? attribute.dbValue() : null;
	}

	@Override
	public SessionStatus convertToEntityAttribute(String dbData) {
		return (dbData != null) ? SessionStatus.from(dbData) : null;
	}
}
