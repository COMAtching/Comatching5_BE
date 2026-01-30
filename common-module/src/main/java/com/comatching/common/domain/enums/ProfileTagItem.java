package com.comatching.common.domain.enums;

import static com.comatching.common.domain.enums.ProfileTagGroup.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.Getter;

@Getter
public enum ProfileTagItem {

	// ── 외모 - 얼굴형 ──
	EGG_FACE(FACE_SHAPE, "계란형 얼굴"),
	ANGULAR_FACE(FACE_SHAPE, "각진 얼굴"),
	ROUND_FACE(FACE_SHAPE, "둥근 얼굴"),
	SHARP_FACE(FACE_SHAPE, "날렵한 얼굴"),
	SMALL_FACE(FACE_SHAPE, "작은 얼굴"),

	// ── 외모 - 포인트 ──
	DIMPLE(FACE_POINT, "보조개"),
	MOLE(FACE_POINT, "점"),
	DOUBLE_EYELID(FACE_POINT, "쌍꺼풀"),
	INNER_EYELID(FACE_POINT, "속쌍"),
	NO_EYELID(FACE_POINT, "무쌍"),
	FRECKLES(FACE_POINT, "주근깨"),

	// ── 외모 - 피부/인상 ──
	FAIR_SKIN(SKIN, "하얀 피부"),
	TAN_SKIN(SKIN, "건강한 피부"),
	SOFT_LOOK(SKIN, "순한 인상"),
	SHARP_LOOK(SKIN, "날카로운 인상"),
	FRIENDLY_LOOK(SKIN, "친근한 인상"),

	// ── 외모 - 눈/입술 ──
	BIG_EYES(EYE_LIP, "큰 눈"),
	LONG_EYES(EYE_LIP, "긴 눈"),
	CAT_EYES(EYE_LIP, "고양이 눈"),
	PUPPY_EYES(EYE_LIP, "강아지 눈"),
	THICK_LIPS(EYE_LIP, "도톰한 입술"),
	THIN_LIPS(EYE_LIP, "얇은 입술"),

	// ── 체형 - 체형 ──
	SLIM(BODY_TYPE, "마른 체형"),
	NORMAL_BUILD(BODY_TYPE, "보통 체형"),
	MUSCULAR(BODY_TYPE, "근육질"),
	CHUBBY(BODY_TYPE, "통통한 체형"),
	TALL(BODY_TYPE, "큰 키"),
	AVERAGE_HEIGHT(BODY_TYPE, "보통 키"),
	PETITE(BODY_TYPE, "아담한 키"),

	// ── 체형 - 운동 ──
	GYM(EXERCISE, "헬스/웨이트"),
	RUNNING(EXERCISE, "러닝"),
	YOGA(EXERCISE, "요가/필라테스"),
	SWIMMING(EXERCISE, "수영"),
	SPORTS(EXERCISE, "구기 스포츠"),

	// ── 체형 - 체형 특징 ──
	BROAD_SHOULDER(BODY_FEATURE, "넓은 어깨"),
	NARROW_SHOULDER(BODY_FEATURE, "좁은 어깨"),
	LONG_LEGS(BODY_FEATURE, "긴 다리"),
	NICE_HANDS(BODY_FEATURE, "예쁜 손"),

	// ── 성격 - 에너지/분위기 ──
	EXTROVERT(ENERGY, "외향적"),
	INTROVERT(ENERGY, "내향적"),
	BRIGHT(ENERGY, "밝은 분위기"),
	CALM(ENERGY, "차분한 분위기"),
	WITTY(ENERGY, "유머러스"),

	// ── 성격 - 표현/커뮤니케이션 ──
	AFFECTIONATE(EXPRESSION, "다정다감"),
	STRAIGHTFORWARD(EXPRESSION, "직진형"),
	GOOD_LISTENER(EXPRESSION, "경청형"),
	TALKATIVE(EXPRESSION, "수다쟁이"),
	SHY(EXPRESSION, "수줍음"),

	// ── 성격 - 태도/가치관 ──
	CARING(ATTITUDE, "배려심"),
	POSITIVE(ATTITUDE, "긍정적"),
	PASSIONATE(ATTITUDE, "열정적"),
	EASYGOING(ATTITUDE, "쿨한 성격"),
	LOYAL(ATTITUDE, "의리파"),

	// ── 성격 - 사고방식/일처리 ──
	LOGICAL(THINKING, "논리적"),
	CREATIVE(THINKING, "창의적"),
	ORGANIZED(THINKING, "계획적"),
	SPONTANEOUS(THINKING, "즉흥적"),
	DETAIL_ORIENTED(THINKING, "꼼꼼한"),

	// ── 매력 - 잘하는 것 ──
	GOOD_COOK(TALENT, "요리 잘하는"),
	FASHIONABLE(TALENT, "패션 감각"),
	MUSICAL(TALENT, "음악/악기"),
	ARTISTIC(TALENT, "그림/예술"),
	GOOD_DRIVER(TALENT, "운전"),
	PHOTOGRAPHY(TALENT, "사진/영상"),
	GAMING(TALENT, "게임");

	private final ProfileTagGroup group;
	private final String label;

	private static final Map<ProfileTagGroup, List<ProfileTagItem>> BY_GROUP;

	static {
		BY_GROUP = Collections.unmodifiableMap(
			Arrays.stream(values())
				.collect(Collectors.groupingBy(
					ProfileTagItem::getGroup,
					() -> new EnumMap<>(ProfileTagGroup.class),
					Collectors.toUnmodifiableList()
				))
		);
	}

	ProfileTagItem(ProfileTagGroup group, String label) {
		this.group = group;
		this.label = label;
	}

	public static List<ProfileTagItem> getByGroup(ProfileTagGroup group) {
		return BY_GROUP.getOrDefault(group, List.of());
	}
}
