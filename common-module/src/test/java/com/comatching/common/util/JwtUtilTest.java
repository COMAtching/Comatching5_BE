package com.comatching.common.util;

import static org.assertj.core.api.Assertions.*;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import com.comatching.common.auth.LoginSession;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.exception.BusinessException;

class JwtUtilTest {
	private final JwtUtil jwt = new JwtUtil(Base64.getEncoder().encodeToString(new byte[32]), 60000, 60000);
	private final LoginSession session = new LoginSession(42L, 3L, UUID.randomUUID());

	@Test
	void tokenPurposeSessionAndAccountVersionAreBoundAtIssue() {
		String access = jwt.createAccessToken(session, "member@example.invalid", MemberRole.ROLE_USER, MemberStatus.ACTIVE, null);
		UUID refreshId = UUID.randomUUID();
		String refresh = jwt.createRefreshToken(session, refreshId);
		assertThat(jwt.readAccess(access).session()).isEqualTo(session);
		assertThat(jwt.readAccess(access).claims().get("email")).isEqualTo("member@example.invalid");
		assertThat(jwt.readRefresh(refresh).claims().getId()).isEqualTo(refreshId.toString());
		assertThat(jwt.readRefresh(refresh).session()).isEqualTo(session);
		assertThatThrownBy(() -> jwt.readRefresh(access)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> jwt.readAccess(refresh)).isInstanceOf(BusinessException.class);
	}
}
