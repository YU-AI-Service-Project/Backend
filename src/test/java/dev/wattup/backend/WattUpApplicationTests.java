package dev.wattup.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = WattUpApplication.class,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "wattup.collection.enabled=false"
        })
class WattUpApplicationTests {

    @Test
    void contextLoads() {
    }
}
