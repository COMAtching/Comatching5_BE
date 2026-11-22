package com.comatching.user.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

public abstract class OutboxMySqlSupport {
    protected static final MySQLContainer<?> MYSQL;
    static {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("backend root missing");
        MYSQL = new MySQLContainer<>("mysql:8.0").withDatabaseName("comatching_user");
        MYSQL.start();
        try (var connection = MYSQL.createConnection("")) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(root.resolve("database/init/03-create-member-outbox.sql")));
        } catch (SQLException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
