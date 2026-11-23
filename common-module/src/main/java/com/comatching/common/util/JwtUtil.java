package com.comatching.common.util;

import java.security.Key;
import java.util.Date;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.comatching.common.auth.LoginSession;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtUtil {
	private final Key key;
	private final long accessTokenValidity;
	private final long refreshTokenValidity;

	public JwtUtil(@Value("${jwt.secret}") String secret,
		@Value("${jwt.access-token.expiration}") long accessTokenValidity,
		@Value("${jwt.refresh-token.expiration}") long refreshTokenValidity) {
		this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
		this.accessTokenValidity = accessTokenValidity;
		this.refreshTokenValidity = refreshTokenValidity;
	}

	public String createAccessToken(LoginSession session, String email, MemberRole role, MemberStatus status, String nickname) {
		return builder(session, "access", accessTokenValidity, UUID.randomUUID())
			.claim("email", email).claim("role", role.name()).claim("status", status.name())
			.claim("nickname", nickname).compact();
	}

	public String createRefreshToken(LoginSession session, UUID tokenId) {
		return builder(session, "refresh", refreshTokenValidity, tokenId).compact();
	}

	private JwtBuilder builder(LoginSession session, String use, long validity, UUID tokenId) {
		Date now = new Date();
		return Jwts.builder().setSubject(Long.toString(session.memberId())).setId(tokenId.toString())
			.setIssuedAt(now).setExpiration(new Date(now.getTime() + validity))
			.claim("token_use", use).claim("sid", session.id().toString()).claim("auth_version", session.authVersion())
			.signWith(key, SignatureAlgorithm.HS256);
	}

	public record VerifiedToken(LoginSession session, Claims claims) {}

	public VerifiedToken readAccess(String token) {
		return verify(token, "access");
	}

	public VerifiedToken readRefresh(String token) {
		return verify(token, "refresh");
	}

	private VerifiedToken verify(String token, String use) {
		try {
			Claims claims = Jwts.parserBuilder().setSigningKey(key).require("token_use", use).build()
				.parseClaimsJws(token).getBody();
			Long version = claims.get("auth_version", Long.class);
			if (version == null || claims.getExpiration() == null || claims.getId() == null || claims.get("sid") == null) {
				throw new IllegalArgumentException("Missing session claims");
			}
			LoginSession session = new LoginSession(Long.parseLong(claims.getSubject()), version,
				UUID.fromString(claims.get("sid", String.class)));
			return new VerifiedToken(session, claims);
		} catch (JwtException | IllegalArgumentException e) {
			throw new BusinessException(GeneralErrorCode.UNAUTHORIZED);
		}
	}
}
