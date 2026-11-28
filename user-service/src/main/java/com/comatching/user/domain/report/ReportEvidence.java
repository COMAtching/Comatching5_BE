package com.comatching.user.domain.report;

import com.comatching.common.dto.chat.ReportChatEvidence;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "member_report_evidence")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportEvidence {
    @Id
    private Long reportId;

    @Getter
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "json")
    private ReportChatEvidence snapshot;

    ReportEvidence(long reportId, ReportChatEvidence snapshot) {
        this.reportId = reportId;
        this.snapshot = snapshot;
    }
}
