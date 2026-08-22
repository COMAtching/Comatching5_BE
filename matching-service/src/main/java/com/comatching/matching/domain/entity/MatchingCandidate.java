package com.comatching.matching.domain.entity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.hibernate.annotations.BatchSize;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.vo.KoreanAge;
import com.comatching.matching.domain.vo.Mbti;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "matching_candidate",
        indexes = {
                @Index(name = "idx_candidate_basic", columnList = "gender, is_matchable"),
                @Index(name = "idx_candidate_major", columnList = "major"),
                @Index(name = "idx_candidate_sample", columnList = "gender, is_matchable, random_key")
        }
)
public class MatchingCandidate {

    public static final int RANDOM_KEY_MAX = 1_000_000_000;
    public static final int RANDOM_KEY_START_BOUND = 900_000_000;

    @Id
    private Long memberId;

    private Long profileId;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "mbti"))
    private Mbti mbti;

    private String major;

    private boolean isMatchable;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "age"))
    private KoreanAge age;

    @Enumerated(EnumType.STRING)
    private ContactFrequency contactFrequency;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "candidate_hobby_categories",
            joinColumns = @JoinColumn(name = "member_id"),
            indexes = {
                    // 점수 계산용. (카테고리, 회원) 순서라 '이 회원이 이 카테고리를 몇 개 갖는지'를
                    // 커버링으로 답한다. 조인 루프가 표본 크기만큼(5,000회) 도니
                    // 행당 비용이 그대로 총합이 된다.
                    @Index(name = "idx_hobby_category_member", columnList = "hobby_categories, member_id"),
                    // FK 용. 이걸 선언하지 않으면 Hibernate 가 member_id '단독' 인덱스를 자동 생성하고,
                    // 옵티마이저가 위 커버링 인덱스 대신 그쪽을 골라 회원당 3행을 읽고 걸러낸다.
                    // 실측으로 18.9ms -> 43.8ms, 2.3배 느려졌다. 단독 인덱스가 아예 없어야 한다.
                    @Index(name = "idx_hobby_member_category", columnList = "member_id, hobby_categories")
            }
    )
    @Enumerated(EnumType.STRING)
    @BatchSize(size = 100)
    private List<HobbyCategory> hobbyCategories = new ArrayList<>();

    @Column(name = "random_key", nullable = false)
    private int randomKey;

    public void syncProfile(
            Long profileId, Gender gender, String mbti, String major, ContactFrequency contactFrequency,
            List<HobbyCategory> hobbyCategories, LocalDate birthDate, Boolean isMatchable) {
        if (profileId != null) {
            this.profileId = profileId;
        }
        if (gender != null) {
            this.gender = gender;
        }
        if (mbti != null) {
            this.mbti = new Mbti(mbti);
        }
        if (major != null) {
            this.major = major;
        }
        if (contactFrequency != null) {
            this.contactFrequency = contactFrequency;
        }
        if (birthDate != null) {
            this.age = KoreanAge.fromBirthDate(birthDate);
        }
        if (isMatchable != null) {
            this.isMatchable = isMatchable;
        }

        if (hobbyCategories != null) {
            this.hobbyCategories.clear();
            this.hobbyCategories.addAll(hobbyCategories);
        }
    }

    public static MatchingCandidate create(Long memberId, Long profileId, Gender gender, String mbti, String major,
                                           ContactFrequency contactFrequency, List<HobbyCategory> hobbyCategories, LocalDate birthDate, boolean isMatchable) {
        MatchingCandidate candidate = new MatchingCandidate();
        candidate.memberId = memberId;
        candidate.randomKey = ThreadLocalRandom.current().nextInt(RANDOM_KEY_MAX);
        candidate.syncProfile(profileId, gender, mbti, major, contactFrequency, hobbyCategories, birthDate, isMatchable);
        return candidate;
    }

    public boolean hasHobbyCategory(HobbyCategory category) {
        return category != null && this.hobbyCategories.contains(category);
    }

    public boolean matchesContactFrequency(ContactFrequency frequency) {
        return frequency == null || this.contactFrequency == frequency;
    }
}
