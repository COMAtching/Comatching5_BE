package com.comatching.common.resolver;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.dto.member.MemberInfo;

import jakarta.servlet.http.HttpServletRequest;

public class MemberInfoArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CurrentMember.class)
			&& parameter.getParameterType().equals(MemberInfo.class);
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
		NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {

		HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();

		String memberIdHeader = request.getHeader("X-Member-Id");
		String email = request.getHeader("X-Member-Email");
		String role = request.getHeader("X-Member-Role");

		if (memberIdHeader == null) {
			throw new IllegalArgumentException("인증 헤더가 누락되었습니다.");
		}

		return new MemberInfo(Long.parseLong(memberIdHeader), email, role);
	}
}
