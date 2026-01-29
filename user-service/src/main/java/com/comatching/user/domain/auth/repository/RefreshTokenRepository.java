package com.comatching.user.domain.auth.repository;

import org.springframework.data.repository.CrudRepository;

import com.comatching.user.domain.auth.entity.RefreshToken;

public interface RefreshTokenRepository extends CrudRepository<RefreshToken, Long> {
}
