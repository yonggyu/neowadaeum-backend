package com.neowadaeum.identity.domain;

import jakarta.persistence.Converter;

/** {@link UserStatus} ↔ {@code user.status}. */
@Converter(autoApply = true)
public class UserStatusConverter extends LowerCaseEnumConverter<UserStatus> {

	public UserStatusConverter() {
		super(UserStatus.class);
	}
}
