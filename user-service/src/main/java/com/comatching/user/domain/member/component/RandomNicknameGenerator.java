package com.comatching.user.domain.member.component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

@Component
public class RandomNicknameGenerator {

	private static final List<String> DETERMINERS = List.of(
		"노래하는", "춤추는", "뛰어노는", "구르는", "빙글도는", "날아가는", "흐느적이는", "뒹구는", "달리는", "흔들리는",
		"웃는", "반짝이는", "두근대는", "깔깔대는", "놀라는", "감탄하는", "감동한", "행복한", "수줍은", "즐거운",
		"반짝반짝한", "포근한", "시원한", "따뜻한", "향기나는", "알록달록한", "몽글몽글한", "부드러운", "촉촉한",
		"장난치는", "휘파람 부는", "손흔드는", "인사하는", "하품하는", "손뻗는", "끄덕이는", "기지개 켜는", "숨바꼭질하는",
		"멋부린", "잠에서 깬", "바람 타는", "구경하는", "편지 쓰는", "노을 보는", "초콜릿 든", "선물 고르는"
	);

	private static final List<String> ANIMALS_AND_THINGS = List.of(
		"나무늘보", "햄스터", "다람쥐", "고슴도치", "기린", "오리", "판다", "앵무새", "물개", "코알라", "돌고래", "코끼리", "라마", "고래", "아기곰",
		"데이지", "민들레", "코스모스", "라벤더", "동백꽃", "연꽃", "수국", "벚꽃잎", "클로버", "벚꽃", "해바라기",
		"파도", "구름", "별똥별", "바람", "노을", "햇살", "모래성", "무지개", "달빛", "눈송이",
		"화가", "작가", "바리스타", "제빵사", "소방관", "탐험가", "마술사", "사진작가", "연기자", "시인", "조향사", "고고학자"
	);

	public String generate() {
		String determiner = DETERMINERS.get(ThreadLocalRandom.current().nextInt(DETERMINERS.size()));
		String animalOrThing = ANIMALS_AND_THINGS.get(ThreadLocalRandom.current().nextInt(ANIMALS_AND_THINGS.size()));
		int randomNumber = ThreadLocalRandom.current().nextInt(10000);

		return determiner + " " + animalOrThing + randomNumber;
	}
}
