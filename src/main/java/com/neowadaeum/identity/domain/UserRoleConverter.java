package com.neowadaeum.identity.domain;

import jakarta.persistence.Converter;

/** {@link UserRole} ↔ {@code user.role}. */
@Converter(autoApply = true)
public class UserRoleConverter extends LowerCaseEnumConverter<UserRole> {

	public UserRoleConverter() {
		super(UserRole.class);
	}
}
