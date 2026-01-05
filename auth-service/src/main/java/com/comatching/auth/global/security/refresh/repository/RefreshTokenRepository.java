package com.comatching.auth.global.security.refresh.repository;

import org.springframework.data.repository.CrudRepository;

import com.comatching.auth.global.security.refresh.RefreshToken;

public interface RefreshTokenRepository extends CrudRepository<RefreshToken, Long> {
}
