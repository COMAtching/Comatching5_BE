package com.comatching.matching.domain.repository.candidate;

import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.matching.domain.entity.MatchingCandidate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * == 개선 전 구조 ==
 * 이성 후보 5 만 명을 500 개씩 100 페이지로 전부 가져와서 JPA 엔티티 5 만 개를 만들고,
 * 자바에서 점수를 매겨 최고점 그룹을 모은 뒤 랜덤으로 하나 골랐다.
 * 요청 1 회에 쿼리 약 601 개(후보 페이지 100 + 취미 N+1 500 + 제외 목록 1).
 * <p>
 * == 개선 후 ==
 * 점수 계산을 SQL 로 내리고 최고점 한 명만 가져온다. 쿼리 1 개.
 * <p>
 * 동작은 같다. 핵심은 정렬 키가 (점수 내림차순, RAND()) 이라는 점이다.
 * 최고점 동점자가 몇 명이든 그 안에서 무작위로 섞인 뒤 첫 행을 집으므로
 * 결과 분포가 기존의 '최고점 그룹에서 균등 랜덤'과 동일하다.
 * 동점 그룹을 통째로 자바로 실어 올릴 이유가 없어졌다.
 * <p>
 * == 왜 네이티브 SQL 인가 ==
 * 조건이 요청마다 켜졌다 꺼졌다 하고(널이면 조건 자체를 빼야 한다), 점수식의 항 개수도
 * MBTI 글자 수에 따라 0~4 개로 변한다. 정적 JPQL 로는 표현이 안 된다.
 * 네이티브를 고르면 생성된 SQL 을 그대로 MySQL 에 붙여넣어 EXPLAIN 을 돌릴 수 있다.
 * <p>
 * == SQL 인젝션 ==
 * 문자열로 이어붙이는 건 '구조'뿐이다 — 조건을 넣을지 말지, 점수 항을 몇 개 둘지,
 * 그리고 enum 에서 나온 비교 연산자. 값은 전부 setParameter 로 바인딩한다.
 * 특히 MBTI 글자는 요청에서 그대로 오는 사용자 입력이라 절대 이어붙이지 않는다.
 */

/**
 * == 왜 전수조사를 버렸나 ==
 * 개선 A 로 쿼리는 601 개에서 2 개가 됐지만 여전히 이성 후보 전원을 훑고 정렬했다
 * (Using temporary; Using filesort). 5 만 명에 113.9ms, 50 만 명이면 약 570ms 다.
 * <p>
 * 그런데 실제 점수 분포를 재보니 5 만 명 중 최고점(90 점)이 단 4 명이었다.
 * 즉 같은 조건으로 요청하는 모든 사용자가 늘 같은 4 명을 받는다.
 * 전수조사는 비싸기만 한 게 아니라 매칭을 소수에게 쏠리게 만들고 있었다.
 * <p>
 * == 바뀐 정의 ==
 * '전체에서 최고점 1 명' -> '무작위 표본 5,000 명 중 최고점 1 명'.
 * 실측 분포 기준 80 점 이상을 99.9% 확보한다. 사용자는 점수를 보지 않고,
 * 애초에 동점 그룹에서 무작위로 주는 구조라 체감 차이가 없다.
 * 대신 후보 풀이 4 명에서 수백 명으로 넓어지고, 비용이 사용자 수와 무관해진다.
 * <p>
 * == 표본을 어떻게 뽑나 ==
 * random_key 는 삽입할 때 한 번 정해지는 무작위 정수다.
 * (gender, is_matchable, random_key) 인덱스에서 무작위 지점으로 점프해
 * 연속한 N 건을 읽는다. '무작위 공간에서의 연속 구간'이므로 실제로는 무작위 표본이다.
 * 인덱스 시크 한 번이라 전체 인원과 무관하게 O(표본 크기) 다.
 * <p>
 * == 창이 비면 처음부터 다시 훑는다 ==
 * random_key 는 0~10 억, randomStart 는 0~9 억에서 뽑는다. 조건을 통과한 후보
 * 전원의 키가 randomStart 보다 작으면 창이 텅 빈다. 후보가 N 명일 때 그 확률이
 * 0.9^N/(N+1) 이라 N 이 작을수록 급격히 커진다 - 1 명이면 45%, 3 명이면 18%,
 * 10 명이면 3% 다. 예전에는 이걸 그대로 '후보 없음'으로 돌려줘서, 후보 풀이 작은
 * 초기 서비스에서는 멀쩡한 상대를 두고도 매칭이 실패했다.
 * 그래서 첫 조회가 빈손이면 randomStart 를 0 으로 낮춰 한 번 더 조회한다.
 * 후보가 많을 때는 첫 조회가 거의 항상 성공하므로 추가 비용이 없다.
 * <p>
 * == 필수 조건을 표본 '안'이 아니라 '밖'에서 거는 이유 ==
 * 거꾸로다 — 필수 조건은 표본을 뽑을 때 함께 적용한다.
 * 뽑고 나서 거르면 5,000 명이 조건 통과분만 남아 수백 명으로 쪼그라든다.
 * 안에서 걸면 '조건을 만족하는 5,000 명'이 나온다. 대신 조건이 빡빡할수록
 * 5,000 건을 채우려고 더 많은 행을 읽는데, 그 양은 선택도에만 달려 있고
 * 전체 인원과는 무관하다. 100 만이든 1,000 만이든 같다.
 */
