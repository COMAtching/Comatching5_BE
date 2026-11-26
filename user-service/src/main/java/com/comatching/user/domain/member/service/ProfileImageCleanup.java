package com.comatching.user.domain.member.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.comatching.common.service.S3Service;
import com.comatching.user.domain.member.repository.ProfileRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class ProfileImageCleanup {
	private final S3Service images;
	private final ProfileRepository profiles;

	@Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
	void removeExpiredUploads() {
		images.cleanupExpired(S3Service.Purpose.PROFILE,
			(memberId, objectPath) -> profiles.existsByMemberIdAndProfileImageUrlEndingWith(Long.valueOf(memberId), objectPath));
	}
}
