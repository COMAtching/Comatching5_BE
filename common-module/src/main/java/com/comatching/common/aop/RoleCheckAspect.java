package com.comatching.common.aop;

import java.util.Arrays;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

@Aspect
@Component
public class RoleCheckAspect {

	@Before("@annotation(requireRole)")
	public void checkRole(JoinPoint joinPoint, RequireRole requireRole) {

		// 파라미터에서 MemberInfo 찾기
		MemberInfo memberInfo = Arrays.stream(joinPoint.getArgs())
			.filter(arg -> arg instanceof MemberInfo)
			.map(arg -> (MemberInfo) arg)
			.findFirst()
			.orElseThrow(() -> new BusinessException(GeneralErrorCode.UNAUTHORIZED));

		MemberRole currentRole = MemberRole.valueOf(memberInfo.role());

		// 권한 검사
		boolean hasAccess = Arrays.asList(requireRole.value()).contains(currentRole);

		if (!hasAccess) {
			throw new BusinessException(GeneralErrorCode.FORBIDDEN);
		}

	}
}
