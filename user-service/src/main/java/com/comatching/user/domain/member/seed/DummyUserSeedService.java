package com.comatching.user.domain.member.seed;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.service.MemberService;
import com.comatching.user.domain.member.service.ProfileCreateService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DummyUserSeedService {

	private final MemberService memberService;
	private final ProfileCreateService profileCreateService;

	@Transactional
	public void createSingleDummyUser(String email, String encodedPassword, ProfileCreateRequest request) {
		memberService.createMember(email, encodedPassword);
		Member member = memberService.getMemberByEmail(email);
		profileCreateService.createProfile(member.getId(), request);
	}
}
