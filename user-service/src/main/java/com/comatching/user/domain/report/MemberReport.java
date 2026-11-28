package com.comatching.user.domain.report;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.comatching.user.domain.member.entity.Profile;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "member_report", uniqueConstraints = @UniqueConstraint(columnNames = {"reporter_id", "request_key"}),
    indexes = {@Index(name = "idx_report_review", columnList = "status,id"),
        @Index(name = "idx_report_warning", columnList = "target_id,status,acknowledged_at,id")})
public class MemberReport {
    public enum Status { OPEN, WARNED, SUSPENDED, DISMISSED }
    public enum Source { PROFILE, CHAT }
    public enum Category { ABUSE, SEXUAL_HARASSMENT, IMPERSONATION, SPAM_FRAUD, PRIVACY, OTHER }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "reporter_id", nullable = false)
    private long reporterId;
    @Column(name = "target_id", nullable = false)
    private long targetId;
    @Column(name = "request_key", nullable = false, length = 36)
    private String requestKey;
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Category category;
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;
    @Column(nullable = false, length = 1000)
    private String reason;
    private String nickname;
    private String major;
    @Column(length = 2000)
    private String intro;
    @Column(length = 2048)
    private String imageUrl;
    @Column(nullable = false)
    private LocalDateTime createdAt;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    @Column(length = 500)
    private String decisionNote;
    private LocalDateTime acknowledgedAt;
    private LocalDateTime reporterReadAt;
    private LocalDateTime liftedAt;
    private Long liftedBy;

    static MemberReport open(long reporter, Profile profile, ReportService.Submission form) {
        MemberReport report = new MemberReport();
        report.reporterId = reporter;
        report.targetId = form.targetId();
        report.requestKey = form.requestId().toString();
        report.source = form.source();
        report.category = form.category();
        report.reason = form.reason().strip();
        report.status = Status.OPEN;
        report.nickname = profile.getNickname();
        report.major = profile.getMajor();
        report.intro = profile.getIntro();
        report.imageUrl = profile.getProfileImageUrl();
        report.createdAt = LocalDateTime.now();
        return report;
    }

    void decide(Status result, long adminId, String note) {
        status = result;
        reviewedBy = adminId;
        decisionNote = note.strip();
        reviewedAt = LocalDateTime.now();
        reporterReadAt = null;
    }

    void lift(long adminId) {
        liftedBy = adminId;
        liftedAt = LocalDateTime.now();
    }
}
