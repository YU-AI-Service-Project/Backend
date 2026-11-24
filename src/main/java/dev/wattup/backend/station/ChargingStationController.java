package dev.wattup.backend.station;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/charging-stations")
class ChargingStationController {
    private final ChargingStationService service;

    ChargingStationController(ChargingStationService service) {
        this.service = service;
    }

    @GetMapping
    StationViews.SearchResult nearby(
            @RequestParam double latitude, @RequestParam double longitude,
            @RequestParam(defaultValue = "3000") int radiusM, @RequestParam(defaultValue = "30") int limit,
            @RequestParam(defaultValue = "ALL") String profile, @RequestParam(required = false) List<String> chargerTypes,
            @RequestParam(required = false) BigDecimal minOutputKw,
            @RequestParam(defaultValue = "false") boolean availableOnly,
            @RequestParam(defaultValue = "DISTANCE") String sort) {
        var filter = ChargerFilter.forSearch(profile, chargerTypes, minOutputKw);
        return service.search(StationSearch.of(latitude, longitude, radiusM, limit, availableOnly, sort, filter));
    }

    @GetMapping("/{stationId}")
    StationViews.StationDetails details(@PathVariable String stationId, @RequestParam(defaultValue = "ALL") String profile) {
        return service.details(stationId, ChargerFilter.forStation(profile, null, null));
    }

    @GetMapping("/{stationId}/chargers")
    StationViews.ChargerList chargers(@PathVariable String stationId, @RequestParam(defaultValue = "ALL") String profile,
                                      @RequestParam(required = false) String typeCode,
                                      @RequestParam(required = false) BigDecimal minOutputKw) {
        return service.chargers(stationId, ChargerFilter.forStation(profile, typeCode, minOutputKw));
    }

    @GetMapping("/{stationId}/status")
    StationViews.StationStatus status(@PathVariable String stationId, @RequestParam(defaultValue = "ALL") String profile) {
        return service.status(stationId, ChargerFilter.forStation(profile, null, null));
    }
}
