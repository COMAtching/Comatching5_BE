package com.comatching.common.util;

import java.security.Key;
import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class JwtUtil {

	private final Key key;
	private final long accessTokenValidity;
	private final long refreshTokenValidity;

	public JwtUtil(
		@Value("${jwt.secret}") String secret,
		@Value("${jwt.access-token.expiration}") long accessTokenValidity,
		@Value("${jwt.refresh-token.expiration}") long refreshTokenValidity) {

		byte[] keyBytes = Decoders.BASE64.decode(secret);
		this.key = Keys.hmacShaKeyFor(keyBytes);

		this.accessTokenValidity = accessTokenValidity;
		this.refreshTokenValidity = refreshTokenValidity;
	}

	// Access Token 생성
	public String createAccessToken(Long memberId, String email, String role, String status, String nickname) {
		MemberRole memberRole = MemberRole.valueOf(role);
		MemberStatus memberStatus = MemberStatus.valueOf(status);
		return createToken(memberId, email, memberRole, memberStatus, nickname, accessTokenValidity);
	}

	// Refresh Token 생성
	public String createRefreshToken(Long memberId) {
		return createToken(memberId, null, null, null, null, refreshTokenValidity);
	}

	// 내부 토큰 생성 로직
	private String createToken(Long memberId, String email, MemberRole role, MemberStatus status, String nickname, long validity) {
		Date now = new Date();
		Date expiration = new Date(now.getTime() + validity);

		var builder = Jwts.builder()
			.setSubject(String.valueOf(memberId))
			.setIssuedAt(now)
			.setExpiration(expiration)
			.signWith(key, SignatureAlgorithm.HS256);

		if (email != null)
			builder.claim("email", email);
		if (role != null)
			builder.claim("role", role);
		if (status != null)
			builder.claim("status", status);
		if (nickname != null)
			builder.claim("nickname", nickname);

		return builder.compact();
	}

	// 토큰 파싱 (Claims 반환)
	public Claims parseToken(String token) {
		try {
			return Jwts.parserBuilder()
				.setSigningKey(key)
				.build()
				.parseClaimsJws(token)
				.getBody();
		} catch (ExpiredJwtException e) {
			throw new BusinessException(GeneralErrorCode.UNAUTHORIZED, "토큰이 만료되었습니다.");
		} catch (Exception e) {
			throw new BusinessException(GeneralErrorCode.UNAUTHORIZED, "유효하지 않은 토큰입니다.");
		}
	}

	// 토큰 만료 여부 등 유효성 검증 (Gateway용)
	public boolean validateToken(String token) {
		try {
			Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
			return true;
		} catch (Exception e) {
			log.warn("Invalid JWT Token: {}", e.getMessage());
			return false;
		}
	}
}