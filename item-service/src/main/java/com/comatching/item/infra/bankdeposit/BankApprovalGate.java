package com.comatching.item.infra.bankdeposit;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.global.exception.PaymentErrorCode;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="comatching.deposit-ingest.enabled", havingValue="true")
public class BankApprovalGate {
    private final JdbcTemplate jdbc;
    // Taken before assigning the order timestamp, before either approval, and before changing epochs.
    public boolean lock() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM bank_approval_control WHERE id=1 FOR UPDATE", Boolean.class));
    }
    public void requireUnlinkedApprovalAllowed(long order) {
        if (lock() || jdbc.queryForObject("SELECT COUNT(*) FROM bank_order_review WHERE order_id=?", Integer.class, order) > 0)
            throw new BusinessException(PaymentErrorCode.DEPOSIT_SELECTION_REQUIRED);
    }
}
