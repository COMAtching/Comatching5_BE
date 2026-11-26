package com.comatching.item.infra.bankdeposit;

import com.comatching.common.aop.RoleCheckAspect;
import com.comatching.common.exception.handler.GlobalExceptionHandler;
import com.comatching.common.resolver.MemberInfoArgumentResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BankApprovalAdminControllerTest {
    @Mock private BankApprovalService service;
    @Mock private DepositObservationStore observations;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var factory = new AspectJProxyFactory(new BankApprovalAdminController(service, observations));
        factory.addAspect(new RoleCheckAspect());
        BankApprovalAdminController controller = factory.getProxy();
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new MemberInfoArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
            .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "review", "observations", "review/pending-amounts", "observations/recent"})
    void adminCanRead(String endpoint) throws Exception {
        mvc.perform(get("/api/v1/admin/payment/bank/" + endpoint)
                .header("X-Member-Id", "900").header("X-Member-Role", "ROLE_ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("GEN-000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "review", "observations", "review/pending-amounts", "observations/recent"})
    void regularMemberCannotRead(String endpoint) throws Exception {
        mvc.perform(get("/api/v1/admin/payment/bank/" + endpoint)
                .header("X-Member-Id", "901").header("X-Member-Role", "ROLE_USER"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service, observations);
    }

    @Test
    void pendingAmountsRouteAlwaysAppliesFilterBeforePaging() throws Exception {
        mvc.perform(get("/api/v1/admin/payment/bank/review/pending-amounts")
                .param("after", "10").param("limit", "31")
                .header("X-Member-Id", "900").header("X-Member-Role", "ROLE_ADMIN"))
            .andExpect(status().isOk());
        verify(service).review(10, 31, true);
    }
}
