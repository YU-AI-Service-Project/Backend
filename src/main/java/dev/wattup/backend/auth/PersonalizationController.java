package dev.wattup.backend.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;

@RestController
@RequestMapping("/api/users/me")
class PersonalizationController {
    private final Favorites favorites;
    private final SearchPreferences preferences;

    PersonalizationController(Favorites favorites, SearchPreferences preferences) {
        this.favorites = favorites;
        this.preferences = preferences;
    }

    @GetMapping("/favorites")
    Favorites.Page favorites(@AuthenticationPrincipal Jwt access, @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        return favorites.page(access, page, size);
    }

    @PostMapping("/favorites")
    ResponseEntity<Favorites.Saved> add(@AuthenticationPrincipal Jwt access, @RequestBody Add input) {
        return ResponseEntity.status(201).body(favorites.add(access, input.stationId));
    }

    @DeleteMapping("/favorites/{stationId}")
    ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt access, @PathVariable String stationId) {
        favorites.remove(access, stationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/favorites/import")
    Favorites.ImportResult merge(@AuthenticationPrincipal Jwt access, @RequestBody Import input) {
        return favorites.merge(access, input.stationIds, input.mergeMode);
    }

    @GetMapping("/preferences")
    SearchPreferences.Preference preferences(@AuthenticationPrincipal Jwt access) {
        return preferences.get(access);
    }

    @PatchMapping("/preferences")
    SearchPreferences.Preference patch(@AuthenticationPrincipal Jwt access, @RequestBody JsonNode input) {
        return preferences.patch(access, input);
    }

    record Add(String stationId) {}
    record Import(List<String> stationIds, String mergeMode) {}
}
