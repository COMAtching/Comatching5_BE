package com.comatching.user.domain.member.seed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.DefaultHobby;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.ProfileTagItem;
import com.comatching.common.dto.member.HobbyDto;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileTagDto;
import com.comatching.common.exception.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dummy.seed.enabled", havingValue = "true")
public class DummyUserSeedRunner implements ApplicationRunner {

	private static final String REQUIRED_CONFIRM_TOKEN = "I_UNDERSTAND_THIS_WRITES_DATA";
	private static final List<String> MBTI_POOL = List.of(
		"INTJ", "INTP", "ENTJ", "ENTP",
		"INFJ", "INFP", "ENFJ", "ENFP",
		"ISTJ", "ISFJ", "ESTJ", "ESFJ",
		"ISTP", "ISFP", "ESTP", "ESFP"
	);
	private static final List<String> DEFAULT_UNIVERSITIES = List.of(
		"가톨릭대학교"
	);
	private static final List<String> DEFAULT_MAJORS = List.of(
		"인문사회계열",
		"국어국문학과",
		"철학과",
		"국사학과",
		"영어영문학부",
		"중국언어문화학과",
		"일어일본문화학과",
		"프랑스어문화학과",
		"음악과",
		"성악과",
		"종교학과",
		"신학대학",
		"사회복지학과",
		"심리학과",
		"사회학과",
		"특수교육과",
		"경영학과",
		"회계학과",
		"국제학부",
		"법학과",
		"경제학과",
		"행정학과",
		"자연공학계열",
		"화학과",
		"수학과",
		"물리학과",
		"공간디자인소비자학과",
		"의류학과",
		"아동학과",
		"식품영양학과",
		"컴퓨터정보공학부",
		"미디어기술콘텐츠학과",
		"정보통신전자공학부",
		"생명공학과",
		"에너지환경공학과",
		"바이오메디컬화학공학과",
		"인공지능학과",
		"데이터사이언스학과",
		"바이오메디컬소프트웨어학과",
		"바이오로직스공학부",
		"AI의공학과",
		"의약계열",
		"약학대학",
		"간호대학",
		"의과대학",
		"글로벌미래경영학과",
		"세무회계금융학과",
		"IT파이낸스학과",
		"자유전공학부"
	);
	private static final List<String> IMAGE_KEY_POOL = List.of(
		"default_dog", "default_cat", "default_bear", "default_fox", "default_rabbit", "default_otter"
	);
	private static final List<String> INTRO_POOL = List.of(
		"hello there",
		"coffee and coding",
		"weekend hiking",
		"always learning",
		"music and books"
	);

	private final DummyUserSeedService dummyUserSeedService;
	private final PasswordEncoder passwordEncoder;
	private final Environment environment;

	@Value("${dummy.seed.confirm-token:}")
	private String confirmToken;

	@Value("${dummy.seed.count:3000}")
	private int targetCount;

	@Value("${dummy.seed.start-index:1}")
	private long startIndex;

	@Value("${dummy.seed.max-attempt-multiplier:30}")
	private int maxAttemptMultiplier;

	@Value("${dummy.seed.email-prefix:loadtest.user}")
	private String emailPrefix;

	@Value("${dummy.seed.email-domain:comatching.seed}")
	private String emailDomain;

	@Value("${dummy.seed.nickname-prefix:dummy_user}")
	private String nicknamePrefix;

	@Value("${dummy.seed.raw-password:Dummy!1234}")
	private String rawPassword;

	@Value("${dummy.seed.require-allowed-profile:true}")
	private boolean requireAllowedProfile;

	@Value("${dummy.seed.allowed-profiles:aws,prod}")
	private String allowedProfilesRaw;

	@Value("${dummy.seed.universities:}")
	private String universitiesRaw;

	@Value("${dummy.seed.majors:}")
	private String majorsRaw;

	@Override
	public void run(ApplicationArguments args) {
		validateSafetyGuards();

		if (targetCount <= 0) {
			log.info("[DummySeed] targetCount <= 0. Skip.");
			return;
		}

		List<String> universities = resolveValuePool(universitiesRaw, DEFAULT_UNIVERSITIES);
		List<String> majors = resolveValuePool(majorsRaw, DEFAULT_MAJORS);
		Random random = new Random();
		String encodedPassword = passwordEncoder.encode(rawPassword);

		int created = 0;
		int skipped = 0;
		long attempts = 0;
		long sequence = startIndex;
		long maxAttempts = (long) targetCount * maxAttemptMultiplier;

		log.info("[DummySeed] Start seeding. targetCount={}, startIndex={}, emailPrefix={}, emailDomain={}",
			targetCount, startIndex, emailPrefix, emailDomain);

		while (created < targetCount && attempts < maxAttempts) {
			attempts++;

			String email = buildEmail(sequence);
			String nickname = buildNickname(sequence);
			sequence++;

			try {
				createSingleDummyUser(random, email, nickname, encodedPassword, universities, majors);
				created++;

				if (created % 100 == 0) {
					log.info("[DummySeed] Progress created={}/{} (attempts={}, skipped={})",
						created, targetCount, attempts, skipped);
				}
			} catch (BusinessException e) {
				String code = e.getErrorCode().getCode();
				// Duplicate email/nickname/profile already exists -> skip
				if ("MEM-002".equals(code) || "MEM-003".equals(code) || "MEM-008".equals(code)) {
					skipped++;
					continue;
				}

				log.error("[DummySeed] Failed for email={}. code={}, message={}", email, code, e.getMessage());
			} catch (Exception e) {
				log.error("[DummySeed] Unexpected failure for email={}", email, e);
			}
		}

		log.info("[DummySeed] Finished. created={}, skipped={}, attempts={}, target={}",
			created, skipped, attempts, targetCount);

		if (created < targetCount) {
			throw new IllegalStateException("Dummy seed did not reach target count. created=" + created
				+ ", target=" + targetCount + ", attempts=" + attempts);
		}
	}

