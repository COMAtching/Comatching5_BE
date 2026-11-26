package com.comatching.item.infra.bankdeposit;

import com.comatching.item.domain.product.service.AdminPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import static com.comatching.item.infra.bankdeposit.BankScanService.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name="comatching.deposit-ingest.enabled", havingValue="true")
public class BankApprovalService {
    private final JdbcTemplate db;
    private final BankApprovalGate gate;
    private final AdminPaymentService approvals;

    public record Status(boolean enabled, long epoch, LocalDateTime switchedAt, LocalDateTime lastSuccess,
                         String checkpoint, String issue, int queueDepth, Long oldestPublishWaitSeconds) {}
    public record Candidate(long id,String name,long amount,String status,LocalDateTime requestedAt,LocalDateTime expiresAt) {}
    record Deposit(long id,String payer,long amount,String second,String reason,Long orderId) {}
    private record MatchGroup(String payer,long amount,String second) {}
    public record Review(long id,String payer,long amount,String second,String reason,List<Candidate> candidates) {}

    public Status status() {
        return db.queryForObject("""
            SELECT c.*, (SELECT TIMESTAMPDIFF(SECOND, MIN(received_at), CURRENT_TIMESTAMP) FROM bank_deposit_observation WHERE published_at IS NULL) raw_age,
                (SELECT MIN(received_at) FROM bank_deposit_scan WHERE published_at IS NULL) oldest
            FROM bank_approval_control c WHERE id=1
            """,(rs,i)-> {
            var oldest=rs.getObject("oldest",LocalDateTime.class);
            Long rawAge=rs.getObject("raw_age",Long.class);
            Long wait=rawAge;
            if (oldest!=null) wait=Math.max(rawAge==null ? 0 : rawAge,Duration.between(oldest,LocalDateTime.now(SEOUL)).toSeconds());
            return new Status(rs.getBoolean("enabled"),rs.getLong("epoch"),rs.getObject("switched_at",LocalDateTime.class),
                rs.getObject("last_success",LocalDateTime.class),rs.getString("checkpoint_at"),rs.getBoolean("transfer_held") ? "TRANSFER_HELD" : rs.getString("issue"),
                rs.getInt("queue_depth"),wait);
        });
    }

    @Transactional
    public Status toggle(boolean enabled, long expectedEpoch,long admin) {
        boolean current=gate.lock();
        Status state=status();
        if(state.epoch()!=expectedEpoch) throw conflict("다른 운영자가 설정을 변경했습니다.");
        if(current!=enabled) {
            var now=LocalDateTime.now(SEOUL);
            db.update("UPDATE bank_approval_control SET enabled=?,epoch=epoch+1,switched_at=? WHERE id=1",enabled,now);
            db.update("INSERT INTO bank_approval_switch(epoch,enabled,changed_at,admin_id) VALUES (?,?,?,?)",state.epoch()+1,enabled,now,admin);
            if (!enabled) db.update("UPDATE bank_deposit_ledger SET reason='MANUAL_PERIOD' WHERE reason='NEW' AND order_id IS NULL");
        }
        return status();
    }

