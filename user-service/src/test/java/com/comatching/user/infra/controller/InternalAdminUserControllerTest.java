package com.comatching.user.infra.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.comatching.common.dto.member.AdminGiftCardUserProfileDto;
import com.comatching.user.domain.member.service.AdminMemberQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAdminUserController API 계약 테스트")
class InternalAdminUserControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @Mock
    private AdminMemberQueryService adminMemberQueryService;

    @InjectMocks
    private InternalAdminUserController internalAdminUserController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(internalAdminUserController).build();
    }

    @Test
    @DisplayName("POST /api/internal/admin/users/bulk는 회원 ID 목록을 전달하고 사용자 정보를 반환한다")
    void shouldReturnUsersByIds() throws Exception {
        List<Long> memberIds = List.of(3L, 1L);
        given(adminMemberQueryService.getUsersByIds(memberIds)).willReturn(List.of(
            new AdminGiftCardUserProfileDto(3L, "third@example.com", "세번째", "셋"),
            new AdminGiftCardUserProfileDto(1L, "first@example.com", "첫번째", "하나")
        ));

        mockMvc.perform(post("/api/internal/admin/users/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(memberIds)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(3))
            .andExpect(jsonPath("$[0].email").value("third@example.com"))
            .andExpect(jsonPath("$[0].realName").value("세번째"))
            .andExpect(jsonPath("$[0].nickname").value("셋"))
            .andExpect(jsonPath("$[1].id").value(1))
            .andExpect(jsonPath("$[1].email").value("first@example.com"));

        then(adminMemberQueryService).should().getUsersByIds(memberIds);
    }

    @Test
    @DisplayName("POST 일괄 회원 조회는 빈 ID 목록도 서비스에 그대로 전달한다")
    void shouldDelegateEmptyMemberIds() throws Exception {
        given(adminMemberQueryService.getUsersByIds(List.of())).willReturn(List.of());

        mockMvc.perform(post("/api/internal/admin/users/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$").isEmpty());

        then(adminMemberQueryService).should().getUsersByIds(List.of());
    }

    @Test
    @DisplayName("POST 일괄 회원 조회의 요청 본문이 배열이 아니면 400을 반환한다")
    void shouldRejectMalformedMemberIds() throws Exception {
        mockMvc.perform(post("/api/internal/admin/users/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":1}"))
            .andExpect(status().isBadRequest());

        then(adminMemberQueryService).shouldHaveNoInteractions();
    }
}
