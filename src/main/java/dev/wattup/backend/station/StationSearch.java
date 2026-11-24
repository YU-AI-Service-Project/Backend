package dev.wattup.backend.station;

record StationSearch(double latitude, double longitude, int radiusM, int limit, boolean availableOnly,
                     ChargerFilter filter) {
    static final double EARTH_RADIUS_M = 6371008.8;

    static StationSearch of(double latitude, double longitude, int radiusM, int limit, boolean availableOnly,
                            String sort, ChargerFilter filter) {
        validateCoordinate(latitude, -90, 90, "latitude");
        validateCoordinate(longitude, -180, 180, "longitude");
        if (radiusM < 100 || radiusM > 10000) {
            throw StationApiError.invalid("INVALID_RADIUS", "radiusM");
        }
        if (limit < 1 || limit > 100) {
            throw StationApiError.invalid("INVALID_REQUEST", "limit");
        }
        if (!"DISTANCE".equals(sort)) {
            throw StationApiError.binding("INVALID_REQUEST", "sort", "UNSUPPORTED");
        }
        return new StationSearch(latitude, longitude, radiusM, limit, availableOnly, filter);
    }

    double latitudeDelta() {
        return Math.toDegrees(radiusM / EARTH_RADIUS_M);
    }

    private static void validateCoordinate(double coordinate, int minimum, int maximum, String field) {
        if (!Double.isFinite(coordinate) || coordinate < minimum || coordinate > maximum) {
            throw StationApiError.invalid("INVALID_COORDINATES", field);
        }
    }
}
