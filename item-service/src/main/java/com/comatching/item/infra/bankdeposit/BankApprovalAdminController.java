package com.comatching.item.infra.bankdeposit;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/payment/bank")
@RequiredArgsConstructor
@ConditionalOnProperty(name="comatching.deposit-ingest.enabled", havingValue="true")
public class BankApprovalAdminController {
    private final BankApprovalService service;
    private final DepositObservationStore observations;
    @GetMapping("/observations") @RequireRole(MemberRole.ROLE_ADMIN)
    public ApiResponse<List<DepositObservationController.Observation>> observations(@CurrentMember MemberInfo admin) {
        return ApiResponse.ok(observations.recentUnreconciled());
    }
    @GetMapping("/observations/recent") @RequireRole(MemberRole.ROLE_ADMIN)
    public ApiResponse<List<DepositObservationController.Observation>> recentObservations(@CurrentMember MemberInfo admin) {
        return ApiResponse.ok(observations.recent());
    }

    public record Switch(@jakarta.validation.constraints.NotNull Boolean enabled, @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long epoch) {}
    @GetMapping("/status") @RequireRole(MemberRole.ROLE_ADMIN)
    public ApiResponse<BankApprovalService.Status> status(@CurrentMember MemberInfo admin) {
        return ApiResponse.ok(service.status());
    }
    @PostMapping("/switch") @RequireRole(MemberRole.ROLE_ADMIN)
    public ApiResponse<BankApprovalService.Status> toggle(@jakarta.validation.Valid @RequestBody Switch change,@CurrentMember MemberInfo admin) {
        return ApiResponse.ok(service.toggle(change.enabled(),change.epoch(),admin.memberId()));
    }
    @GetMapping("/review") @RequireRole(MemberRole.ROLE_ADMIN)
    public ApiResponse<List<BankApprovalService.Review>> review(@RequestParam(defaultValue="0") long after,
            @RequestParam(defaultValue="30") int limit, @RequestParam(defaultValue="false") boolean pendingAmountsOnly,
            @CurrentMember MemberInfo admin) {
        return ApiResponse.ok(service.review(after,limit,pendingAmountsOnly));
    }
    @GetMapping("/review/pending-amounts") @RequireRole(MemberRole.ROLE_ADMIN)
    public ApiResponse<List<BankApprovalService.Review>> reviewPendingAmounts(@RequestParam(defaultValue="0") long after,
            @RequestParam(defaultValue="30") int limit, @CurrentMember MemberInfo admin) {
        return ApiResponse.ok(service.review(after,limit,true));
    }
    @PostMapping("/deposits/{deposit}/orders/{order}") @RequireRole(MemberRole.ROLE_ADMIN)
    public ApiResponse<Void> link(@PathVariable long deposit,@PathVariable long order,@CurrentMember MemberInfo admin) {
        service.manual(deposit,order,admin.memberId()); return ApiResponse.ok();
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Void> reject(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).build(); }
}
