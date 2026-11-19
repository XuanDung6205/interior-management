package org.example.eshop.commerce;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** No local override: Maven must disable background workers across every cached test context. */
@SpringBootTest(classes=CommerceApplication.class)
@ActiveProfiles("commerce")
@EnabledIfEnvironmentVariable(named="TV3_TEST_DB_URL",matches=".*furniture_shop_test_.*")
class JobSchedulingDefaultTest {
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("TV3_TEST_DB_URL"));r.add("spring.datasource.username",()->System.getenv().getOrDefault("DB_USERNAME","root"));r.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));}
    @Autowired Environment environment;
    @Test void allMavenIntegrationContextsDefaultToDisabledWorkers(){assertFalse(environment.getProperty("commerce.jobs.enabled",Boolean.class,true),"Background workers must not mutate fixtures from cached integration contexts");}
}