	private void createSingleDummyUser(
		Random random,
		String email,
		String nickname,
		String encodedPassword,
		List<String> universities,
		List<String> majors
	) {
		ProfileCreateRequest request = ProfileCreateRequest.builder()
			.nickname(nickname)
			.gender(randomEnum(random, Gender.values()))
			.birthDate(randomBirthDate(random))
			.mbti(randomElement(random, MBTI_POOL))
			.intro(randomElement(random, INTRO_POOL))
			.profileImageKey(randomElement(random, IMAGE_KEY_POOL))
			.socialType(null)
			.socialAccountId(null)
			.university(randomElement(random, universities))
			.major(randomElement(random, majors))
			.contactFrequency(randomEnum(random, ContactFrequency.values()))
			.song("https://example.com/song/" + email.replace("@", "_"))
			.hobbies(randomHobbies(random))
			.tags(randomTags(random))
			.build();

		dummyUserSeedService.createSingleDummyUser(email, encodedPassword, request);
	}

	private List<HobbyDto> randomHobbies(Random random) {
		List<DefaultHobby> pool = new ArrayList<>(Arrays.asList(DefaultHobby.values()));
		Collections.shuffle(pool, random);

		int hobbyCount = 2 + random.nextInt(4); // 2~5
		return pool.subList(0, hobbyCount).stream()
			.map(hobby -> new HobbyDto(hobby.getCategory(), hobby.name()))
			.toList();
	}

	private List<ProfileTagDto> randomTags(Random random) {
		List<ProfileTagItem> pool = new ArrayList<>(Arrays.asList(ProfileTagItem.values()));
		Collections.shuffle(pool, random);

		int tagCount = 1 + random.nextInt(5); // 1~5
		return pool.subList(0, tagCount).stream()
			.map(tag -> new ProfileTagDto(tag.name()))
			.toList();
	}

	private LocalDate randomBirthDate(Random random) {
		int startYear = 1998;
		int endYear = 2005;
		int year = startYear + random.nextInt(endYear - startYear + 1);
		int month = 1 + random.nextInt(12);
		int day = 1 + random.nextInt(28);
		return LocalDate.of(year, month, day);
	}

	private void validateSafetyGuards() {
		if (!REQUIRED_CONFIRM_TOKEN.equals(confirmToken)) {
			throw new IllegalStateException(
				"dummy.seed.confirm-token must be set to " + REQUIRED_CONFIRM_TOKEN + " to run seeding."
			);
		}

		if (!requireAllowedProfile) {
			return;
		}

		Set<String> activeProfiles = Arrays.stream(environment.getActiveProfiles())
			.map(s -> s.toLowerCase(Locale.ROOT))
			.collect(Collectors.toSet());
		Set<String> allowedProfiles = splitToSet(allowedProfilesRaw);

		boolean profileAllowed = !Collections.disjoint(activeProfiles, allowedProfiles);
		if (!profileAllowed) {
			throw new IllegalStateException(
				"Seeding blocked by profile guard. activeProfiles=" + activeProfiles + ", allowedProfiles="
					+ allowedProfiles
			);
		}
	}

	private static Set<String> splitToSet(String value) {
		if (value == null || value.isBlank()) {
			return Set.of();
		}
		return Arrays.stream(value.split(","))
			.map(String::trim)
			.filter(s -> !s.isBlank())
			.map(s -> s.toLowerCase(Locale.ROOT))
			.collect(Collectors.toSet());
	}

	private static List<String> resolveValuePool(String raw, List<String> fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		List<String> parsed = Arrays.stream(raw.split(","))
			.map(String::trim)
			.filter(s -> !s.isBlank())
			.toList();
		return parsed.isEmpty() ? fallback : parsed;
	}

	private String buildEmail(long sequence) {
		return String.format("%s.%06d@%s", emailPrefix, sequence, emailDomain);
	}

	private String buildNickname(long sequence) {
		return String.format("%s_%06d", nicknamePrefix, sequence);
	}

	private static <T> T randomElement(Random random, List<T> list) {
		return list.get(random.nextInt(list.size()));
	}

	private static <T> T randomEnum(Random random, T[] values) {
		return values[random.nextInt(values.length)];
	}
}
