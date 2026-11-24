package dev.wattup.backend.collection;

import java.time.LocalDateTime;

record ObservedReading(KecoReading reading, LocalDateTime observedAt) {
}
