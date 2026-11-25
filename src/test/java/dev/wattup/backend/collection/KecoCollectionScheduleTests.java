package dev.wattup.backend.collection;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KecoCollectionScheduleTests {
    private final KecoCollector collector = mock(KecoCollector.class);
    private final CollectionRunRepository runs = mock(CollectionRunRepository.class);
    private final KecoCollectionSchedule schedule = new KecoCollectionSchedule(collector, runs, "27", false);

    @Test
    void processesPersistedManualRequestsOnlyWhenEnabled() {
        schedule.runManualRequests();
        verifyNoInteractions(collector);
        new KecoCollectionSchedule(collector, runs, "27", true).runManualRequests();
        verify(collector).collectQueued();
    }

    @Test
    void waitsForTheRegionsFirstCompleteSnapshotBeforeCollectingDeltaStatus() {
        schedule.refreshChangedStatuses();
        verifyNoInteractions(collector);

        when(runs.hasFullSnapshot("27")).thenReturn(true);
        schedule.refreshChangedStatuses();
        verify(collector).collect(KecoEndpoint.CHANGED_STATUS);
    }

    @Test
    void collectsInventoryAndFullStatusTogether() {
        schedule.refreshFullSnapshot();
        verify(collector).collect(KecoEndpoint.FULL_INVENTORY);
    }
}
