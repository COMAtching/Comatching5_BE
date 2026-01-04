package com.comatching.common.util;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

class SecurityUtilTest {

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void getCurrentMemberId_Success() {
		//given
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(new UsernamePasswordAuthenticationToken("100", null));
		SecurityContextHolder.setContext(context);

		//when
		Long memberId = SecurityUtil.getCurrentMemberId();

		//then
		assertThat(memberId).isEqualTo(100L);
	}

	@Test
	void getCurrentMemberId_Fail() {
		assertThatThrownBy(SecurityUtil::getCurrentMemberId)
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining("인증 정보가 존재하지 않습니다")
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(GeneralErrorCode.UNAUTHORIZED);
	}
}