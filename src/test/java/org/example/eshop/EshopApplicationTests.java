package org.example.eshop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes=EshopApplication.class)
@EnabledIfEnvironmentVariable(named="LEGACY_TEST_DB_URL", matches=".*furniture_shop_test_legacy_.*")
class EshopApplicationTests {
    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void disposableLegacySchema(DynamicPropertyRegistry registry) {
        String url = System.getenv("LEGACY_TEST_DB_URL");
        if (url == null || !url.matches("jdbc:mariadb://(?:localhost|127\\.0\\.0\\.1):[0-9]{1,5}/furniture_shop_test_legacy_[a-z0-9_]+")) {
            throw new IllegalStateException("Legacy integration requires an explicit local furniture_shop_test_legacy_* database.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.hikari.jdbc-url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("DB_USERNAME", "root"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        // Only this guarded legacy context creates its own entity schema.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.ai.vectorstore.mariadb.initialize-schema", () -> "false");
        registry.add("spring.jpa.show-sql", () -> "false");
    }

    @Test
    void contextLoads() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).startsWith("furniture_shop_test_legacy_");
        }
    }

}
