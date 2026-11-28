package com.comatching.user.domain.report;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import jakarta.validation.Valid;
import com.comatching.common.dto.chat.ReportChatEvidence;
import com.comatching.user.infra.client.ReportChatClient;
import java.util.UUID;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.comatching.common.domain.enums.*;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.user.domain.auth.service.LoginSessionStore;
import com.comatching.user.domain.member.service.MemberService;
import com.comatching.user.domain.member.service.ProfileManageService;
import com.comatching.user.global.exception.UserErrorCode;
import com.comatching.user.infra.client.ReportMatchClient;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {
    private final ReportRepository reports;
    private final ReportEvidenceRepository evidence;
    private final ReportChatClient chat;
    private final ReportMatchClient matches;
    private final MemberService members;
    private final LoginSessionStore sessions;
    private final ProfileManageService profiles;

    public record Submission(@NotNull UUID requestId, @Positive long targetId, @NotNull MemberReport.Source source,
                             @NotNull MemberReport.Category category, @NotBlank @Size(max = 1000) String reason,
                             @Valid ReportChatEvidence.Range chatRange) {}
    public enum Action { WARN, SUSPEND, DISMISS }
    public record Decision(@NotNull Action action, @NotBlank @Size(max = 500) String note) {}
    public record Summary(long id, long targetId, String nickname, MemberReport.Source source,
                          MemberReport.Category category, MemberReport.Status status, LocalDateTime createdAt) {
        static Summary of(MemberReport r) {
            return new Summary(r.getId(), r.getTargetId(), r.getNickname(), r.getSource(), r.getCategory(), r.getStatus(), r.getCreatedAt());
        }
    }
    public record Detail(Summary report, long reporterId, String reason, String major, String intro, String imageUrl,
                         String decisionNote, Long reviewedBy, LocalDateTime reviewedAt, LocalDateTime liftedAt, Long liftedBy,
                         ReportChatEvidence chatEvidence) {}
    public record Warning(long id, String message, LocalDateTime issuedAt) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long submit(long reporter, Submission input) {
        if (reporter == input.targetId() || !matches.arePartners(reporter, input.targetId()))
            throw new BusinessException(GeneralErrorCode.FORBIDDEN);
        var key = input.requestId().toString();
        var existing = reports.findByReporterIdAndRequestKey(reporter, key);
        if (existing.isPresent()) return existingReceipt(existing.get(), input);

        boolean isChat = input.source() == MemberReport.Source.CHAT;
        if (isChat != (input.chatRange() != null)) throw new BusinessException(GeneralErrorCode.BAD_REQUEST);
        var snapshot = isChat ? chat.capture(reporter, input.targetId(), input.chatRange()) : null;
        if (isChat && (snapshot == null || !input.chatRange().equals(snapshot.range()) || snapshot.messages().isEmpty()))
            throw new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);

        // A remote read may take time. Lock the target only when ready to commit both records.
        var member = members.getMemberByIdForUpdate(input.targetId());
        existing = reports.findByReporterIdAndRequestKey(reporter, key);
        if (existing.isPresent()) return existingReceipt(existing.get(), input);
        if (member.getProfile() == null || member.getRole() != MemberRole.ROLE_USER || member.getStatus() == MemberStatus.WITHDRAWN)
            throw new BusinessException(GeneralErrorCode.NOT_FOUND);
        long id = reports.save(MemberReport.open(reporter, member.getProfile(), input)).getId();
        if (snapshot != null) evidence.save(new ReportEvidence(id, snapshot));
        return id;
    }

    private long existingReceipt(MemberReport report, Submission input) {
        if (report.getTargetId() != input.targetId() || report.getSource() != input.source()
            || report.getCategory() != input.category() || !report.getReason().equals(input.reason().strip()))
            throw conflict();
        var saved = report.getSource() == MemberReport.Source.CHAT
            ? evidence.findById(report.getId()).map(e -> e.getSnapshot().range()).orElse(null) : null;
        if (!Objects.equals(saved, input.chatRange())) throw conflict();
        return report.getId();
    }

    public PagingResponse<Summary> list(MemberReport.Status status, int page) {
        if (page < 0) throw new BusinessException(GeneralErrorCode.BAD_REQUEST);
        var paging = PageRequest.of(page, 20, Sort.by("id").descending());
        var rows = status == null ? reports.findAll(paging) : reports.findByStatus(status, paging);
        return PagingResponse.from(rows.map(Summary::of));
    }

    public Detail detail(long id) {
        var r = reports.findById(id).orElseThrow(() -> new BusinessException(GeneralErrorCode.NOT_FOUND));
        return new Detail(Summary.of(r), r.getReporterId(), r.getReason(), r.getMajor(), r.getIntro(), r.getImageUrl(),
            r.getDecisionNote(), r.getReviewedBy(), r.getReviewedAt(), r.getLiftedAt(), r.getLiftedBy(),
            r.getSource() == MemberReport.Source.CHAT ? evidence.findById(id).map(ReportEvidence::getSnapshot).orElse(null) : null);
    }

    @Transactional
    public void decide(long id, long adminId, Decision input) {
        var r = locked(id);
        if (r.getStatus() != MemberReport.Status.OPEN) throw conflict();
        if (input.action() == Action.DISMISS) {
            r.decide(MemberReport.Status.DISMISSED, adminId, input.note());
            return;
        }
        var target = members.getMemberByIdForUpdate(r.getTargetId());
        if (target.getRole() != MemberRole.ROLE_USER || target.getStatus() != MemberStatus.ACTIVE) throw conflict();
        if (input.action() == Action.WARN) {
            r.decide(MemberReport.Status.WARNED, adminId, input.note());
        } else {
            sessions.endAll(target.getId());
            target.suspend();
            profiles.publishMatchingAvailability(target.getId());
            r.decide(MemberReport.Status.SUSPENDED, adminId, input.note());
        }
    }

    @Transactional
    public void lift(long id, long adminId) {
        var r = locked(id);
        if (r.getStatus() != MemberReport.Status.SUSPENDED || r.getLiftedAt() != null) throw conflict();
        var target = members.getMemberByIdForUpdate(r.getTargetId());
        if (target.getStatus() != MemberStatus.SUSPENDED) throw conflict();
        target.liftSuspension();
        profiles.publishMatchingAvailability(target.getId());
        r.lift(adminId);
    }

    public enum NoticeKind { IN_REVIEW, ACTION_TAKEN, NO_ACTION, WARNING }
    public record InboxItem(long reportId, NoticeKind kind, LocalDateTime at, boolean unread, String warningMessage) {}

    public PagingResponse<InboxItem> inbox(long member, int page) {
        if (page < 0) throw new BusinessException(GeneralErrorCode.BAD_REQUEST);
        var rows = reports.inbox(member, MemberReport.Status.WARNED, PageRequest.of(page, 20));
        return PagingResponse.from(rows.map(r -> {
            if (r.getReporterId() != member) {
                return new InboxItem(r.getId(), NoticeKind.WARNING, r.getReviewedAt(),
                    r.getAcknowledgedAt() == null, r.getDecisionNote());
            }
            var kind = switch (r.getStatus()) {
                case OPEN -> NoticeKind.IN_REVIEW;
                case WARNED, SUSPENDED -> NoticeKind.ACTION_TAKEN;
                case DISMISSED -> NoticeKind.NO_ACTION;
            };
            return new InboxItem(r.getId(), kind, r.getReviewedAt() == null ? r.getCreatedAt() : r.getReviewedAt(),
                r.getReporterReadAt() == null, null);
        }));
    }

    public long unreadCount(long member) {
        return reports.unread(member, MemberReport.Status.WARNED);
    }

    @Transactional
    public void readNotice(long member, long id, NoticeKind seen) {
        // A delayed tap on a receipt must leave a newly arrived review result unread.
        switch (seen) {
            case WARNING -> reports.acknowledge(id, member, MemberReport.Status.WARNED);
            case IN_REVIEW -> reports.readResult(member, id, List.of(MemberReport.Status.OPEN));
            case ACTION_TAKEN -> reports.readResult(member, id, List.of(MemberReport.Status.WARNED, MemberReport.Status.SUSPENDED));
            case NO_ACTION -> reports.readResult(member, id, List.of(MemberReport.Status.DISMISSED));
        }
    }

    public List<Warning> warnings(long target) {
        return reports.findTop20ByTargetIdAndStatusAndAcknowledgedAtIsNullOrderByIdAsc(target, MemberReport.Status.WARNED)
            .stream().map(r -> new Warning(r.getId(), r.getDecisionNote(), r.getReviewedAt())).toList();
    }

    @Transactional
    public void acknowledge(long target, long id) {
        reports.acknowledge(id, target, MemberReport.Status.WARNED);
    }

    private MemberReport locked(long id) {
        return reports.lock(id).orElseThrow(() -> new BusinessException(GeneralErrorCode.NOT_FOUND));
    }
    private BusinessException conflict() { return new BusinessException(UserErrorCode.REPORT_CONFLICT); }
}
