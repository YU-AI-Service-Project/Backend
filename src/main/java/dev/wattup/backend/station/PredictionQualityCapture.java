package dev.wattup.backend.station;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "wattup.prediction.quality-capture-enabled", havingValue = "true")
@EnableScheduling
public class PredictionQualityCapture {
    private static final Logger log = LoggerFactory.getLogger(PredictionQualityCapture.class);
    private final PredictionQualityRepository samples;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().daemon().name("eta-quality-writer").factory(),
            new ThreadPoolExecutor.DiscardPolicy());

    PredictionQualityCapture(PredictionQualityRepository samples) {
        this.samples = samples;
    }

    public void enqueue(List<PredictionQualityRepository.Sample> batch) {
        if (!batch.isEmpty()) {
            writer.execute(() -> {
                try {
                    samples.capture(batch);
                } catch (DataAccessException | TransactionException e) {
                    log.warn("Could not store ETA evaluation samples ({})", e.getClass().getSimpleName());
                }
            });
        }
    }

    @Scheduled(cron = "0 17 * * * *", zone = "Asia/Seoul")
    void removeExpiredSamples() {
        samples.prune(Instant.now().minus(90, ChronoUnit.DAYS));
    }

    @PreDestroy
    void stopWriter() {
        writer.shutdownNow();
    }
}
