package com.youtube.analytics.controller;

import com.youtube.analytics.model.TranscriptComparisonRequest;
import com.youtube.analytics.service.TranscriptComparisonService;
import com.youtube.analytics.service.businesslogic.AnalyticsWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** API facade for the UI workspace workflows. */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/youtube-analytics/v1/analytics/workspace")
public class AnalyticsWorkspaceController {
    private final AnalyticsWorkspaceService workspaceService;
    private final TranscriptComparisonService transcriptComparisonService;

    @GetMapping("/channel/mine")
    public ResponseEntity<Map<String, Object>> myChannel(
            @RequestParam(required = false) Integer lastNDays) {
        return ResponseEntity.ok(workspaceService.channel("mine", lastNDays));
    }

    @GetMapping("/channel/{channelId}")
    public ResponseEntity<Map<String, Object>> channel(
            @PathVariable String channelId,
            @RequestParam(required = false) Integer lastNDays) {
        return ResponseEntity.ok(workspaceService.channel(channelId, lastNDays));
    }

    @GetMapping("/video/{videoId}")
    public ResponseEntity<Map<String, Object>> video(
            @PathVariable String videoId,
            @RequestParam(required = false) Integer lastNDays) {
        return ResponseEntity.ok(workspaceService.video(videoId, lastNDays));
    }

    @GetMapping("/channel/{channelId}/compare")
    public ResponseEntity<Map<String, Object>> compare(
            @PathVariable String channelId,
            @RequestParam(defaultValue = "LONG_FORM") String contentType,
            @RequestParam(defaultValue = "10") Integer count,
            @RequestParam(required = false) Integer lastNDays) {
        return ResponseEntity.ok(workspaceService.compare(channelId, contentType, count, lastNDays));
    }

    @PostMapping("/transcript-compare")
    public ResponseEntity<Map<String, Object>> transcriptCompare(
            @Valid @RequestBody TranscriptComparisonRequest request) {
        return ResponseEntity.ok(transcriptComparisonService.compare(request));
    }

    @GetMapping("/reports")
    public ResponseEntity<Map<String, Object>> reports(
            @RequestParam(defaultValue = "mine") String channelId) {
        return ResponseEntity.ok(workspaceService.reports(channelId));
    }

    @GetMapping("/keywords")
    public ResponseEntity<Map<String, Object>> keywords(
            @RequestParam String seed,
            @RequestParam(required = false) Integer lastNDays) {
        return ResponseEntity.ok(workspaceService.keywords(seed, lastNDays));
    }

    @GetMapping("/benchmarks")
    public ResponseEntity<Map<String, Object>> benchmark(@RequestParam String query) {
        return ResponseEntity.ok(workspaceService.benchmark(query));
    }
}
