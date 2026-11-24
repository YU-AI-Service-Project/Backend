package dev.wattup.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        classes = WattUpApplication.class,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "wattup.collection.enabled=false"
        })
class WattUpApplicationTests {

    @MockitoBean
    private NamedParameterJdbcTemplate jdbc;

    @MockitoBean
    private JdbcTemplate authJdbc;

    @Test
    void contextLoads() {
    }
}
