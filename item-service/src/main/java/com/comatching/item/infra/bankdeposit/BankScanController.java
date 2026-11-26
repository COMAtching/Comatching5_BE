package com.comatching.item.infra.bankdeposit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/collector/deposit-observations")
@CrossOrigin(originPatterns="chrome-extension://*",allowedHeaders={"Authorization","Content-Type"},methods=RequestMethod.POST,allowCredentials="false")
@ConditionalOnProperty(name="comatching.deposit-ingest.enabled",havingValue="true")
public class BankScanController {
    private final CollectorCredential credential;
    private final BankScanService scans;
    private final JdbcTemplate db;
    public BankScanController(@Value("${comatching.deposit-ingest.token}") String token,BankScanService scans,JdbcTemplate db) {
        this.credential=new CollectorCredential(token); this.scans=scans; this.db=db;
    }
    public record Health(@PositiveOrZero int queueDepth,@Pattern(regexp="COVERAGE_GAP|READ_FAILED|TRANSFER_HELD|STORAGE_FULL") String issue) {}
    @PostMapping("/scans")
    public ResponseEntity<BankScanService.Receipt> complete(@RequestHeader(value="Authorization",defaultValue="") String header,
                                                             @Valid @RequestBody BankScanService.Scan scan) {
        credential.check(header); return ResponseEntity.accepted().body(scans.accept(scan));
    }
    public record Cursor(String accountKey,String checkpoint,String activationFloor) {}
    @PostMapping("/health")
    public ResponseEntity<Cursor> health(@RequestHeader(value="Authorization",defaultValue="") String header,@Valid @RequestBody Health health) {
        credential.check(header);
        if (health.issue()==null || "TRANSFER_HELD".equals(health.issue())) {
            db.update("UPDATE bank_approval_control SET queue_depth=?,transfer_held=? WHERE id=1",
                health.queueDepth(),health.issue()!=null);
        } else {
            db.update("UPDATE bank_approval_control SET queue_depth=?,issue=?,last_problem_at=? WHERE id=1",
                health.queueDepth(),health.issue(),java.time.LocalDateTime.now(BankScanService.SEOUL));
        }
        var cursor=db.queryForObject("SELECT account_key,checkpoint_at,switched_at,enabled FROM bank_approval_control WHERE id=1",
            (rs,i)->{
                var switched=rs.getObject("switched_at",java.time.LocalDateTime.class);
                return new Cursor(rs.getString("account_key"),rs.getString("checkpoint_at"),
                    rs.getBoolean("enabled") && switched!=null ? BankScanService.SECOND.format(switched) : null);
            });
        return ResponseEntity.ok(cursor);
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Void> reject(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).build(); }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }
}
