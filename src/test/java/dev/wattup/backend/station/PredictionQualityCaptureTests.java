package dev.wattup.backend.station;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class PredictionQualityCaptureTests {
    @Test
    void boundsTheBacklogWithoutBlockingRequestsWhileTheWriterWaitsForTheDatabase() throws Exception {
        var repository = mock(PredictionQualityRepository.class);
        var capture = new PredictionQualityCapture(repository);
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var accepted = List.of(mock(PredictionQualityRepository.Sample.class));
        var excess = List.of(mock(PredictionQualityRepository.Sample.class));
        doAnswer(call -> {
            writing.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(repository).capture(accepted);
        try {
            capture.enqueue(accepted);
            assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue();
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                for (int i = 0; i < 32; i++) {
                    capture.enqueue(accepted);
                }
                capture.enqueue(excess);
            });
            release.countDown();
            verify(repository, timeout(2000).times(33)).capture(accepted);
            verify(repository, never()).capture(excess);
        } finally {
            release.countDown();
            capture.stopWriter();
        }
    }
}
