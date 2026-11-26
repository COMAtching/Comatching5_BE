package com.comatching.item.infra.bankdeposit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name="comatching.deposit-ingest.enabled",havingValue="true")
public class BankCollectorMetrics {
    private final BankApprovalService service;
    private final AtomicLong age, gap, queue, unpublished;
    public BankCollectorMetrics(MeterRegistry meters,BankApprovalService service) {
        this.service=service;
        age=meters.gauge("bank.collector.silence.seconds",new AtomicLong());
        gap=meters.gauge("bank.collector.issue",new AtomicLong());
        queue=meters.gauge("bank.collector.queue.batches",new AtomicLong());
        unpublished=meters.gauge("bank.collector.publish.wait.seconds",new AtomicLong());
    }
    @Scheduled(fixedDelay=15000)
    public void sample() {
        var s=service.status();
        var since=s.lastSuccess();
        if(since==null || (s.switchedAt()!=null && since.isBefore(s.switchedAt()))) since=s.switchedAt();
        age.set(!s.enabled() || since==null ? 0 : Math.max(0,Duration.between(since,LocalDateTime.now(BankScanService.SEOUL)).toSeconds()));
        gap.set(s.enabled() && s.issue()!=null?1:0);
        queue.set(s.queueDepth()); unpublished.set(s.oldestPublishWaitSeconds()==null?0:s.oldestPublishWaitSeconds());
    }
}
