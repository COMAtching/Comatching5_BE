package com.comatching.item.infra.bankprobe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/probe/synthetic-deposits")
@ConditionalOnProperty(name = "comatching.bank-probe.enabled", havingValue = "true")
@Validated
public class SyntheticDepositController {
    static final int PAGE_SIZE = 200;
    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public record Deposit(
            @Positive @Max(MAX_SAFE_INTEGER) long sequence,
            @NotBlank @Pattern(regexp = "CREDIT") String direction,
            @Positive @Max(MAX_SAFE_INTEGER) long amountKrw,
            @NotNull OffsetDateTime occurredAt,
            @NotBlank @Size(max = 80) String description
    ) {
    }

    public record Batch(
            @NotBlank @Pattern(regexp = "synthetic_demo") String dataKind,
            @NotNull UUID streamId,
            @NotNull @Size(min = 1, max = PAGE_SIZE) List<@NotNull @Valid Deposit> deposits
    ) {
    }

    public record Receipt(UUID streamId, List<Long> sequences) {
    }

    private final byte[] authorization;
    private final SyntheticDepositStore store;

    public SyntheticDepositController(@Value("${comatching.bank-probe.token:${BANK_PROBE_TOKEN:}}") String token,
                                      SyntheticDepositStore store) {
        if (token.isBlank() || token.length() < 32) {
            throw new IllegalArgumentException("Set a dedicated bank probe token of at least 32 characters");
        }
        this.authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.store = store;
    }

    @PostMapping
    public Receipt receive(@RequestHeader(value = "Authorization", defaultValue = "") String authorization,
                           @Valid @RequestBody Batch batch) {
        authenticate(authorization);
        store.accept(batch);
        return new Receipt(batch.streamId(), batch.deposits().stream().map(Deposit::sequence).toList());
    }

    @GetMapping("/{streamId}")
    public List<Deposit> read(@RequestHeader(value = "Authorization", defaultValue = "") String authorization,
                              @PathVariable UUID streamId, @RequestParam(defaultValue = "0") long after) {
        authenticate(authorization);
        return store.read(streamId, after);
    }

    private void authenticate(String supplied) {
        if (!MessageDigest.isEqual(authorization, supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Void> reject(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).build();
    }
}
