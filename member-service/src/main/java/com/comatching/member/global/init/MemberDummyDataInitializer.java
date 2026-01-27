package com.comatching.member.global.init;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
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

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.enums.IntroQuestion;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.domain.enums.SocialAccountType;
import com.comatching.common.domain.enums.SocialType;
import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.member.domain.entity.Member;
import com.comatching.member.domain.entity.Profile;
import com.comatching.member.domain.entity.ProfileHobby;
import com.comatching.member.domain.entity.ProfileIntro;
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
			"myuser@test.com", "승환", Gender.MALE, "ENFP", "컴퓨터공학과", ContactFrequency.FREQUENT,
			List.of(new ProfileHobby(HobbyCategory.DEV, "코딩"), new ProfileHobby(HobbyCategory.SPORTS, "축구")), LocalDate.of(2000, 1, 1)
		);

		// 2. [랜덤 유저] 생성
		for (int i = 1; i <= 20; i++) {
			// Gender gender = (i % 2 == 0) ? Gender.FEMALE : Gender.MALE;
			Gender gender = Gender.FEMALE;
			String mbti = mbtis.get(random.nextInt(mbtis.size()));
			String major = majors.get(random.nextInt(majors.size()));

			// 취미 랜덤
			List<ProfileHobby> hobbies = new ArrayList<>();
			HobbyCategory randomCategory = HobbyCategory.values()[random.nextInt(HobbyCategory.values().length)];
			hobbies.add(new ProfileHobby(randomCategory, "취미" + i));
			List<ContactFrequency> contactFrequencies = new ArrayList<>(Set.of(ContactFrequency.values()));

			createMemberAndProfile(
				"user" + i + "@test.com", "유저" + i, gender, mbti, major, contactFrequencies.get(random.nextInt(contactFrequencies.size())),
				hobbies, LocalDate.of(1998 + random.nextInt(6), 1, 1)
			);
		}

		// 3. [시나리오 유저] 매칭 테스트를 위한 맞춤형 상대방 생성
		// - Scenario 1:
		// 완전 일치 (여성, ENFP, 시각디자인과, 헬스)
		createMemberAndProfile(
			"target1@test.com", "완벽매칭녀", Gender.FEMALE, "ENFP", "시각디자인과", ContactFrequency.FREQUENT,
			List.of(new ProfileHobby(HobbyCategory.SPORTS, "헬스")), LocalDate.of(2000, 5, 5)
		);

		// - Scenario 2: 취미만 다름 (여성, ENFP, 경영학과(전공다름), 독서(취미다름))
		createMemberAndProfile(
			"target2@test.com", "취미다른녀", Gender.FEMALE, "ENFP", "컴퓨터공학과", ContactFrequency.FREQUENT,
			List.of(new ProfileHobby(HobbyCategory.CULTURE, "독서")), LocalDate.of(2001, 3, 15)
		);

		log.info("✅ [Member] 더미 데이터 생성 완료!");
	}

	private void createMemberAndProfile(String email, String nickname, Gender gender, String mbti, String major, ContactFrequency contactFrequency, List<ProfileHobby> hobbies, LocalDate birthDate) {

		// 1. Member 생성 (USER, ACTIVE)
		Member member = Member.builder()
			.email(email)
			.password(passwordEncoder.encode("1234"))
			.socialType(null)
			.socialId(UUID.randomUUID().toString())
			.role(MemberRole.ROLE_USER)      // 👈 요청하신 부분
			.status(MemberStatus.ACTIVE)     // 👈 요청하신 부분
			.build();
		memberRepository.save(member);

		List<ProfileIntro> intros = createRandomIntros(gender);

		// 2. Profile 생성
		Profile profile = Profile.builder()
			.member(member)
			.nickname(nickname)
			.gender(gender)
			.mbti(mbti)
			.major(major)
			.contactFrequency(contactFrequency)
			.intro("안녕하세요! " + nickname + "입니다.")
			.profileImageUrl("https://dummy-image.com/" + nickname)
			.university("한국대학교")
			.birthDate(birthDate)
			.socialAccountType(SocialAccountType.INSTAGRAM)
			.socialAccountId("insta_" + nickname)
			.hobbies(hobbies)
			.intros(intros)
			.build();

		for (ProfileIntro intro : intros) {
			intro.assignProfile(profile);
		}

		profileRepository.save(profile);

		// 3. [핵심] Kafka 이벤트 발행 -> Matching Service가 받아서 Candidate 생성
		sendEventToMatchingService(profile);
	}

	private List<ProfileIntro> createRandomIntros(Gender gender) {
		Random random = new Random();
		List<ProfileIntro> allIntros = new ArrayList<>();

		// 후보 1: 키
		int height = (gender == Gender.MALE)
			? 170 + random.nextInt(15)
			: 155 + random.nextInt(15);
		allIntros.add(new ProfileIntro(IntroQuestion.HEIGHT, height + "cm"));

		// 후보 2: 직업
		List<String> jobs = List.of("대학생", "취준생", "개발자", "디자이너", "프리랜서");
		allIntros.add(new ProfileIntro(IntroQuestion.JOB, jobs.get(random.nextInt(jobs.size()))));

		// 후보 3: 흡연 여부
		allIntros.add(new ProfileIntro(IntroQuestion.SMOKING_HABIT, random.nextBoolean() ? "흡연" : "비흡연"));

		// 후보 4: 음주 습관
		List<String> drinking = List.of("전혀 안 함", "가끔 마심", "즐기는 편", "술고래");
		allIntros.add(new ProfileIntro(IntroQuestion.DRINKING_HABIT, drinking.get(random.nextInt(drinking.size()))));

		// 후보 5: 좋아하는 음식
		List<String> foods = List.of("한식", "일식", "양식", "중식", "분식", "마라탕");
		allIntros.add(new ProfileIntro(IntroQuestion.FAVORITE_FOOD, foods.get(random.nextInt(foods.size()))));

		// [핵심] 셔플 후 앞에서부터 3개만 자르기 (최대 3개 제한 준수)
		Collections.shuffle(allIntros);
		return new ArrayList<>(allIntros.subList(0, 3));
	}

	private void sendEventToMatchingService(Profile profile) {
		ProfileUpdatedMatchingEvent event = ProfileUpdatedMatchingEvent.builder()
			.memberId(profile.getMember().getId())
			.profileId(profile.getId())
			.gender(profile.getGender())
			.mbti(profile.getMbti())
			.major(profile.getMajor())
			.contactFrequency(profile.getContactFrequency())
			.hobbyCategories(profile.getHobbyCategories())
			.birthDate(profile.getBirthDate())
			.isMatchable(true) // 기본값 true
			.build();

		memberEventProducer.sendProfileUpdatedMatchingEvent(event);
		log.info("📤 [Event] 매칭 서비스로 프로필 전송 완료: {}", profile.getNickname());
	}
}