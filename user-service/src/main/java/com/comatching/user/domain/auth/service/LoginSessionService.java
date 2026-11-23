package com.comatching.user.domain.auth.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.auth.LoginSession;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.util.JwtUtil;
import com.comatching.user.domain.auth.dto.TokenResponse;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.service.MemberService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LoginSessionService {
	private final MemberService members;
	private final LoginSessionStore store;
	private final JwtUtil jwt;

	@Transactional
	public TokenResponse login(long memberId, long authenticatedVersion) {
		LoginSession session = new LoginSession(memberId, authenticatedVersion, UUID.randomUUID());
		Member member = lockActiveMember(session);
		UUID tokenId = UUID.randomUUID();
		TokenResponse tokens = issue(member, session, tokenId);
		store.start(session, tokenId.toString());
		return tokens;
	}

	@Transactional
	public TokenResponse reissue(String refreshToken) {
		var previous = jwt.readRefresh(refreshToken);
		LoginSession session = previous.session();
		Member member = lockActiveMember(session);
		UUID tokenId = UUID.randomUUID();
		TokenResponse tokens = issue(member, session, tokenId);
		store.rotate(session, previous.claims().getId(), tokenId.toString());
		return tokens;
	}

	TokenResponse upgrade(Member member, LoginSession session, String nickname) {
		UUID tokenId = UUID.randomUUID();
		TokenResponse tokens = issue(member, session, tokenId, nickname);
		store.upgrade(session, tokenId.toString());
		return tokens;
	}

	public void logout(String accessToken, String refreshToken) {
		LoginSession session = sessionIfValid(accessToken, true);
		if (session == null) session = sessionIfValid(refreshToken, false);
		if (session != null) store.end(session);
	}

	private LoginSession sessionIfValid(String token, boolean access) {
		if (token == null || token.isBlank()) return null;
		try {
			return (access ? jwt.readAccess(token) : jwt.readRefresh(token)).session();
		} catch (BusinessException invalidToken) {
			return null;
		}
	}

	Member lockActiveMember(LoginSession session) {
		Member member = members.getMemberByIdForUpdate(session.memberId());
		if (member.getAuthVersion() != session.authVersion()
			|| (member.getStatus() != MemberStatus.ACTIVE && member.getStatus() != MemberStatus.PENDING)) {
			throw new BusinessException(GeneralErrorCode.UNAUTHORIZED);
		}
		return member;
	}

	private TokenResponse issue(Member member, LoginSession session, UUID refreshId) {
		return issue(member, session, refreshId, member.getProfile() == null ? null : member.getProfile().getNickname());
	}

	private TokenResponse issue(Member member, LoginSession session, UUID refreshId, String nickname) {
		return new TokenResponse(jwt.createAccessToken(session, member.getEmail(), member.getRole(), member.getStatus(), nickname),
			jwt.createRefreshToken(session, refreshId));
	}
}
