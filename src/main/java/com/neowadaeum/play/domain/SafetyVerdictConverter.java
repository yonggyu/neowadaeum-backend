package com.neowadaeum.play.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@link SafetyVerdict} ↔ DB 소문자 표기. {@link SessionStatusConverter} 와 같은 이유다 —
 * {@code @Enumerated(STRING)} 은 {@code PASS} 를 저장하지만 CHECK 제약은 {@code pass} 다.
 */
@Converter(autoApply = true)
public class SafetyVerdictConverter implements AttributeConverter<SafetyVerdict, String> {

	@Override
	public String convertToDatabaseColumn(SafetyVerdict attribute) {
		return (attribute != null) ? attribute.dbValue() : null;
	}

	@Override
	public SafetyVerdict convertToEntityAttribute(String dbData) {
		return (dbData != null) ? SafetyVerdict.from(dbData) : null;
	}
}