@Slf4j
@RequiredArgsConstructor
public class MatchingCandidateRepositoryImpl implements MatchingCandidateRepositoryCustom {

    private static final int SCORE_PER_MBTI_TRAIT = 10;
    private static final int SCORE_HOBBY_ONE = 10;
    private static final int SCORE_HOBBY_TWO = 15;
    private static final int SCORE_HOBBY_THREE_PLUS = 20;
    private static final int SCORE_AGE = 20;
    private static final int SCORE_CONTACT = 10;

    private final EntityManager em;

    @Override
    public Optional<MatchingCandidate> findBestCandidate(MatchingCandidateSearchCondition condition) {
        Optional<MatchingCandidate> found = queryBestCandidate(condition);

        // 표본 창(random_key >= randomStart)이 비었다. 조건에 맞는 사람이 정말 없는 건지,
        // 있는데 전원이 창 앞쪽에 몰린 건지 구분이 안 되므로 0 부터 한 번 더 훑는다.
        // 이 두 번째 조회는 첫 조회가 빈손일 때만 나가므로 정상 경로의 비용은 그대로다.
        if (found.isEmpty() && condition.randomStart() != 0) {
            return queryBestCandidate(condition.withRandomStart(0));
        }
        return found;
    }

    private Optional<MatchingCandidate> queryBestCandidate(MatchingCandidateSearchCondition condition) {

        Map<String, Object> params = new LinkedHashMap<>();

        HobbyCategory hobbyCategory = condition.scoreHobbyCategory() != null
                ? condition.scoreHobbyCategory()
                : condition.requiredHobbyCategory();

        String sample = buildSample(condition, hobbyCategory, params);
        String score = buildScore(condition, hobbyCategory, params);

        String hobbyJoin = hobbyCategory == null ? "" :
                "\n LEFT JOIN candidate_hobby_categories h"
                + "\n        ON h.member_id = c.member_id AND h.hobby_categories = :hobbyCategory";

        // 점수 항이 하나도 없으면 정렬식에서 점수를 통째로 뺀다.
        // 상수 "0" 을 넣으면 MySQL 이 ORDER BY 의 정수 리터럴을 '몇 번째 컬럼'으로
        // 해석해 Unknown column '0' in 'order clause' 로 죽는다. 괄호로 감싸도 마찬가지다.
        // 어차피 전원 동점이므로 RAND() 만 남기면 표본 안에서 무작위 추출이 된다.
        String orderBy = (score == null)
                ? "\n  ORDER BY RAND()"
                : "\n  ORDER BY (" + score + ") DESC, RAND()";

        String sql = "SELECT c.*"
                + "\n   FROM (" + sample + ") s"
                + "\n   JOIN matching_candidate c ON c.member_id = s.member_id"
                + hobbyJoin
                + "\n  GROUP BY c.member_id"
                + orderBy
                + "\n  LIMIT 1";

        log.debug("후보 조회 SQL\n{}\n파라미터: {}", sql, params);

        Query query = em.createNativeQuery(sql, MatchingCandidate.class);
        params.forEach(query::setParameter);

        @SuppressWarnings("unchecked")
        List<MatchingCandidate> rows = query.getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private String buildSample(MatchingCandidateSearchCondition condition, HobbyCategory hobbyCategory, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();

        conditions.add("mc.gender = :targetGender");
        params.put("targetGender", condition.targetGender().name());

        conditions.add("mc.is_matchable = 1");

        conditions.add("mc.random_key >= :randomStart");
        params.put("randomStart", condition.randomStart());

        if (condition.excludeMajor() != null) {
            conditions.add("mc.major <> :excludeMajor");
            params.put("excludeMajor", condition.excludeMajor());
        }

        if (condition.excludeMemberIds() != null && !condition.excludeMemberIds().isEmpty()) {
            conditions.add("mc.member_id NOT IN (:excludeMemberIds)");
            params.put("excludeMemberIds", condition.excludeMemberIds());
        }

        if (condition.minAge() != null) {
            conditions.add("mc.age >= :minAge");
            params.put("minAge", condition.minAge());
        }

        if (condition.maxAge() != null) {
            conditions.add("mc.age <= :maxAge");
            params.put("maxAge", condition.maxAge());
        }

        String required = condition.requiredMbtiTraits();
        if (required != null && !required.isBlank()) {
            for (int i = 0; i < required.length(); i++) {
                String name = "reqMbti" + i;
                conditions.add("LOCATE(:" + name + ", mc.mbti) > 0");
                params.put(name, String.valueOf(required.charAt(i)));
            }
        }

        if (condition.requiredContactFrequency() != null) {
            conditions.add("mc.contact_frequency = :reqContact");
            params.put("reqContact", condition.requiredContactFrequency().name());
        }

        if (condition.requiredHobbyCategory() != null) {
            conditions.add("EXISTS (SELECT 1 FROM candidate_hobby_categories hx"
                    + " WHERE hx.member_id = mc.member_id AND hx.hobby_categories = :hobbyCategory)");
        }

        if (hobbyCategory != null) {
            params.put("hobbyCategory", hobbyCategory.name());
        }

        return "SELECT mc.member_id"
                + "\n           FROM matching_candidate mc"
                + "\n          WHERE " + String.join("\n            AND ", conditions)
                + "\n          ORDER BY mc.random_key"
                + "\n          LIMIT " + condition.sampleSize();
    }

    private String buildScore(MatchingCandidateSearchCondition condition, HobbyCategory hobbyCategory, Map<String, Object> params) {
        List<String> terms = new ArrayList<>();

        // Mbti.calculateScore: 요청 글자 중 후보 MBTI 에 있는 개수 × 10
        String traits = condition.scoreMbtiTraits();
        if (traits != null && !traits.isBlank()) {
            for (int i = 0; i < traits.length(); i++) {
                String name = "scoreMbti" + i;
                terms.add("CASE WHEN LOCATE(:" + name + ", c.mbti) > 0 THEN "
                        + SCORE_PER_MBTI_TRAIT + " ELSE 0 END");
                params.put(name, String.valueOf(traits.charAt(i)));
            }
        }

        // calculateHobbyScore: 같은 카테고리를 몇 개 가졌는지로 10 / 15 / 20 점
        if (hobbyCategory != null && condition.scoreHobbyCategory() != null) {
            terms.add("CASE WHEN COUNT(h.member_id) >= 3 THEN " + SCORE_HOBBY_THREE_PLUS
                    + "\n               WHEN COUNT(h.member_id) = 2 THEN " + SCORE_HOBBY_TWO
                    + "\n               WHEN COUNT(h.member_id) = 1 THEN " + SCORE_HOBBY_ONE
                    + "\n               ELSE 0 END");
        }

        if (condition.scoreAgeOption() != null && condition.myAge() != null) {
            String operator = switch (condition.scoreAgeOption()) {
                case EQUAL -> "=";
                case OLDER -> ">";
                case YOUNGER -> "<";
            };
            terms.add("CASE WHEN c.age " + operator + " :myAge THEN " + SCORE_AGE + " ELSE 0 END");
            params.put("myAge", condition.myAge());
        }

        if (condition.scoreContactFrequency() != null) {
            terms.add("CASE WHEN c.contact_frequency = :scoreContact THEN " + SCORE_CONTACT + " ELSE 0 END");
            params.put("scoreContact", condition.scoreContactFrequency().name());
        }

        // 항이 없으면 null. 호출부가 ORDER BY 에서 점수를 빼는 신호로 쓴다.
        return terms.isEmpty() ? null : String.join("\n        + ", terms);
    }

}
