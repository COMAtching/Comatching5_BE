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

        Map<String, Object> params = new LinkedHashMap<>();

        HobbyCategory hobbyCategory = condition.scoreHobbyCategory() != null
                ? condition.scoreHobbyCategory()
                : condition.requiredHobbyCategory();

        String hobbyJoin = "";
        if (hobbyCategory != null) {
            hobbyJoin = "\n LEFT JOIN (SELECT member_id, COUNT(*) AS cnt"
                    + "\n              FROM candidate_hobby_categories"
                    + "\n             WHERE hobby_categories = :hobbyCategory"
                    + "\n             GROUP BY member_id) h ON h.member_id = c.member_id";
            params.put("hobbyCategory", hobbyCategory.name());
        }

        String where = buildWhere(condition, params);
        String score = buildScore(condition, hobbyCategory, params);

        String sql = "SELECT c.* FROM matching_candidate c" + hobbyJoin + "\n"
                + " WHERE " + where + "\n"
                + " ORDER BY (" + score + ") DESC, RAND()\n"
                + " LIMIT 1";

        log.debug("후보 조회 SQL\n{}\n파라미터: {}", sql, params);

        Query query = em.createNativeQuery(sql, MatchingCandidate.class);
        params.forEach(query::setParameter);

        @SuppressWarnings("unchecked")
        List<MatchingCandidate> rows = query.getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private String buildWhere(MatchingCandidateSearchCondition condition, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();

        conditions.add("c.gender = :targetGender");
        params.put("targetGender", condition.targetGender().name());

        conditions.add("c.is_matchable = 1");

        if (condition.excludeMajor() != null) {
            conditions.add("c.major <> :excludeMajor");
            params.put("excludeMajor", condition.excludeMajor());
        }

        if (condition.excludeMemberIds() != null && !condition.excludeMemberIds().isEmpty()) {
            conditions.add("c.member_id NOT IN (:excludeMemberIds)");
            params.put("excludeMemberIds", condition.excludeMemberIds());
        }

        if (condition.minAge() != null) {
            conditions.add("c.age >= :minAge");
            params.put("minAge", condition.minAge());
        }

        if (condition.maxAge() != null) {
            conditions.add("c.age <= :maxAge");
            params.put("maxAge", condition.maxAge());
        }

        String required = condition.requiredMbtiTraits();
        if (required != null && !required.isBlank()) {
            for (int i = 0; i < required.length(); i++) {
                String name = "reqMbti" + i;
                conditions.add("LOCATE(:" + name + ", c.mbti) > 0");
                params.put(name, String.valueOf(required.charAt(i)));
            }
        }

        if (condition.requiredContactFrequency() != null) {
            conditions.add("c.contact_frequency = :reqContact");
            params.put("reqContact", condition.requiredContactFrequency().name());
        }

        if (condition.requiredHobbyCategory() != null) {
            conditions.add("h.cnt >= 1");
        }

        return String.join("\n   AND ", conditions);
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
            terms.add("CASE WHEN COALESCE(h.cnt, 0) >= 3 THEN " + SCORE_HOBBY_THREE_PLUS
                    + "\n               WHEN COALESCE(h.cnt, 0) = 2 THEN " + SCORE_HOBBY_TWO
                    + "\n               WHEN COALESCE(h.cnt, 0) = 1 THEN " + SCORE_HOBBY_ONE
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

        return terms.isEmpty() ? "0" : String.join("\n        + ", terms);
    }

}
