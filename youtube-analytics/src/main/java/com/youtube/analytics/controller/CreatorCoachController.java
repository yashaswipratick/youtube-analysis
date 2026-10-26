package com.youtube.analytics.controller;

import com.youtube.analytics.service.creatorcoach.CreatorCoachExportService;
import com.youtube.analytics.service.creatorcoach.CreatorCoachService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** REST contract for the creator coaching backend. Sensitive owner IDs are derived only from OAuth. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/youtube-analytics/v1")
public class CreatorCoachController {
    private final CreatorCoachService service;
    private final CreatorCoachExportService exports;

    @PostMapping("/auth/youtube/connect")
    public ResponseEntity<Map<String, Object>> connectYouTube() {
        return ResponseEntity.ok(Map.of(
                "authorizationUrl", "/api/youtube-analytics/v1/oauth/start",
                "message", "Open the authorization URL to connect or reconnect the YouTube channel."));
    }

    @GetMapping("/auth/status")
    public ResponseEntity<Map<String, Object>> authStatus(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            return ResponseEntity.ok(Map.of(
                    "authenticated", false, "connected", false, "reconnectRequired", true,
                    "message", "Please connect YouTube to continue.",
                    "authorizationUrl", "/api/youtube-analytics/v1/oauth/start"));
        }
        return ResponseEntity.ok(service.authStatus(ownerId(principal), attr(principal, "email"), attr(principal, "name")));
    }

    @GetMapping("/profile")
    public Map<String, Object> getProfile(@AuthenticationPrincipal OAuth2User principal) {
        return service.getProfile(ownerId(principal));
    }

    @PutMapping("/profile")
    public Map<String, Object> saveProfile(@AuthenticationPrincipal OAuth2User principal,
                                           @RequestBody Map<String, Object> request) {
        return service.saveProfile(ownerId(principal), request);
    }

    @GetMapping("/videos")
    public Map<String, Object> videos(@AuthenticationPrincipal OAuth2User principal) {
        return service.videos(ownerId(principal));
    }

    @GetMapping("/videos/{videoId}/inputs")
    public Map<String, Object> inputs(@AuthenticationPrincipal OAuth2User principal,
                                      @PathVariable String videoId,
                                      @RequestParam(defaultValue = "365") Integer lastNDays) {
        return service.getVideoInputs(ownerId(principal), videoId, lastNDays);
    }

    @PutMapping("/videos/{videoId}/manual-metrics")
    public Map<String, Object> manualMetrics(@AuthenticationPrincipal OAuth2User principal,
                                             @PathVariable String videoId,
                                             @RequestBody Map<String, Object> request) {
        return service.saveManualMetrics(ownerId(principal), videoId, request);
    }

    @GetMapping("/videos/{videoId}/transcript")
    public Map<String, Object> transcript(@AuthenticationPrincipal OAuth2User principal,
                                          @PathVariable String videoId) {
        return service.getTranscript(ownerId(principal), videoId);
    }

    @PutMapping("/videos/{videoId}/transcript")
    public Map<String, Object> saveTranscript(@AuthenticationPrincipal OAuth2User principal,
                                              @PathVariable String videoId,
                                              @RequestBody Map<String, Object> request) {
        return service.saveTranscript(ownerId(principal), videoId, request);
    }

    @PostMapping("/videos/{videoId}/analyses")
    public ResponseEntity<Map<String, Object>> startAnalysis(@AuthenticationPrincipal OAuth2User principal,
                                                              @PathVariable String videoId,
                                                              @RequestParam(defaultValue = "365") Integer lastNDays) {
        Map<String, Object> response = service.startAnalysis(ownerId(principal), videoId, lastNDays);
        if (response.containsKey("jobId")) return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/analyses/{jobId}")
    public Map<String, Object> job(@AuthenticationPrincipal OAuth2User principal, @PathVariable String jobId) {
        return service.job(ownerId(principal), jobId);
    }

    @GetMapping("/videos/{videoId}/report")
    public Map<String, Object> latestReport(@AuthenticationPrincipal OAuth2User principal, @PathVariable String videoId) {
        return service.latestReport(ownerId(principal), videoId);
    }

    @GetMapping("/reports/{reportId}")
    public Map<String, Object> report(@AuthenticationPrincipal OAuth2User principal, @PathVariable String reportId) {
        return service.report(ownerId(principal), reportId);
    }

    @GetMapping("/reports/{reportId}/audience-curve")
    public Map<String, Object> audienceCurve(@AuthenticationPrincipal OAuth2User principal, @PathVariable String reportId) {
        return service.audienceCurve(ownerId(principal), reportId);
    }

    @PutMapping("/reports/{reportId}/fix-first")
    public Map<String, Object> fixFirst(@AuthenticationPrincipal OAuth2User principal,
                                        @PathVariable String reportId,
                                        @RequestBody Map<String, Object> request) {
        return service.updateFixFirst(ownerId(principal), reportId, request);
    }

    @PutMapping("/reports/{reportId}/checklist")
    public Map<String, Object> checklist(@AuthenticationPrincipal OAuth2User principal,
                                         @PathVariable String reportId,
                                         @RequestBody Map<String, Object> request) {
        return service.saveChecklist(ownerId(principal), reportId, request);
    }

    @PutMapping("/reports/{reportId}/answers")
    public Map<String, Object> answers(@AuthenticationPrincipal OAuth2User principal,
                                       @PathVariable String reportId,
                                       @RequestBody Map<String, Object> request) {
        return service.saveAnswers(ownerId(principal), reportId, request);
    }

    @GetMapping("/progress")
    public Map<String, Object> progress(@AuthenticationPrincipal OAuth2User principal) {
        return service.progress(ownerId(principal));
    }

    @GetMapping("/plans")
    public Map<String, Object> plans(@AuthenticationPrincipal OAuth2User principal) {
        return service.plans(ownerId(principal));
    }

    @GetMapping("/plans/{planId}")
    public Map<String, Object> plan(@AuthenticationPrincipal OAuth2User principal, @PathVariable String planId) {
        return service.plan(ownerId(principal), planId);
    }

    @GetMapping("/plans/{planId}/export")
    public ResponseEntity<byte[]> exportPlan(@AuthenticationPrincipal OAuth2User principal,
                                             @PathVariable String planId,
                                             @RequestParam(defaultValue = "text") String format) {
        if (!"text".equalsIgnoreCase(format) && !"txt".equalsIgnoreCase(format)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Video-plan export currently supports format=text.");
        }
        Map<String, Object> plan = service.plan(ownerId(principal), planId);
        byte[] bytes = exports.plainTextPlan(plan);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("video-plan-" + planId.replaceAll("[^A-Za-z0-9_-]", "") + ".txt").build().toString())
                .contentLength(bytes.length)
                .body(bytes);
    }

    @PostMapping("/plans")
    public Map<String, Object> createPlan(@AuthenticationPrincipal OAuth2User principal,
                                          @RequestBody Map<String, Object> request) {
        return service.savePlan(ownerId(principal), request, null);
    }

    @PutMapping("/plans/{planId}")
    public Map<String, Object> updatePlan(@AuthenticationPrincipal OAuth2User principal,
                                          @PathVariable String planId,
                                          @RequestBody Map<String, Object> request) {
        return service.savePlan(ownerId(principal), request, planId);
    }

    @PostMapping("/plans/{planId}/duplicate")
    public Map<String, Object> duplicatePlan(@AuthenticationPrincipal OAuth2User principal,
                                             @PathVariable String planId) {
        return service.duplicatePlan(ownerId(principal), planId);
    }

    @PostMapping("/plans/{planId}/title-check")
    public Map<String, Object> titleCheck(@AuthenticationPrincipal OAuth2User principal,
                                          @PathVariable String planId,
                                          @RequestBody Map<String, Object> request) {
        return service.titleCheck(ownerId(principal), planId, request);
    }

    @GetMapping("/reports/{reportId}/export")
    public ResponseEntity<byte[]> exportReport(@AuthenticationPrincipal OAuth2User principal,
                                                @PathVariable String reportId,
                                                @RequestParam(defaultValue = "text") String format) {
        Map<String, Object> report = service.report(ownerId(principal), reportId);
        String normalized = format == null ? "text" : format.trim().toLowerCase();
        byte[] bytes;
        MediaType type;
        String extension;
        if ("pdf".equals(normalized)) {
            bytes = exports.pdf(report);
            type = MediaType.APPLICATION_PDF;
            extension = "pdf";
        } else if ("text".equals(normalized) || "txt".equals(normalized)) {
            bytes = exports.plainText(report);
            type = new MediaType("text", "plain", StandardCharsets.UTF_8);
            extension = "txt";
        } else {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "format must be pdf or text.");
        }
        String safeId = reportId.replaceAll("[^A-Za-z0-9_-]", "");
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("creator-story-report-" + safeId + "." + extension).build().toString())
                .contentLength(bytes.length)
                .body(bytes);
    }

    @GetMapping("/glossary")
    public Map<String, Object> glossary(@AuthenticationPrincipal OAuth2User principal) {
        return service.glossary(ownerId(principal));
    }

    @DeleteMapping("/account/data")
    public ResponseEntity<Map<String, Object>> deleteMyData(@AuthenticationPrincipal OAuth2User principal) {
        service.deleteCreatorData(ownerId(principal));
        return ResponseEntity.ok(Map.of("deleted", true,
                "message", "Creator profiles, transcripts, reports, plans and checklist records were deleted. Reconnect YouTube if authorization is also required."));
    }

    private String ownerId(OAuth2User principal) {
        if (principal == null) throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Please connect YouTube to continue.");
        String subject = attr(principal, "sub");
        if (subject.isBlank()) subject = principal.getName();
        if (subject == null || subject.isBlank()) throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "The signed-in creator identity is unavailable. Please reconnect YouTube.");
        return subject;
    }

    private String attr(OAuth2User principal, String key) {
        Object value = principal.getAttribute(key);
        return value == null ? "" : String.valueOf(value);
    }
}
