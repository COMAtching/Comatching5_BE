package com.comatching.user.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.comatching.common.exception.BusinessException;
import com.comatching.user.domain.auth.dto.ChangePasswordRequest;
import com.comatching.user.domain.auth.dto.ResetPasswordRequest;
import com.comatching.user.domain.mail.service.EmailService;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.service.MemberService;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {
	@InjectMocks AuthServiceImpl auth;
	@Mock MemberService members;
	@Mock EmailService email;
	@Mock PasswordEncoder passwords;

	@Test
	void changeChecksTheCurrentPasswordUnderTheSameLockAsRevocation() {
		Member member = Member.builder().email("a@example.invalid").password("hash").build();
		given(members.getMemberByIdForUpdate(42L)).willReturn(member);
		var request = new ChangePasswordRequest("current", "next");
		assertThatThrownBy(() -> auth.changePassword(42L, request)).isInstanceOf(BusinessException.class);
		then(members).should(never()).updatePassword(anyString(), anyString());
		given(passwords.matches("current", "hash")).willReturn(true);
		given(passwords.encode("next")).willReturn("new-hash");
		auth.changePassword(42L, request);
		then(members).should().updatePassword("a@example.invalid", "new-hash");
	}

	@Test
	void resetUsesTheSameAccountWidePasswordUpdate() {
		given(passwords.encode("next")).willReturn("new-hash");
		auth.resetPassword(new ResetPasswordRequest("a@example.invalid", "code", "next"));
		then(email).should().verifyPasswordResetCode("a@example.invalid", "code");
		then(members).should().updatePassword("a@example.invalid", "new-hash");
	}
}
