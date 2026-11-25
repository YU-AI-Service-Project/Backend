package dev.wattup.backend.collection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "wattup.collection.enabled", havingValue = "true")
@EnableScheduling
class KecoCollectionConfiguration {
    @Bean
    KecoCollectionSchedule kecoCollectionSchedule(
            JdbcTemplate jdbc, PlatformTransactionManager transactions, JsonMapper json,
            @Value("${wattup.collection.service-key}") String key,
            @Value("${wattup.collection.region}") String region,
            @Value("${wattup.collection.page-size}") int pageSize,
            @Value("${wattup.collection.daily-limit}") int dailyLimit,
            @Value("${wattup.internal.manual-collection-enabled:false}") boolean manualEnabled) {
        var settings = new KecoCollectionSettings(key, region, pageSize, dailyLimit);
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var api = new KecoApiClient(http, json, URI.create("https://apis.data.go.kr/B552584/EvCharger/"),
                Duration.ofSeconds(15));
        var runs = new CollectionRunRepository(jdbc);
        var collector = new KecoCollector(api, runs, new ChargerCollectionRepository(jdbc),
                transactions, settings, Clock.systemUTC());
        return new KecoCollectionSchedule(collector, runs, region, manualEnabled);
    }
}
