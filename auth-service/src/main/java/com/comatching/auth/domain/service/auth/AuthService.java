package com.comatching.auth.domain.service.auth;

import com.comatching.auth.domain.dto.TokenResponse;

public interface AuthService {

	TokenResponse reissue(String refreshToken);

	void logout(String refreshToken);
}
