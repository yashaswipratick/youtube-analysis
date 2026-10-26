package com.youtube.analytics.controller;

import com.youtube.analytics.service.vlogscript.StorytellingRewriteService;
import com.youtube.analytics.service.vlogscript.VlogScriptStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import java.util.UUID;

/**
 * Authenticated API for the destination/trip -> episode -> script-section library
 * used by VlogScript Studio. The owner is always derived from Google OAuth.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/youtube-analytics/v1/vlog-trips")
public class VlogScriptController {
    private final VlogScriptStore store;
    private final StorytellingRewriteService storytellingRewriteService;

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

    /**
     * Rewrites the supplied voice-over sections as natural spoken storytelling.
     * Only a preview is returned; the Studio persists changes after the creator
     * explicitly applies the rewrite.
     */
    @PostMapping("/storytelling-rewrite")
    public Map<String, Object> rewriteVoiceOverSections(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestBody Map<String, Object> request) {
        ownerId(principal); // Rewriting uses the same authenticated creator boundary as script storage.
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rewrite request is required.");
        }
        Object rawSections = request.get("sections");
        if (!(rawSections instanceof List<?> values)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'sections' must be an array.");
        }

        List<Map<String, Object>> sections = sectionMaps(values, "rewrite");
        List<Map<String, Object>> episodeContext = List.of();
        Object rawContext = request.get("fullEpisodeContext");
        if (rawContext instanceof List<?> contextValues) {
            episodeContext = sectionMaps(contextValues, "episode context");
        } else if (rawContext != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'fullEpisodeContext' must be an array.");
        }
        String destination = Objects.toString(request.get("destinationName"), "");
        String episode = Objects.toString(request.get("episodeTitle"), "");
        log.info("VOICEOVER_REWRITE_ENDPOINT_RECEIVED | requestId={} | creatorAuthenticated=true | destination={} | episode={} | sections={} | contextSections={}",
                requestId, destination.replaceAll("[\\r\\n\\t]+", " "), episode.replaceAll("[\\r\\n\\t]+", " "), sections.size(), episodeContext.size());
        try {
            List<Map<String, String>> rewritten = storytellingRewriteService.rewriteSections(
                    requestId, destination, episode, sections, episodeContext);
            log.info("VOICEOVER_REWRITE_ENDPOINT_SUCCESS | requestId={} | sectionsReturned={}", requestId, rewritten.size());
            return Map.of("requestId", requestId, "model", "openai", "sections", rewritten);
        } catch (IllegalArgumentException ex) {
            log.warn("VOICEOVER_REWRITE_ENDPOINT_REJECTED | requestId={} | reason={}", requestId, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            log.error("VOICEOVER_REWRITE_ENDPOINT_FAILED | requestId={} | creatorAuthenticated=true | reason={}", requestId, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Storytelling rewrite failed (requestId=" + requestId + "). " + ex.getMessage(), ex);
        }
    }

    /**
     * Audits the full episode against the reusable Yash Xplore v2.3 story format.
     * The audit is advisory only and never mutates or persists script content.
     */
    @PostMapping("/storytelling-audit")
    public Map<String, Object> auditEpisodeStory(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestBody Map<String, Object> request) {
        ownerId(principal);
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Story audit request is required.");
        }
        Object rawSections = request.get("sections");
        if (!(rawSections instanceof List<?> values)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'sections' must be an array.");
        }
        List<Map<String, Object>> sections = sectionMaps(values, "episode");
        String destination = Objects.toString(request.get("destinationName"), "");
        String episode = Objects.toString(request.get("episodeTitle"), "");
        log.info("STORY_AUDIT_ENDPOINT_RECEIVED | requestId={} | creatorAuthenticated=true | destination={} | episode={} | sections={}",
                requestId, destination.replaceAll("[\\r\\n\\t]+", " "),
                episode.replaceAll("[\\r\\n\\t]+", " "), sections.size());
        try {
            return storytellingRewriteService.auditEpisode(requestId, destination, episode, sections);
        } catch (IllegalArgumentException ex) {
            log.warn("STORY_AUDIT_ENDPOINT_REJECTED | requestId={} | reason={}", requestId, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            log.error("STORY_AUDIT_ENDPOINT_FAILED | requestId={} | creatorAuthenticated=true | reason={}",
                    requestId, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Story audit failed (requestId=" + requestId + "). " + ex.getMessage(), ex);
        }
    }

    private List<Map<String, Object>> sectionMaps(List<?> values, String purpose) {
        List<Map<String, Object>> sections = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> source)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Every " + purpose + " section must be an object.");
            }
            Map<String, Object> section = new LinkedHashMap<>();
            source.forEach((key, item) -> section.put(String.valueOf(key), item));
            sections.add(section);
        }
        return sections;
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
