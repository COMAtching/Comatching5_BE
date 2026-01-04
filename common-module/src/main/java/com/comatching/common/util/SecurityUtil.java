package com.comatching.common.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

public class SecurityUtil {

	private SecurityUtil() {}

	/**
	 * 현재 로그인한 사용자의 ID(PK)
	 * 로그인하지 않은 상태라면 예외
	 */
	public static Long getCurrentMemberId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		// 1. 인증 정보가 아예 없는 경우
		if (authentication == null || authentication.getName() == null) {
			throw new BusinessException(GeneralErrorCode.UNAUTHORIZED, "인증 정보가 존재하지 않습니다.");
		}

		// 2. 익명 사용자 (로그인 안 함)
		if (authentication.getPrincipal().equals("anonymousUser")) {
			throw new BusinessException(GeneralErrorCode.UNAUTHORIZED, "로그인이 필요한 서비스입니다.");
		}

		// 3. ID 파싱 (Gateway가 헤더에 "1", "2" 처럼 ID를 넣어줬다고 가정)
		try {
			return Long.parseLong(authentication.getName());
		} catch (NumberFormatException e) {
			throw new BusinessException(GeneralErrorCode.INTERNAL_SERVER_ERROR, "인증 ID 형식이 올바르지 않습니다.");
		}
	}

	/**
	 * 로그인 여부만 확인
	 */
	public static boolean isAuthenticated() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null
			&& authentication.isAuthenticated()
			&& !authentication.getPrincipal().equals("anonymousUser");
	}
}
