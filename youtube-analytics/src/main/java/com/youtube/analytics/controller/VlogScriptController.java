package com.youtube.analytics.controller;

import com.youtube.analytics.service.vlogscript.VlogScriptStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Authenticated API for the destination/trip -> episode -> script-section library
 * used by VlogScript Studio. The owner is always derived from Google OAuth.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/youtube-analytics/v1/vlog-trips")
public class VlogScriptController {
    private final VlogScriptStore store;

    @GetMapping
    public Map<String, Object> listTrips(@AuthenticationPrincipal OAuth2User principal) {
        return Map.of("trips", store.listTrips(ownerId(principal)));
    }

    @GetMapping("/{tripId}")
    public Map<String, Object> getTrip(@AuthenticationPrincipal OAuth2User principal,
                                       @PathVariable String tripId) {
        return store.getTrip(ownerId(principal), tripId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found."));
    }

    /**
     * Merge/upsert trips from the browser's local script library. Trips omitted
     * from this payload are left untouched; explicit deletion uses DELETE /{tripId}.
     */
    @PutMapping
    public Map<String, Object> syncTrips(@AuthenticationPrincipal OAuth2User principal,
                                         @RequestBody Map<String, Object> request) {
        Object rawTrips = request == null ? null : request.get("trips");
        if (!(rawTrips instanceof List<?> values)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'trips' must be an array.");
        }
        List<Map<String, Object>> trips = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> source)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Every trip in 'trips' must be an object.");
            }
            Map<String, Object> trip = new LinkedHashMap<>();
            source.forEach((key, item) -> trip.put(String.valueOf(key), item));
            trips.add(trip);
        }
        try {
            return Map.of("trips", store.saveTrips(ownerId(principal), trips));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PutMapping("/{tripId}")
    public Map<String, Object> saveTrip(@AuthenticationPrincipal OAuth2User principal,
                                        @PathVariable String tripId,
                                        @RequestBody Map<String, Object> request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trip data is required.");
        Map<String, Object> trip = new LinkedHashMap<>(request);
        trip.put("id", tripId);
        try {
            return store.saveTrip(ownerId(principal), trip);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @DeleteMapping("/{tripId}")
    public ResponseEntity<Void> deleteTrip(@AuthenticationPrincipal OAuth2User principal,
                                           @PathVariable String tripId) {
        if (!store.deleteTrip(ownerId(principal), tripId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    private String ownerId(OAuth2User principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in with Google to access trip scripts.");
        }
        String subject = Objects.toString((Object) principal.getAttribute("sub"), "");
        if (subject.isBlank()) subject = Objects.toString(principal.getName(), "");
        if (subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "The signed-in creator identity is unavailable. Please sign in again.");
        }
        return subject;
    }
}
