package com.comatching.user.domain.report;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ReportRepository extends JpaRepository<MemberReport, Long> {
    Optional<MemberReport> findByReporterIdAndRequestKey(long reporter, String key);
    Page<MemberReport> findByStatus(MemberReport.Status status, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MemberReport r where r.id = :id")
    Optional<MemberReport> lock(@Param("id") long id);

    List<MemberReport> findTop20ByTargetIdAndStatusAndAcknowledgedAtIsNullOrderByIdAsc(long targetId, MemberReport.Status status);

    @Modifying
    @Query("update MemberReport r set r.acknowledgedAt = CURRENT_TIMESTAMP where r.id = :id and r.targetId = :target and r.status = :status and r.acknowledgedAt is null")
    int acknowledge(@Param("id") long id, @Param("target") long target, @Param("status") MemberReport.Status status);
}
