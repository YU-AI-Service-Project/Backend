package dev.wattup.backend.station;

import java.math.BigDecimal;
import java.util.List;

record ChargerFilter(Profile profile, List<String> typeCodes, BigDecimal minOutputKw) {
    private static final BigDecimal MAX_OUTPUT_KW = new BigDecimal("999999.99");

    static ChargerFilter forSearch(String profile, List<String> typeCodes, BigDecimal minOutputKw) {
        Profile selected = parseProfile(profile, true);
        List<String> types = typeCodes == null ? List.of() : typeCodes.stream().distinct().toList();
        if (types.stream().anyMatch(code -> !validTypeCode(code))) {
            throw StationApiError.unsupported("UNSUPPORTED_CHARGER_FILTER", "chargerTypes");
        }
        return new ChargerFilter(selected, types, validateOutput(minOutputKw));
    }

    static ChargerFilter forStation(String profile, String typeCode, BigDecimal minOutputKw) {
        Profile selected = parseProfile(profile, false);
        if (typeCode != null && !validTypeCode(typeCode)) {
            throw StationApiError.binding("INVALID_REQUEST", "typeCode", "INVALID_FORMAT");
        }
        return new ChargerFilter(selected, typeCode == null ? List.of() : List.of(typeCode),
                validateOutput(minOutputKw));
    }

    private static Profile parseProfile(String profile, boolean search) {
        try {
            return Profile.valueOf(profile);
        } catch (IllegalArgumentException e) {
            throw search
                    ? StationApiError.unsupported("UNSUPPORTED_CHARGER_FILTER", "profile")
                    : StationApiError.binding("INVALID_REQUEST", "profile", "UNSUPPORTED");
        }
    }

    private static boolean validTypeCode(String typeCode) {
        return typeCode.matches("[0-9]{2}");
    }

    private static BigDecimal validateOutput(BigDecimal minOutputKw) {
        if (minOutputKw != null && (minOutputKw.scale() < -6 || minOutputKw.scale() > 18
                || minOutputKw.signum() < 0 || minOutputKw.compareTo(MAX_OUTPUT_KW) > 0)) {
            throw StationApiError.invalid("INVALID_REQUEST", "minOutputKw");
        }
        return minOutputKw;
    }

    enum Profile {
        ALL(List.of()),
        AC_SLOW(List.of("02")),
        DC_COMBO(List.of("04", "05", "06", "08", "10"));

        final List<String> typeCodes;

        Profile(List<String> typeCodes) {
            this.typeCodes = typeCodes;
        }
    }
}
