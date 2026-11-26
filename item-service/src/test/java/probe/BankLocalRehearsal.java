package probe;

import com.comatching.common.config.kafka.KafkaEventRoute;
import com.comatching.item.infra.bankdeposit.BankScanKafka;
import com.comatching.item.infra.bankdeposit.DepositObservationController.Observation;
import com.comatching.item.infra.bankdeposit.DepositObservationPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.EmbeddedKafkaZKBroker;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** Loopback-only, test classpath. No admin routes, order creation or production configuration. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@org.springframework.boot.autoconfigure.ImportAutoConfiguration(org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration.class)
@Import({BankApprovalIT.App.class, DepositObservationPublisher.class, BankLocalRehearsal.Ledger.class})
public class BankLocalRehearsal {
    public static void main(String[] args) throws Exception {
        var broker=new EmbeddedKafkaZKBroker(1,false,1,DepositObservationPublisher.TOPIC,
            BankScanKafka.TOPIC,KafkaEventRoute.ReplayTopics.BANK_SCAN,KafkaEventRoute.BANK_SCAN.dltTopic());
        broker.brokerProperties(Map.of("listeners","PLAINTEXT://127.0.0.1:0"));
        Path folder=Path.of("build","bank-local-rehearsal").toAbsolutePath();
        Files.createDirectories(folder);
        String token=UUID.randomUUID()+"-"+UUID.randomUUID();
        try {
            broker.afterPropertiesSet();
            var app=new SpringApplication(BankLocalRehearsal.class);
            var context=app.run("--spring.config.name=bank-local-rehearsal","--server.address=127.0.0.1","--server.port=48767",
                "--spring.datasource.url=jdbc:h2:mem:rehearsal;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "--spring.datasource.username=sa","--spring.datasource.password=","--spring.jpa.hibernate.ddl-auto=create-drop",
                "--spring.sql.init.mode=always","--spring.sql.init.schema-locations=classpath:20261124_01_bank_deposit_observation.sql,classpath:20261125_01_bank_approval.sql",
                "--spring.kafka.bootstrap-servers="+broker.getBrokersAsString(),"--spring.kafka.producer.acks=all",
                "--spring.kafka.producer.value-serializer=com.comatching.common.config.kafka.EventValueSerializer",
                "--comatching.deposit-ingest.enabled=true","--comatching.deposit-ingest.token="+token,
                "--comatching.bank-probe.enabled=false","--comatching.kafka.dlt-alert-webhook-url=",
                "--logging.level.root=WARN","--logging.level.org.springframework=WARN","--logging.level.org.hibernate=WARN",
                "--logging.level.org.apache.kafka=WARN","--logging.level.kafka=WARN","--logging.level.com.comatching=WARN",
                "--spring.jpa.show-sql=false","--spring.mvc.log-request-details=false",
                "--spring.web.resources.add-mappings=false");
            Runtime.getRuntime().addShutdownHook(new Thread(()->{context.close();broker.destroy();},"bank-local-stop"));
            Path secret=Files.createTempFile(folder,"credential-", ".tmp");
            if(Files.getFileStore(secret).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(secret,PosixFilePermissions.fromString("rw-------"));
            Files.writeString(secret,token);
            Files.move(secret,folder.resolve("collector-token.txt"),StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Local rehearsal ready at http://127.0.0.1:48767/ · "+folder);
        } catch(Exception e) {broker.destroy();throw e;}
    }

    record Row(String date,String time,String payer,long won) {}
    record Capture(int count,long won,List<Row> rows) {}
    record Totals(long count,long won) {}
    record Snapshot(Capture lastPage,Totals ledger,long unpublishedRows,long completedScans,LocalDateTime lastSuccess,String issue) {}

    @RestController
    static class Ledger {
        private final JdbcTemplate db;
        private final ObjectMapper json;
        private final byte[] credential;
        Ledger(JdbcTemplate db,ObjectMapper json,@Value("${comatching.deposit-ingest.token}") String token) {
            this.db=db; this.json=json; credential=("Bearer "+token).getBytes(StandardCharsets.UTF_8);
        }
        @GetMapping(value="/",produces="text/html; charset=utf-8")
        ResponseEntity<ClassPathResource> home() {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Frame-Options","DENY").header("Referrer-Policy","no-referrer")
                .body(new ClassPathResource("bank-local-rehearsal.html"));
        }
        @GetMapping("/rehearsal/ledger")
        @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
        public ResponseEntity<Snapshot> ledger(@RequestHeader(value="Authorization",defaultValue="") String header) {
            if(!MessageDigest.isEqual(credential,header.getBytes(StandardCharsets.UTF_8)))
                return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).build();
            var last=db.queryForList("SELECT LEFT(event_id,36) FROM bank_deposit_observation ORDER BY received_at DESC,event_id DESC LIMIT 1",String.class);
            Capture captured=null;
            if(!last.isEmpty()) {
                var rows=db.query("SELECT payload FROM bank_deposit_observation WHERE event_id LIKE ?",(rs,i)->{
                    try {
                        var row=json.readValue(rs.getString(1),Observation.class);
                        return new Row(row.transactionDate().toString(),row.transactionTime(),row.counterparty(),row.amountKrw());
                    } catch(java.io.IOException e){throw new IllegalStateException("Cannot read local observation",e);}
                }, last.get(0)+":%");
                captured=new Capture(rows.size(),rows.stream().mapToLong(Row::won).sum(),rows);
            }
            var totals=db.queryForObject("SELECT COUNT(*),COALESCE(SUM(amount_krw),0) FROM bank_deposit_ledger",
                (rs,i)->new Totals(rs.getLong(1),rs.getLong(2)));
            long pending=db.queryForObject("SELECT COUNT(*) FROM bank_deposit_observation WHERE published_at IS NULL",Long.class);
            long scans=db.queryForObject("SELECT COUNT(*) FROM bank_deposit_scan WHERE processed_at IS NOT NULL AND issue IS NULL",Long.class);
            var page=captured;
            var result=db.queryForObject("SELECT last_success,issue FROM bank_approval_control WHERE id=1",
                (rs,i)->new Snapshot(page,totals,pending,scans,rs.getObject(1,LocalDateTime.class),rs.getString(2)));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        }
    }
}
