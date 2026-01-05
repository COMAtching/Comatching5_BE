package com.comatching.auth.global.security;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.comatching.common.dto.auth.MemberLoginDto;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UserPrincipal implements UserDetails {

	private final MemberLoginDto memberDto;

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		if ("ACTIVE".equals(memberDto.status())) {
			return Collections.singletonList(new SimpleGrantedAuthority(memberDto.role()));
		} else if ("BANNED".equals(memberDto.status())) {
			return List.of(new SimpleGrantedAuthority("ROLE_BANNED"));
		}
		return List.of();
	}

	@Override
	public String getPassword() {
		return memberDto.password();
	}

	@Override
	public String getUsername() {
		return memberDto.email();
	}

	public Long getId() {
		return memberDto.id();
	}

	public String getStatus() {
		return memberDto.status();
	}

	public String getRole() {
		return memberDto.role();
	}

	@Override
	public boolean isAccountNonExpired() {
		return UserDetails.super.isAccountNonExpired();
	}

	@Override
	public boolean isAccountNonLocked() {
		return UserDetails.super.isAccountNonLocked();
	}

	@Override
	public boolean isCredentialsNonExpired() {
		return UserDetails.super.isCredentialsNonExpired();
	}

	@Override
	public boolean isEnabled() {
		return UserDetails.super.isEnabled();
	}
}
