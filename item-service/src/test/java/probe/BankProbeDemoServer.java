package probe;

import com.comatching.common.exception.handler.GlobalExceptionHandler;
import com.comatching.item.infra.bankprobe.SyntheticDepositController;
import com.comatching.item.infra.bankprobe.SyntheticDepositStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Path;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

// Test runtime launcher for the actual item-service receiver, with its own file database.
@Configuration(proxyBeanMethods = false)
@Import({SyntheticDepositController.class, SyntheticDepositStore.class, GlobalExceptionHandler.class})
@ImportAutoConfiguration({
    ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
    DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class, ValidationAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class
})
public class BankProbeDemoServer {
    public static void main(String[] args) throws Exception {
        boolean verify = args.length == 1 && "verify".equals(args[0]);
        var app = new SpringApplication(BankProbeDemoServer.class);
        app.setDefaultProperties(Map.of(
            "spring.config.name", "bank-probe-demo",
            "server.address", "127.0.0.1",
            "server.port", "48766",
            "spring.datasource.url", "jdbc:h2:file:./build/bank-probe/receipts;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
            "comatching.bank-probe.enabled", "true"
        ));
        if (!verify) {
            app.run(args);
            return;
        }
        // Disposable verification DB, port and credential; do not load operator settings.
        String token = UUID.randomUUID().toString();
        try (var context = (ServletWebServerApplicationContext) app.run(
            "--spring.config.name=bank-probe-demo", "--spring.profiles.active=bank-probe-verification",
            "--server.address=127.0.0.1", "--server.port=0",
            "--spring.datasource.url=jdbc:h2:mem:syncverify;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
            "--comatching.bank-probe.enabled=true", "--comatching.bank-probe.token=" + token)) {
            var verifier = new ProcessBuilder("node", "scripts/verify-sync.mjs")
                .directory(Path.of("../tools/bank-transaction-probe").toFile()).inheritIO();
            verifier.environment().put("BANK_PROBE_TOKEN", token);
            verifier.environment().put("BANK_PROBE_URL",
                "http://127.0.0.1:" + context.getWebServer().getPort() + "/probe/synthetic-deposits");
            if (verifier.start().waitFor() != 0) throw new IllegalStateException("Synthetic sync verification failed");
        }
    }
}
