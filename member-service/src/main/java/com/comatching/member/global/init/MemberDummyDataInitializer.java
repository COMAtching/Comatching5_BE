package com.comatching.member.global.init;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.Hobby;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.domain.enums.SocialAccountType;
import com.comatching.common.domain.enums.SocialType;
import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.member.domain.entity.Member;
import com.comatching.member.domain.entity.Profile;
import com.comatching.member.domain.repository.MemberRepository;
import com.comatching.member.domain.repository.ProfileRepository;
import com.comatching.member.infra.kafka.MemberEventProducer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@org.springframework.context.annotation.Profile("default")
@RequiredArgsConstructor
public class MemberDummyDataInitializer {

	private final MemberRepository memberRepository;
	private final ProfileRepository profileRepository;
	private final MemberEventProducer memberEventProducer;
	private final PasswordEncoder passwordEncoder;

	@Bean
	public CommandLineRunner initMemberData() {
		return args -> {
			if (memberRepository.count() > 0) {
				log.info("ℹ️ [Member] 이미 데이터가 존재합니다. 초기화를 건너뜁니다.");
				return;
			}
			createDummyMembers();
		};
	}

	@Transactional
	public void createDummyMembers() {
		log.info("🚀 [Member] 더미 데이터 생성 및 매칭 이벤트 발행 시작...");

		List<String> majors = List.of("컴퓨터공학과", "경영학과", "시각디자인과", "기계공학과", "심리학과", "체육학과", "영어영문학과");
		List<String> mbtis = List.of("ENFP", "ENTJ", "INFP", "ISTJ", "ESFJ", "INTJ", "ENTP", "ISFP");
		Random random = new Random();

		// 1. [내 계정] 테스트용 내 계정 생성 (로그인용)
		createMemberAndProfile(
			"myuser@test.com", "승환", Gender.MALE, "ENFP", "컴퓨터공학과",
			Set.of(Hobby.CODING, Hobby.SOCCER), LocalDate.of(2000, 1, 1)
		);

		// 2. [랜덤 유저] 생성
		for (int i = 1; i <= 30; i++) {
			Gender gender = (i % 2 == 0) ? Gender.FEMALE : Gender.MALE;
			String mbti = mbtis.get(random.nextInt(mbtis.size()));
			String major = majors.get(random.nextInt(majors.size()));

			// 취미 랜덤
			Set<Hobby> hobbies = new HashSet<>();
			hobbies.add(Hobby.values()[random.nextInt(Hobby.values().length)]);

			createMemberAndProfile(
				"user" + i + "@test.com", "유저" + i, gender, mbti, major,
				hobbies, LocalDate.of(1998 + random.nextInt(6), 1, 1)
			);
		}

		// 3. [시나리오 유저] 매칭 테스트를 위한 맞춤형 상대방 생성
		// - Scenario 1: 완전 일치 (여성, ENFP, 시각디자인과, 헬스)
		createMemberAndProfile(
			"target1@test.com", "완벽매칭녀", Gender.FEMALE, "ENFP", "시각디자인과",
			Set.of(Hobby.GYM), LocalDate.of(2000, 5, 5)
		);

		// - Scenario 2: 취미만 다름 (여성, ENFP, 경영학과(전공다름), 독서(취미다름))
		createMemberAndProfile(
			"target2@test.com", "취미다른녀", Gender.FEMALE, "ENFP", "컴퓨터공학과",
			Set.of(Hobby.READING), LocalDate.of(2001, 3, 15)
		);

		log.info("✅ [Member] 더미 데이터 생성 완료!");
	}

	private void createMemberAndProfile(String email, String nickname, Gender gender, String mbti, String major, Set<Hobby> hobbies, LocalDate birthDate) {

		// 1. Member 생성 (USER, ACTIVE)
		Member member = Member.builder()
			.email(email)
			.password(passwordEncoder.encode("1234"))
			.socialType(SocialType.KAKAO)
			.socialId(UUID.randomUUID().toString())
			.role(MemberRole.ROLE_USER)      // 👈 요청하신 부분
			.status(MemberStatus.ACTIVE)     // 👈 요청하신 부분
			.build();
		memberRepository.save(member);

		// 2. Profile 생성
		Profile profile = com.comatching.member.domain.entity.Profile.builder()
			.member(member)
			.nickname(nickname)
			.gender(gender)
			.mbti(mbti)
			.major(major)
			.intro("안녕하세요! " + nickname + "입니다.")
			.profileImageUrl("https://dummy-image.com/" + nickname)
			.university("한국대학교")
			.birthDate(birthDate)
			.socialAccountType(SocialAccountType.INSTAGRAM)
			.socialAccountId("insta_" + nickname)
			.hobbies(hobbies)
			.intros(new ArrayList<>())
			.build();
		profileRepository.save(profile);

		// 3. [핵심] Kafka 이벤트 발행 -> Matching Service가 받아서 Candidate 생성
		sendEventToMatchingService(profile);
	}

	private void sendEventToMatchingService(Profile profile) {
		ProfileUpdatedMatchingEvent event = ProfileUpdatedMatchingEvent.builder()
			.memberId(profile.getMember().getId())
			.profileId(profile.getId())
			.gender(profile.getGender())
			.mbti(profile.getMbti())
			.major(profile.getMajor())
			.hobbies(profile.getHobbies())
			.birthDate(profile.getBirthDate())
			.isMatchable(true) // 기본값 true
			.build();

		memberEventProducer.sendProfileUpdatedMatchingEvent(event);
		log.info("📤 [Event] 매칭 서비스로 프로필 전송 완료: {}", profile.getNickname());
	}
}