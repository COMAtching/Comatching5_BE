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

    @Query("""
        select r from MemberReport r
        where r.reporterId = :member or (r.targetId = :member and r.status = :warned)
        order by coalesce(r.reviewedAt, r.createdAt) desc, r.id desc
        """)
    Page<MemberReport> inbox(@Param("member") long member, @Param("warned") MemberReport.Status warned, Pageable page);

    @Query("""
        select count(r) from MemberReport r
        where (r.reporterId = :member and r.reporterReadAt is null)
           or (r.targetId = :member and r.status = :warned and r.acknowledgedAt is null)
        """)
    long unread(@Param("member") long member, @Param("warned") MemberReport.Status warned);

    @Modifying
    @Query("""
        update MemberReport r set r.reporterReadAt = CURRENT_TIMESTAMP
        where r.id = :id and r.reporterId = :member and r.status in :states and r.reporterReadAt is null
        """)
    int readResult(@Param("member") long member, @Param("id") long id, @Param("states") List<MemberReport.Status> states);

    @Modifying
    @Query("update MemberReport r set r.acknowledgedAt = CURRENT_TIMESTAMP where r.id = :id and r.targetId = :target and r.status = :status and r.acknowledgedAt is null")
    int acknowledge(@Param("id") long id, @Param("target") long target, @Param("status") MemberReport.Status status);
}
