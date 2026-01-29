package com.comatching.user.global.security;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.auth.MemberLoginDto;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UserPrincipal implements UserDetails {

	private final MemberLoginDto memberDto;

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {

		MemberStatus status = MemberStatus.valueOf(memberDto.status());

		if (status == MemberStatus.ACTIVE || status == MemberStatus.PENDING) {
			return Collections.singletonList(new SimpleGrantedAuthority(memberDto.role()));
		}

		return List.of(new SimpleGrantedAuthority("ROLE_NO_AUTH"));
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

	public String getNickname() {
		return memberDto.nickname();
	}

	@Override
	public boolean isAccountNonLocked() {
		MemberStatus status = MemberStatus.valueOf(memberDto.status());
		return status != MemberStatus.SUSPENDED && status != MemberStatus.BANNED;
	}

	@Override
	public boolean isEnabled() {
		MemberStatus status = MemberStatus.valueOf(memberDto.status());
		return status != MemberStatus.WITHDRAWN && status != MemberStatus.DORMANT;
	}

	@Override
	public boolean isAccountNonExpired() {
		return true;
	}

	@Override
	public boolean isCredentialsNonExpired() {
		return true;
	}
}
