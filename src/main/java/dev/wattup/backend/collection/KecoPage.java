package dev.wattup.backend.collection;

import java.util.List;

record KecoPage(int pageNumber, int pageSize, int totalCount, List<KecoReading> readings) {
}
