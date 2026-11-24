package dev.wattup.backend.collection;

enum KecoEndpoint {
    FULL_INVENTORY("getChargerInfo", "STATUS_FULL"),
    CHANGED_STATUS("getChargerStatus", "STATUS_DELTA");

    final String path;
    final String runType;

    KecoEndpoint(String path, String runType) {
        this.path = path;
        this.runType = runType;
    }
}
