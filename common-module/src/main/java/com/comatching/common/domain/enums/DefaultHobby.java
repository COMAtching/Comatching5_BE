package com.comatching.common.domain.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum DefaultHobby {

	// 스포츠
	SOCCER(HobbyCategory.SPORTS, "⚽ 축구"),
	BASKETBALL(HobbyCategory.SPORTS, "🏀 농구"),
	BASEBALL(HobbyCategory.SPORTS, "⚾ 야구"),
	VOLLEYBALL(HobbyCategory.SPORTS, "🏐 배구"),
	TENNIS(HobbyCategory.SPORTS, "🎾 테니스"),
	BADMINTON(HobbyCategory.SPORTS, "🏸 배드민턴"),
	TABLE_TENNIS(HobbyCategory.SPORTS, "🏓 탁구"),
	BOWLING(HobbyCategory.SPORTS, "🎳 볼링"),
	GOLF(HobbyCategory.SPORTS, "⛳ 골프"),
	SWIMMING(HobbyCategory.SPORTS, "🏊 수영"),
	RUNNING(HobbyCategory.SPORTS, "🏃 러닝"),
	HIKING(HobbyCategory.SPORTS, "⛰️ 등산"),
	FITNESS(HobbyCategory.SPORTS, "🏋️ 헬스"),
	YOGA(HobbyCategory.SPORTS, "🧘 요가"),
	CLIMBING(HobbyCategory.SPORTS, "🧗 클라이밍"),

	// 문화예술
	MOVIE(HobbyCategory.CULTURE, "🎬 영화감상"),
	DRAMA(HobbyCategory.CULTURE, "📺 드라마"),
	MUSICAL(HobbyCategory.CULTURE, "🎭 뮤지컬"),
	CONCERT(HobbyCategory.CULTURE, "🎫 콘서트"),
	EXHIBITION(HobbyCategory.CULTURE, "🖼️ 전시회"),
	READING(HobbyCategory.CULTURE, "📚 독서"),
	WRITING(HobbyCategory.CULTURE, "✍️ 글쓰기"),
	DRAWING(HobbyCategory.CULTURE, "🎨 그림"),
	PHOTOGRAPHY(HobbyCategory.CULTURE, "📷 사진"),
	CALLIGRAPHY(HobbyCategory.CULTURE, "🖌️ 캘리그라피"),
	CRAFT(HobbyCategory.CULTURE, "🧶 공예"),
	ANIMATION(HobbyCategory.CULTURE, "👾 애니메이션"),
	WEBTOON(HobbyCategory.CULTURE, "📱 웹툰"),
	DANCE(HobbyCategory.CULTURE, "💃 댄스"),

	// 음악
	KPOP(HobbyCategory.MUSIC, "🎤 K-POP"),
	POP(HobbyCategory.MUSIC, "🎶 팝"),
	HIPHOP(HobbyCategory.MUSIC, "🧢 힙합"),
	RNB(HobbyCategory.MUSIC, "🎵 R&B"),
	ROCK(HobbyCategory.MUSIC, "🎸 록"),
	JAZZ(HobbyCategory.MUSIC, "🎷 재즈"),
	CLASSICAL(HobbyCategory.MUSIC, "🎻 클래식"),
	INDIE(HobbyCategory.MUSIC, "🎧 인디"),
	EDM(HobbyCategory.MUSIC, "🎛️ EDM"),
	BALLAD(HobbyCategory.MUSIC, "🎹 발라드"),
	GUITAR(HobbyCategory.MUSIC, "🎸 기타연주"),
	PIANO(HobbyCategory.MUSIC, "🎹 피아노"),
	DRUM(HobbyCategory.MUSIC, "🥁 드럼"),
	SINGING(HobbyCategory.MUSIC, "🎤 노래"),
	COMPOSING(HobbyCategory.MUSIC, "🎼 작곡"),

	// 여가생활
	TRAVEL(HobbyCategory.LEISURE, "✈️ 여행"),
	CAMPING(HobbyCategory.LEISURE, "⛺ 캠핑"),
	FISHING(HobbyCategory.LEISURE, "🎣 낚시"),
	CAFE(HobbyCategory.LEISURE, "☕ 카페투어"),
	RESTAURANT(HobbyCategory.LEISURE, "🍽️ 맛집탐방"),
	SHOPPING(HobbyCategory.LEISURE, "🛍️ 쇼핑"),
	COOKING(HobbyCategory.LEISURE, "🍳 요리"),
	BAKING(HobbyCategory.LEISURE, "🥐 베이킹"),
	PET(HobbyCategory.LEISURE, "🐶 반려동물"),
	GARDENING(HobbyCategory.LEISURE, "🌿 원예"),
	DRIVING(HobbyCategory.LEISURE, "🚗 드라이브"),
	CYCLING(HobbyCategory.LEISURE, "🚲 자전거"),
	SKATEBOARD(HobbyCategory.LEISURE, "🛹 스케이트보드"),
	SURFING(HobbyCategory.LEISURE, "🏄 서핑"),
	SKI(HobbyCategory.LEISURE, "⛷️ 스키/보드"),

	// 일상/공부
	STUDY(HobbyCategory.DAILY, "📝 공부"),
	LANGUAGE(HobbyCategory.DAILY, "💬 외국어"),
	CODING(HobbyCategory.DAILY, "💻 코딩"),
	STOCK(HobbyCategory.DAILY, "📈 주식/투자"),
	CERTIFICATE(HobbyCategory.DAILY, "📜 자격증"),
	VOLUNTEERING(HobbyCategory.DAILY, "❤️ 봉사활동"),
	DIARY(HobbyCategory.DAILY, "📒 일기쓰기"),
	MEDITATION(HobbyCategory.DAILY, "🧘‍♂️ 명상"),
	SELFCARE(HobbyCategory.DAILY, "✨ 자기관리"),
	FASHION(HobbyCategory.DAILY, "👗 패션"),
	MAKEUP(HobbyCategory.DAILY, "💄 뷰티/메이크업"),
	INTERIOR(HobbyCategory.DAILY, "🏠 인테리어"),
	YOUTUBE(HobbyCategory.DAILY, "▶️ 유튜브"),
	PODCAST(HobbyCategory.DAILY, "🎙️ 팟캐스트"),
	SNS(HobbyCategory.DAILY, "📱 SNS"),
	BLOG(HobbyCategory.DAILY, "⌨️ 블로그"),
	SIDE_PROJECT(HobbyCategory.DAILY, "🚀 사이드프로젝트"),

	// 게임
	LOL(HobbyCategory.GAME, "⚔️ 리그오브레전드"),
	VALORANT(HobbyCategory.GAME, "🔫 발로란트"),
	OVERWATCH(HobbyCategory.GAME, "🛡️ 오버워치"),
	MINECRAFT(HobbyCategory.GAME, "🧱 마인크래프트"),
	MAPLESTORY(HobbyCategory.GAME, "🍁 메이플스토리"),
	CONSOLE(HobbyCategory.GAME, "🎮 콘솔게임"),
	MOBILE_GAME(HobbyCategory.GAME, "📱 모바일게임"),
	BOARD_GAME(HobbyCategory.GAME, "🎲 보드게임"),
	PUZZLE(HobbyCategory.GAME, "🧩 퍼즐게임"),
	STEAM(HobbyCategory.GAME, "♨️ 스팀게임"),
	NINTENDO(HobbyCategory.GAME, "🍄 닌텐도");

	private final HobbyCategory category;
	private final String displayName;

	public static List<DefaultHobby> getByCategory(HobbyCategory category) {
		return Arrays.stream(values())
			.filter(hobby -> hobby.category == category)
			.toList();
	}

	public static Optional<DefaultHobby> findByDisplayName(String displayName) {
		return Arrays.stream(values())
			.filter(hobby -> hobby.displayName.equals(displayName))
			.findFirst();
	}
}