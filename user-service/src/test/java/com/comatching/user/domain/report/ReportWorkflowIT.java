package com.comatching.user.domain.report;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.UUID;
import com.comatching.common.dto.chat.ReportChatEvidence;
import com.comatching.user.infra.client.ReportChatClient;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.jdbc.Sql;
import com.comatching.user.support.OutboxMySqlSupport;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.aop.RoleCheckAspect;
import com.comatching.common.domain.enums.*;
import com.comatching.common.exception.handler.GlobalExceptionHandler;
import com.comatching.common.resolver.MemberInfoArgumentResolver;
import com.comatching.common.service.S3Service;
import com.comatching.user.domain.auth.service.LoginSessionStore;
import com.comatching.user.domain.event.UserEventPublisher;
import com.comatching.user.domain.member.entity.*;
import com.comatching.user.domain.member.repository.*;
import com.comatching.user.domain.member.service.*;
import com.comatching.user.global.config.ProfileImageProperties;
import com.comatching.user.infra.client.ReportMatchClient;
import com.comatching.user.infra.controller.ReportController;
import com.comatching.common.operations.FeatureGate;
import com.fasterxml.jackson.databind.ObjectMapper;

@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(statements = {"DROP TABLE IF EXISTS member_report_evidence", "DROP TABLE member_report"}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(scripts = {"/20261128_01_member_report.sql", "/20261128_02_report_inbox.sql", "/20261128_03_report_category.sql", "/20261128_04_report_evidence.sql"}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Tag("integration")
@DirtiesContext
@ContextConfiguration(classes = ReportWorkflowIT.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReportWorkflowIT extends OutboxMySqlSupport {
    @Configuration
    @EntityScan(basePackageClasses = {Member.class, MemberReport.class})
    @EnableJpaRepositories(basePackageClasses = {MemberRepository.class, ReportRepository.class})
    @Import({ReportService.class, MemberServiceImpl.class, ProfileServiceImpl.class})
    static class Config {}

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired ReportService reports;
    @Autowired MemberRepository members;
    @Autowired ProfileRepository profiles;
    @MockitoBean ReportMatchClient matches;
    @MockitoBean ReportChatClient chat;
    final ReportChatEvidence.Range range = new ReportChatEvidence.Range("room","mid","mid");
    @MockitoBean LoginSessionStore sessions;
    @MockitoBean UserEventPublisher events;
    @MockitoBean ProfileImageProperties images;
    @MockitoBean S3Service s3;
    @MockitoBean FeatureGate features;
    MockMvc mvc;
    long target;
    final long reporter = 7001;
    final long admin = 9001;

    @BeforeEach void setup() {
        var proxy = new AspectJProxyFactory(new ReportController(reports));
        proxy.addAspect(new RoleCheckAspect());
        mvc = MockMvcBuilders.standaloneSetup((ReportController)proxy.getProxy())
            .setCustomArgumentResolvers(new MemberInfoArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper())).build();
        var member = members.save(Member.builder().email(UUID.randomUUID()+"@example.test")
            .role(MemberRole.ROLE_USER).status(MemberStatus.ACTIVE).build());
        target = member.getId();
        profiles.save(Profile.builder().member(member).nickname("봄꽃").gender(Gender.FEMALE)
            .birthDate(LocalDate.of(2000,1,1)).mbti("ISTJ").contactFrequency(ContactFrequency.NORMAL)
            .university("대학교").major("국문과").intro("소개글").build());
        when(matches.arePartners(reporter,target)).thenReturn(true);
        when(chat.capture(reporter,target,range)).thenReturn(new ReportChatEvidence(range,java.util.List.of(
            new ReportChatEvidence.Message("mid",target,"TALK","첨부 원문","2026-11-28T12:00:00",ReportChatEvidence.Part.SELECTED))));
    }

    long submit(UUID key) throws Exception {
        var res = mvc.perform(post("/api/members/reports").header("X-Member-Id",reporter).header("X-Member-Role","ROLE_USER")
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"targetId":%d,"requestId":"%s","source":"CHAT","chatRange":{"roomId":"room","firstMessageId":"mid","lastMessageId":"mid"},"category":"ABUSE","reason":"모욕적인 메시지"}
                """.formatted(target,key)))
            .andExpect(status().isOk()).andReturn().getResponse();
        return new ObjectMapper().readTree(res.getContentAsByteArray()).get("data").longValue();
    }

    @Test void onlyChatSubmissionsCanAttachEvidenceAndRetriesKeepTheOriginalCopy() throws Exception {
        UUID key = UUID.randomUUID();
        long id = submit(key);
        reset(chat);
        assertThat(submit(key)).isEqualTo(id);
        verifyNoInteractions(chat);
        mvc.perform(get("/api/v1/admin/reports/" + id).header("X-Member-Id",admin).header("X-Member-Role","ROLE_ADMIN"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.chatEvidence.messages[0].content").value("첨부 원문"));
        var different = new ReportService.Submission(key,target,MemberReport.Source.CHAT,MemberReport.Category.ABUSE,
            "모욕적인 메시지",new ReportChatEvidence.Range("room","mid","later"));
        assertThatThrownBy(() -> reports.submit(reporter,different)).isInstanceOf(com.comatching.common.exception.BusinessException.class);
        for (var invalid : java.util.List.of(
            new ReportService.Submission(UUID.randomUUID(),target,MemberReport.Source.CHAT,MemberReport.Category.OTHER,"사유",null),
            new ReportService.Submission(UUID.randomUUID(),target,MemberReport.Source.PROFILE,MemberReport.Category.OTHER,"사유",range))) {
            assertThatThrownBy(() -> reports.submit(reporter,invalid)).isInstanceOf(com.comatching.common.exception.BusinessException.class);
        }
        mvc.perform(get("/api/members/me/inbox").header("X-Member-Id",reporter).header("X-Member-Role","ROLE_USER"))
            .andExpect(jsonPath("$.data.content[0].chatEvidence").doesNotExist());
    }

    @Test void failedCaptureDoesNotCreateAReportAndOldChatReportHasNoEvidence() {
        var input = new ReportService.Submission(UUID.randomUUID(),target,MemberReport.Source.CHAT,MemberReport.Category.ABUSE,"사유",range);
        when(chat.capture(reporter,target,range)).thenThrow(new IllegalStateException("chat unavailable"));
        assertThatThrownBy(() -> reports.submit(reporter,input)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_report WHERE request_key=?", Long.class,
            input.requestId().toString())).isZero();

        jdbc.update("INSERT INTO member_report(reporter_id,target_id,request_key,source,status,reason,created_at) VALUES (?,?,?,'CHAT','OPEN','기존 신고',CURRENT_TIMESTAMP)",
            reporter,target,input.requestId().toString());
        Long legacyId = jdbc.queryForObject("SELECT id FROM member_report WHERE request_key=?", Long.class, input.requestId().toString());
        assertThat(reports.detail(legacyId).chatEvidence()).isNull();
    }

    @Test void submissionRequiresARecognizedCategory() throws Exception {
        for (String field : java.util.List.of("", "\"category\":\"UNKNOWN\",")) {
            mvc.perform(post("/api/members/reports").header("X-Member-Id",reporter).header("X-Member-Role","ROLE_USER")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"targetId":%d,"requestId":"%s","source":"PROFILE",%s"reason":"신고 사유"}
                    """.formatted(target,UUID.randomUUID(),field)))
                .andExpect(status().isBadRequest());
        }
    }

    @Test void changingOnlyCategoryCannotReplayAReceiptAndLegacyReportsStayUnclassified() throws Exception {
        UUID key = UUID.randomUUID();
        long id = submit(key);
        var changed = new ReportService.Submission(key, target, MemberReport.Source.CHAT, MemberReport.Category.OTHER, "모욕적인 메시지", range);
        assertThatThrownBy(() -> reports.submit(reporter, changed)).isInstanceOf(com.comatching.common.exception.BusinessException.class);
        assertThat(reports.detail(id).report().category()).isEqualTo(MemberReport.Category.ABUSE);
        jdbc.update("UPDATE member_report SET category=NULL WHERE id=?", id);
        assertThat(reports.detail(id).report().category()).isNull();
    }

    @Test void receiptReviewWarningAndPrivateAcknowledgement() throws Exception {
        var key = UUID.randomUUID();
        long id = submit(key);
        assertThat(submit(key)).isEqualTo(id);
        mvc.perform(get("/api/v1/admin/reports").param("status","OPEN").header("X-Member-Id",admin).header("X-Member-Role","ROLE_ADMIN"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[?(@.id == " + id + ")]").isNotEmpty());
        mvc.perform(get("/api/v1/admin/reports/"+id).header("X-Member-Id",admin).header("X-Member-Role","ROLE_ADMIN"))
            .andExpect(jsonPath("$.data.report.category").value("ABUSE"))
            .andExpect(jsonPath("$.data.reason").value("모욕적인 메시지")).andExpect(jsonPath("$.data.intro").value("소개글"));
        mvc.perform(get("/api/v1/admin/reports/"+id).header("X-Member-Id",reporter).header("X-Member-Role","ROLE_USER"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/reports/"+id+"/decision").header("X-Member-Id",admin).header("X-Member-Role","ROLE_ADMIN")
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"action":"WARN","note":"상대방을 존중해 주세요."}
                """)).andExpect(status().isOk());
        assertThat(reports.warnings(reporter)).isEmpty();
        reports.acknowledge(reporter,id);
        assertThat(reports.warnings(target)).extracting(ReportService.Warning::message).containsExactly("상대방을 존중해 주세요.");
        reports.acknowledge(target,id);
        assertThat(reports.warnings(target)).isEmpty();
        assertThatThrownBy(()->reports.decide(id,admin,new ReportService.Decision(ReportService.Action.SUSPEND,"중복"))).isInstanceOf(com.comatching.common.exception.BusinessException.class);
        verifyNoInteractions(sessions);
    }

    @Test void suspensionRevokesCurrentSessionsAndChangesMatchingAvailability() throws Exception {
        long id = submit(UUID.randomUUID());
        reports.decide(id, admin, new ReportService.Decision(ReportService.Action.SUSPEND, "반복 위반"));
        verify(sessions).endAll(target);
        var suspended = members.findById(target).orElseThrow();
        assertThat(suspended.getStatus()).isEqualTo(MemberStatus.SUSPENDED);
        assertThat(suspended.getAuthVersion()).isEqualTo(1);
        verify(events).sendProfileUpdatedMatchingEvent(argThat(e -> !e.isMatchable()));
        reports.lift(id,admin);
        assertThat(members.findById(target).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        verify(events).sendProfileUpdatedMatchingEvent(argThat(e -> e.isMatchable() && e.profileVersion() == 2));
        assertThat(reports.detail(id).liftedBy()).isEqualTo(admin);
    }

    @Test void concurrentRetriesReuseOneReceiptAndOnlyOneReviewWins() throws Exception {
        var request = new ReportService.Submission(UUID.randomUUID(), target, MemberReport.Source.CHAT, MemberReport.Category.ABUSE, "동일 요청", range);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var ready = new CyclicBarrier(2);
            Callable<Long> submit = () -> {
                ready.await(5, TimeUnit.SECONDS);
                return reports.submit(reporter, request);
            };
            var first = pool.submit(submit);
            var second = pool.submit(submit);
            long id = first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(id);
            Callable<Boolean> review = () -> {
                ready.await(5, TimeUnit.SECONDS);
                try {
                    reports.decide(id, admin, new ReportService.Decision(ReportService.Action.WARN, "경고"));
                    return true;
                } catch (com.comatching.common.exception.BusinessException alreadyReviewed) {
                    return false;
                }
            };
            var left = pool.submit(review);
            var right = pool.submit(review);
            assertThat(java.util.List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
            assertThat(reports.warnings(target)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void cannotModerateWithMemberRoleAndDismissalLeavesAccountActive() throws Exception {
        long id = submit(UUID.randomUUID());
        var endpoint = "/api/v1/admin/reports/" + id + "/decision";
        mvc.perform(post(endpoint).header("X-Member-Id",reporter).header("X-Member-Role","ROLE_USER")
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"action":"SUSPEND","note":"권한 없음"}
                """)).andExpect(status().isForbidden());
        reports.decide(id, admin, new ReportService.Decision(ReportService.Action.DISMISS, "위반 확인 안 됨"));
        assertThat(reports.detail(id).report().status()).isEqualTo(MemberReport.Status.DISMISSED);
        assertThat(members.findById(target).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(reports.warnings(target)).isEmpty();
        verifyNoInteractions(sessions);
    }

    @Test void inboxIsPrivateAndAStaleReadCannotSwallowTheReviewResult() throws Exception {
        long id = submit(UUID.randomUUID());
        long other = 28001;
        long before = reports.unreadCount(reporter);
        reports.readNotice(other, id, ReportService.NoticeKind.IN_REVIEW);
        assertThat(reports.unreadCount(reporter)).isEqualTo(before);
        assertThat(reports.inbox(other, 0).content()).isEmpty();
        reports.readNotice(reporter, id, ReportService.NoticeKind.IN_REVIEW);
        assertThat(reports.unreadCount(reporter)).isEqualTo(before - 1);
        reports.decide(id, admin, new ReportService.Decision(ReportService.Action.WARN, "회원에게만 보이는 경고"));
        reports.readNotice(reporter, id, ReportService.NoticeKind.IN_REVIEW);
        assertThat(reports.unreadCount(reporter)).isEqualTo(before);
        var entry = reports.inbox(reporter, 0).content().stream().filter(i -> i.reportId() == id).findFirst().orElseThrow();
        assertThat(entry.kind()).isEqualTo(ReportService.NoticeKind.ACTION_TAKEN);
        assertThat(entry.warningMessage()).isNull();
        assertThat(entry.unread()).isTrue();
        mvc.perform(get("/api/members/me/inbox").header("X-Member-Id",reporter).header("X-Member-Role","ROLE_USER"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].decisionNote").doesNotExist())
            .andExpect(jsonPath("$.data.content[0].targetId").doesNotExist());
        assertThat(reports.inbox(target, 0).content().get(0).warningMessage()).isEqualTo("회원에게만 보이는 경고");
        reports.readNotice(reporter, id, ReportService.NoticeKind.WARNING);
        assertThat(reports.warnings(target)).hasSize(1);
        reports.readNotice(target, id, ReportService.NoticeKind.WARNING);
        assertThat(reports.unreadCount(target)).isZero();
        assertThat(reports.warnings(target)).isEmpty();
        assertThat(reports.inbox(target, 0).content()).hasSize(1);
        reports.readNotice(reporter, id, ReportService.NoticeKind.ACTION_TAKEN);
        assertThat(reports.unreadCount(reporter)).isEqualTo(before - 1);
    }

    @Test void suspensionAndDismissalKeepTheInternalNotePrivate() throws Exception {
        long id = submit(UUID.randomUUID());
        reports.decide(id, admin, new ReportService.Decision(ReportService.Action.DISMISS,"내부 종결 사유"));
        var closed = reports.inbox(reporter,0).content().stream().filter(i -> i.reportId() == id).findFirst().orElseThrow();
        assertThat(closed.kind()).isEqualTo(ReportService.NoticeKind.NO_ACTION);
        assertThat(closed.warningMessage()).isNull();
        long next = submit(UUID.randomUUID());
        reports.decide(next, admin, new ReportService.Decision(ReportService.Action.SUSPEND,"내부 정지 사유"));
        var handled = reports.inbox(reporter,0).content().stream().filter(i -> i.reportId() == next).findFirst().orElseThrow();
        assertThat(handled.kind()).isEqualTo(ReportService.NoticeKind.ACTION_TAKEN);
        assertThat(handled.warningMessage()).isNull();
        assertThat(reports.inbox(target,0).content()).isEmpty();
    }

    @Test void unrelatedAndSelfReportsAreRejected() {
        var form = new ReportService.Submission(UUID.randomUUID(),target,MemberReport.Source.PROFILE,MemberReport.Category.OTHER,"사유",null);
        assertThatThrownBy(() -> reports.submit(8800,form)).isInstanceOf(com.comatching.common.exception.BusinessException.class);
        assertThatThrownBy(() -> reports.submit(target,form)).isInstanceOf(com.comatching.common.exception.BusinessException.class);
    }
}
