package com.comatching.item.infra.bankdeposit;

import com.comatching.item.infra.bankdeposit.DepositObservationController.Observation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name="comatching.deposit-ingest.enabled", havingValue="true")
public class BankScanService {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");
    public record Page(@Min(1) int number, @NotNull UUID captureId, @Min(0) @Max(20000) int totalCredits, @Min(0) int credits, @NotNull @Size(max=20000) List<@NotBlank @Size(max=96) String> ids) {}
    public record Scan(@NotNull UUID scanId, @NotBlank @Pattern(regexp="[0-9a-f]{64}") String accountKey,
                       @NotNull OffsetDateTime startedAt, @NotNull OffsetDateTime finishedAt,
                       @NotNull LocalDateTime fromSecond, @NotNull LocalDateTime throughSecond,
                       @NotNull @Size(min=1,max=250) List<@NotNull @Valid Page> pages) {}
    public record Receipt(UUID scanId, String checkpoint, String issue) {}
    record Group(String key, String payer, long amount, String second, boolean precise) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final BankApprovalGate gate;

    static ResponseStatusException conflict(String why) { return new ResponseStatusException(HttpStatus.CONFLICT, why); }
    static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String encode(Object o) {
        try { return json.writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    @Transactional
    public Receipt accept(Scan scan) {
        boolean enabled = gate.lock();
        String id = scan.scanId().toString(), digest = hash(encode(scan));
        var replay = db.query("SELECT digest,checkpoint_at,issue FROM bank_deposit_scan WHERE scan_id=?", (rs,i) -> {
            if (!digest.equals(rs.getString(1))) throw conflict("scan ID conflict");
            return new Receipt(scan.scanId(),rs.getString(2),rs.getString(3));
        }, id);
        if (!replay.isEmpty()) return replay.get(0);
        var control = db.queryForMap("SELECT account_key,checkpoint_at,epoch,switched_at,last_scan_at,last_problem_at,transfer_held FROM bank_approval_control WHERE id=1");
        String account = (String)control.get("account_key");
        if (account != null && !account.equals(scan.accountKey())) throw conflict("collector account changed");
        var start = scan.startedAt().atZoneSameInstant(SEOUL).toLocalDateTime();
        var end = scan.finishedAt().atZoneSameInstant(SEOUL).toLocalDateTime();
        var sealed=start.withNano(0).minusSeconds(1);
        if (scan.throughSecond().isAfter(sealed)) throw conflict("scan includes an open bank second");
        if (end.isBefore(start) || end.isAfter(LocalDateTime.now(SEOUL).plusSeconds(5)) ||
            scan.throughSecond().isBefore(scan.fromSecond()) || scan.throughSecond().isAfter(end.plusSeconds(1)) ||
            scan.fromSecond().getNano()!=0 || scan.throughSecond().getNano()!=0) throw conflict("invalid scan interval");
        String low = SECOND.format(scan.fromSecond()), high = SECOND.format(scan.throughSecond());
        String checkpoint = (String)control.get("checkpoint_at");
        var switchAt = localTime(control.get("switched_at"));
        var lastScan = localTime(control.get("last_scan_at"));
        var lastProblem=localTime(control.get("last_problem_at"));
        String issue = lastProblem!=null && start.isBefore(lastProblem) ? "COVERAGE_GAP" : null;
        if (lastScan != null && start.isBefore(lastScan)) issue = "STALE_SCAN";
        if (checkpoint != null && (low.compareTo(checkpoint)>0 || high.compareTo(checkpoint)<0)) issue = "COVERAGE_GAP";

        Set<UUID> captures = new HashSet<>();
        Map<Group,Integer> counts = new LinkedHashMap<>();
        int totalCredits = 0;
        for (int i=0;i<scan.pages().size();i++) {
            Page page=scan.pages().get(i);
            totalCredits += page.totalCredits();
            if (totalCredits > 20000 || page.number()!=i+1 || !captures.add(page.captureId()) ||
                page.credits()!=page.ids().size() || page.credits()>page.totalCredits())
                throw conflict("page manifest mismatch");
            if(db.queryForObject("SELECT COUNT(*) FROM bank_scan_capture WHERE capture_id=?",Integer.class,page.captureId().toString())>0)
                throw conflict("capture already belongs to a scan");
            var observations=db.query("SELECT payload FROM bank_deposit_observation WHERE event_id LIKE ?", (rs,n)-> {
                try { return json.readValue(rs.getString(1),Observation.class); }
                catch(JsonProcessingException e){ throw new IllegalStateException(e); }
            },page.captureId()+":%");
            if (observations.size()<page.totalCredits()) throw new ResponseStatusException(HttpStatus.TOO_EARLY,"observations pending");
            if (observations.size()!=page.totalCredits()) throw conflict("page observation count mismatch");
            Set<String> included = new HashSet<>();
            for (var row:observations) {
                if (row.capturedAt().isBefore(scan.startedAt()) || row.capturedAt().isAfter(scan.finishedAt())) throw conflict("observation outside scan");
                String time=row.transactionTime(), payer=Objects.toString(row.counterparty(),"").strip();
                var at=row.transactionDate().atTime(time==null ? LocalTime.MIDNIGHT : LocalTime.parse(time));
                String second=SECOND.format(at);
                if (time!=null && time.length()==8) {
                    if (at.isAfter(sealed)) continue;
                    if (second.compareTo(high)>0) throw conflict("observation above scan head");
                    if (second.compareTo(low)<0) continue;
                }
                included.add(row.eventId());
                var group=new Group(hash(encode(List.of(scan.accountKey(),row.transactionDate(),Objects.toString(time,""),payer,row.amountKrw()))),
                    payer,row.amountKrw(),second,time!=null && time.length()==8 && !payer.isBlank());
                counts.merge(group,1,Integer::sum);
            }
            db.update("INSERT INTO bank_scan_capture(capture_id,scan_id) VALUES(?,?)",page.captureId().toString(),id);
            if (included.size()!=page.credits() || !included.equals(new HashSet<>(page.ids())))
                throw conflict("page observation IDs mismatch");
        }
        var old=new HashMap<String,Integer>();
        db.query("SELECT fingerprint,COUNT(*) cnt FROM bank_deposit_ledger WHERE paid_second BETWEEN ? AND ? GROUP BY fingerprint",
            (org.springframework.jdbc.core.RowCallbackHandler)rs->old.put(rs.getString(1),rs.getInt(2)),low,high);
        var remaining=new HashMap<>(old);
        for(var entry:counts.entrySet()) {
            Integer prior=remaining.remove(entry.getKey().key());
            if(prior!=null && prior>entry.getValue()) issue="COUNT_CONFLICT";
        }
        if(!remaining.isEmpty()) issue="COUNT_CONFLICT";
        boolean active=enabled && !Boolean.TRUE.equals(control.get("transfer_held")) && switchAt!=null && start.isAfter(switchAt) && issue==null;
        for(var entry:counts.entrySet()) {
            Group g=entry.getKey();
            String reason=issue!=null ? issue : entry.getValue()>1 ? "IDENTICAL_ROWS" : !g.precise() ? "INCOMPLETE_IDENTITY" :
                !active || !LocalDateTime.parse(g.second()).isAfter(switchAt) ? "MANUAL_PERIOD" : "NEW";
            if (entry.getValue()>1 || issue!=null)
                db.update("UPDATE bank_deposit_ledger SET reason=? WHERE fingerprint=? AND order_id IS NULL",reason,g.key());
            var slots=java.util.stream.IntStream.rangeClosed(old.getOrDefault(g.key(),0)+1,entry.getValue()).boxed().toList();
            if (!slots.isEmpty()) db.batchUpdate("""
                    INSERT INTO bank_deposit_ledger(fingerprint,occurrence_no,payer,amount_krw,paid_second,scan_id,reason,identity_incomplete)
                    VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE fingerprint=fingerprint
                    """,slots,100,(statement,slot)->{
                    statement.setString(1,g.key());
                    statement.setInt(2,slot);
                    statement.setString(3,g.payer());
                    statement.setLong(4,g.amount());
                    statement.setString(5,g.second());
                    statement.setString(6,id);
                    statement.setString(7,reason);
                    statement.setBoolean(8,!g.precise());
                });
        }
        if(issue!=null) db.update("UPDATE bank_deposit_ledger SET reason=? WHERE paid_second BETWEEN ? AND ? AND order_id IS NULL",issue,low,high);
        String next=issue==null ? high : checkpoint;
        var now=LocalDateTime.now(SEOUL);
        db.update("""
            INSERT INTO bank_deposit_scan(scan_id,digest,epoch,eligible,checkpoint_at,issue,received_at,
                coverage_from,coverage_through,page_count,credit_count) VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,id,digest,control.get("epoch"),active,next,issue,now,low,high,scan.pages().size(),
                counts.values().stream().mapToInt(Integer::intValue).sum());
        if(issue==null) db.update("UPDATE bank_approval_control SET account_key=?,checkpoint_at=?,last_scan_at=?,last_success=?,issue=NULL WHERE id=1",scan.accountKey(),next,end,end);
        else db.update("UPDATE bank_approval_control SET issue=? WHERE id=1",issue);
        return new Receipt(scan.scanId(),next,issue);
    }
    static LocalDateTime localTime(Object value) {
        if(value==null) return null;
        if(value instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return (LocalDateTime)value;
    }
}