    private static Deposit deposit(java.sql.ResultSet rs,int i) throws java.sql.SQLException {
        return new Deposit(rs.getLong("id"),rs.getString("payer"),rs.getLong("amount_krw"),rs.getString("paid_second"),
            rs.getString("reason"),rs.getObject("order_id",Long.class));
    }
    private static LocalDateTime orderClock(String second) {
        return LocalDateTime.parse(second).atZone(SEOUL).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    // Include orders which were open at bank time even if they were decided before Kafka delivery.
    private List<Candidate> candidates(Deposit d,boolean lock) {
        var at=orderClock(d.second());
        return db.query("""
            SELECT id, requester_real_name,expected_price,status,requested_at,expires_at FROM purchase_order
            WHERE requester_real_name=? AND expected_price=? AND requested_at<? AND expires_at>?
              AND (status='PENDING' OR decided_at IS NULL OR decided_at>=?)
            ORDER BY id
            """+(lock?" FOR UPDATE":""),(rs,i)->new Candidate(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getString(4),
                rs.getObject(5,LocalDateTime.class),rs.getObject(6,LocalDateTime.class)),d.payer(),d.amount(),at.plusSeconds(1),at,at);
    }

    public List<Review> review(long after,int limit) {
        return review(after,limit,false);
    }

    public List<Review> review(long after,int limit,boolean pendingAmountsOnly) {
        var sql = "SELECT d.* FROM bank_deposit_ledger d WHERE d.order_id IS NULL AND d.id>? ";
        var parameters = new ArrayList<Object>();
        parameters.add(after);
        if (pendingAmountsOnly) {
            sql += """
                AND EXISTS (SELECT 1 FROM purchase_order p WHERE p.expected_price=d.amount_krw
                            AND p.status='PENDING' AND p.expires_at>?)
                """;
            parameters.add(LocalDateTime.now());
        }
        parameters.add(Math.min(100,Math.max(1,limit)));
        var rows=db.query(sql + " ORDER BY d.id LIMIT ?",BankApprovalService::deposit,parameters.toArray());
        var matches=new HashMap<MatchGroup,List<Candidate>>();
        return rows.stream().map(d->{
            var group=new MatchGroup(d.payer(),d.amount(),d.second());
            var orders=matches.computeIfAbsent(group,ignored->candidates(d,false));
            return new Review(d.id(),d.payer(),d.amount(),d.second(),d.reason(),orders);
        }).toList();
    }

    @Transactional
    public void process(String scanId) {
        gate.lock();
        var scan=db.queryForMap("SELECT epoch,eligible,processed_at FROM bank_deposit_scan WHERE scan_id=? FOR UPDATE",scanId);
        if(scan.get("processed_at")!=null) return;
        Status state=status();
        boolean eligible=state.issue()==null && state.enabled() && state.epoch()==((Number)scan.get("epoch")).longValue()
            && Boolean.TRUE.equals(db.queryForObject("SELECT eligible FROM bank_deposit_scan WHERE scan_id=?",Boolean.class,scanId));
        var deposits=db.query("SELECT * FROM bank_deposit_ledger WHERE scan_id=? AND order_id IS NULL ORDER BY id FOR UPDATE",
            BankApprovalService::deposit,scanId);
        Set<MatchGroup> conflictingGroups=new HashSet<>();
        for(var d:deposits) {
            var group=new MatchGroup(d.payer(),d.amount(),d.second());
            if(conflictingGroups.contains(group)) continue;
            var orders=candidates(d,true);
            if(orders.size()>1 || "IDENTICAL_ROWS".equals(d.reason())) {
                holdCandidates(orders);
                db.update("""
                    UPDATE bank_deposit_ledger SET reason=? WHERE scan_id=? AND payer=? AND amount_krw=?
                      AND paid_second=? AND order_id IS NULL
                    """,orders.size()>1?"MULTIPLE_ORDERS":"IDENTICAL_ROWS",scanId,d.payer(),d.amount(),d.second());
                conflictingGroups.add(group);
                continue;
            }
            if(!"NEW".equals(d.reason())) continue;
            if(!eligible || orders.size()!=1) { reason(d.id(),eligible?"NO_ORDER":"MANUAL_PERIOD"); continue; }
            var order=orders.get(0);
            if(!order.name().equals(d.payer()) || !"PENDING".equals(order.status()) || !order.expiresAt().isAfter(LocalDateTime.now())
                || order.requestedAt().isAfter(orderClock(d.second()))) { reason(d.id(),"ORDER_TIME_OR_STATUS"); continue; }
            var lower=order.requestedAt().atZone(ZoneId.systemDefault()).withZoneSameInstant(SEOUL).toLocalDateTime();
            int possible=db.queryForObject("""
                SELECT COUNT(*) FROM bank_deposit_ledger WHERE payer=? AND amount_krw=? AND order_id IS NULL
                  AND paid_second>=? AND paid_second<?
                """,Integer.class,d.payer(),d.amount(),SECOND.format(lower),
                    SECOND.format(order.expiresAt().atZone(ZoneId.systemDefault()).withZoneSameInstant(SEOUL)));
            int incomplete=db.queryForObject("""
                SELECT COUNT(*) FROM bank_deposit_ledger WHERE identity_incomplete=TRUE AND order_id IS NULL
                  AND amount_krw=? AND (payer='' OR payer=?) AND LEFT(paid_second,10) BETWEEN ? AND ?
                """,Integer.class,d.amount(),d.payer(),lower.toLocalDate().toString(),
                    order.expiresAt().atZone(ZoneId.systemDefault()).withZoneSameInstant(SEOUL).toLocalDate().toString());
            if(incomplete>0 || possible!=1 || db.queryForObject("SELECT COUNT(*) FROM bank_order_review WHERE order_id=?",Integer.class,order.id())>0) {
                hold(order.id()); reason(d.id(),"MANUAL_REVIEW"); continue;
            }
            link(d.id(),order.id(),null);
        }
        db.update("UPDATE bank_deposit_scan SET processed_at=? WHERE scan_id=?",LocalDateTime.now(SEOUL),scanId);
    }

    private void holdCandidates(List<Candidate> orders) {
        if (orders.isEmpty()) return;
        db.batchUpdate("INSERT INTO bank_order_review(order_id,reason) VALUES (?,'AMBIGUOUS') ON DUPLICATE KEY UPDATE order_id=order_id",
            orders,100,(ps,order)->ps.setLong(1,order.id()));
    }
    private void hold(long order) {
        db.update("INSERT INTO bank_order_review(order_id,reason) VALUES (?,'AMBIGUOUS') ON DUPLICATE KEY UPDATE order_id=order_id",order);
    }
    private void reason(long id,String why) { db.update("UPDATE bank_deposit_ledger SET reason=? WHERE id=? AND order_id IS NULL",why,id); }

    @Transactional
    public void manual(long depositId,long orderId,long admin) {
        gate.lock();
        var rows=db.query("SELECT * FROM bank_deposit_ledger WHERE id=? FOR UPDATE",BankApprovalService::deposit,depositId);
        if(rows.isEmpty()) throw conflict("입금이 없습니다.");
        var d=rows.get(0);
        if(Objects.equals(d.orderId(),orderId)) return;
        if(d.orderId()!=null) throw conflict("이미 연결된 입금입니다.");
        var order=db.queryForList("SELECT expected_price,status,expires_at FROM purchase_order WHERE id=? FOR UPDATE",orderId);
        if(order.isEmpty() || !"PENDING".equals(order.get(0).get("status"))
            || ((Number)order.get(0).get("expected_price")).longValue()!=d.amount()
            || !localTime(order.get(0).get("expires_at")).isAfter(LocalDateTime.now())) throw conflict("금액이 같은 유효한 대기 주문을 선택하세요.");
        var candidates=candidates(d,true);
        if (candidates.size()>1 || "IDENTICAL_ROWS".equals(d.reason())) {
            holdCandidates(candidates);
        }
        link(depositId,orderId,admin);
    }
    private void link(long deposit,long order,Long admin) {
        String why=admin==null?"BANK_AUTO":"BANK_MANUAL";
        if(db.update("UPDATE bank_deposit_ledger SET order_id=?,linked_at=?,reason=? WHERE id=? AND order_id IS NULL",
            order,LocalDateTime.now(SEOUL),why,deposit)!=1) throw conflict("이미 처리된 입금입니다.");
        approvals.approveMatchedPurchase(order,admin,why+":"+deposit);
    }
}
