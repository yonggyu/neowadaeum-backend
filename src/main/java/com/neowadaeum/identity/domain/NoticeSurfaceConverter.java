package com.neowadaeum.identity.domain;

import com.neowadaeum.common.spi.NoticeSurface;
import jakarta.persistence.Converter;

/** {@link NoticeSurface} ↔ {@code ai_notice_impression.surface}. */
@Converter(autoApply = true)
public class NoticeSurfaceConverter extends LowerCaseEnumConverter<NoticeSurface> {

	public NoticeSurfaceConverter() {
		super(NoticeSurface.class);
	}
}
