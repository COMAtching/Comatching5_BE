package com.comatching.user.domain.auth.service;

import com.comatching.common.operations.Feature;
import com.comatching.common.operations.FeatureGate;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.auth.LoginSession;
import com.comatching.common.domain.enums.MemberRole;
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
	private final FeatureGate features;
	private final MemberService members;
	private final LoginSessionStore store;
	private final JwtUtil jwt;

	@Transactional
	public TokenResponse login(long memberId, long authenticatedVersion) {
		return login(memberId, authenticatedVersion, null);
	}

	@Transactional
	public TokenResponse login(long memberId, long authenticatedVersion, UUID installation) {
		return login(memberId, authenticatedVersion, installation, null, null);
	}

	@Transactional
	public TokenResponse login(long memberId, long authenticatedVersion, UUID installation, String oldAccess, String oldRefresh) {
		LoginSession session = new LoginSession(memberId, authenticatedVersion, UUID.randomUUID());
		Member member = lockActiveMember(session);
		if (member.getRole() != MemberRole.ROLE_ADMIN) features.requireOpen(Feature.LOGIN);
		UUID tokenId = UUID.randomUUID();
		TokenResponse tokens = issue(member, session, tokenId);
		store.start(session, tokenId.toString());
		// Signed login cookies also identify pre-migration sessions which have no installation yet.
		logout(oldAccess, oldRefresh, null);
		if (installation != null) store.claimInstallation(installation, session, true);
		return tokens;
	}

	public UUID prepareInstallation(UUID installation, LoginSession session) {
		return store.claimInstallation(installation, session, false);
	}

	@Transactional
	public TokenResponse reissue(String refreshToken) {
		var previous = jwt.readRefresh(refreshToken);
		LoginSession session = previous.session();
		Member member = lockActiveMember(session);
		if (member.getRole() != MemberRole.ROLE_ADMIN) features.requireOpen(Feature.LOGIN);
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

	public boolean logout(String accessToken, String refreshToken, String expected) {
		LoginSession session = sessionIfValid(accessToken, true);
		if (session == null) session = sessionIfValid(refreshToken, false);
		if (expected != null && (session == null || !expected.equals(session.clientMarker()))) {
			throw new BusinessException(GeneralErrorCode.FORBIDDEN);
		}
		return session != null && store.end(session);
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
