package com.comatching.user.domain.auth.service;

import com.comatching.user.domain.auth.dto.ChangePasswordRequest;
import com.comatching.user.domain.auth.dto.ResetPasswordRequest;
import com.comatching.user.domain.auth.dto.TokenResponse;

public interface AuthService {

	TokenResponse reissue(String refreshToken);

	void logout(String refreshToken);

	void resetPassword(ResetPasswordRequest request);

	void changePassword(Long memberId, ChangePasswordRequest request);

	void withdraw(Long memberId);
}
