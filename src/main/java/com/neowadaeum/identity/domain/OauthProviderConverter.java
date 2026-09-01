package com.neowadaeum.identity.domain;

import jakarta.persistence.Converter;

/** {@link OauthProvider} ↔ {@code oauth_identity.provider}. */
@Converter(autoApply = true)
public class OauthProviderConverter extends LowerCaseEnumConverter<OauthProvider> {

	public OauthProviderConverter() {
		super(OauthProvider.class);
	}
}
