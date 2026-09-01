package com.neowadaeum.identity.domain;

import jakarta.persistence.Converter;

/** {@link ConsentType} ↔ {@code consent_log.consent_type}. */
@Converter(autoApply = true)
public class ConsentTypeConverter extends LowerCaseEnumConverter<ConsentType> {

	public ConsentTypeConverter() {
		super(ConsentType.class);
	}
}
