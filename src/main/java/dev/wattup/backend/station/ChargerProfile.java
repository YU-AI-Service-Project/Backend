package dev.wattup.backend.station;

import java.util.List;

public enum ChargerProfile {
    ALL(List.of()),
    AC_SLOW(List.of("02")),
    DC_COMBO(List.of("04", "05", "06", "08", "10"));

    final List<String> typeCodes;

    ChargerProfile(List<String> typeCodes) {
        this.typeCodes = typeCodes;
    }

    public boolean includes(String typeCode) {
        return typeCodes.isEmpty() || typeCode != null && typeCodes.contains(typeCode);
    }
}
