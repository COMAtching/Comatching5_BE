package com.comatching.item.infra.bankdeposit;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/collector/deposit-observations")
@CrossOrigin(originPatterns = "chrome-extension://*", allowedHeaders = {"Authorization", "Content-Type"},
        methods = RequestMethod.POST, allowCredentials = "false")
@ConditionalOnProperty(name = "comatching.deposit-ingest.enabled", havingValue = "true")
public class DepositObservationController {
    public record Observation(
            @NotNull @Pattern(regexp = "[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}(?::[0-9]{1,10}){3}") String eventId,
            @NotBlank @Size(max = 60) String accountAlias,
            @NotNull OffsetDateTime capturedAt,
            @NotNull LocalDate transactionDate,
            @Pattern(regexp = "(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?") String transactionTime,
            @NotNull @Pattern(regexp = "CREDIT") String direction,
            @Positive @Max(9_007_199_254_740_991L) long amountKrw,
            @Size(max = 256) String counterparty,
            @Size(max = 512) String description,
            @Size(max = 128) String bankTransactionId) {}
    public record Batch(@NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Observation> observations) {}
    public record Receipt(List<String> acceptedIds) {}

    private final DepositObservationStore store;
    private final CollectorCredential credential;

    public DepositObservationController(DepositObservationStore store, @Value("${comatching.deposit-ingest.token}") String token) {
        this.credential = new CollectorCredential(token);
        this.store = store;
    }

    @PostMapping
    public ResponseEntity<Receipt> receive(@RequestHeader(value = "Authorization", defaultValue = "") String header,
                                           @Valid @RequestBody Batch batch) {
        credential.check(header);
        var ids = batch.observations().stream().map(Observation::eventId).toList();
        if (new HashSet<>(ids).size() != ids.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        store.accept(batch.observations());
        return ResponseEntity.accepted().body(new Receipt(ids));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Void> reject(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).build();
    }

    // Keep bank field values out of validation error logs and responses.
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }
}
