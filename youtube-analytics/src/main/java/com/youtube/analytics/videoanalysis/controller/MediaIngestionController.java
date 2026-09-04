package com.youtube.analytics.videoanalysis.controller;

import com.youtube.analytics.model.ApiResponse;
import com.youtube.analytics.videoanalysis.ingestion.LocalMediaFile;
import com.youtube.analytics.videoanalysis.ingestion.MediaApprovalService;
import com.youtube.analytics.videoanalysis.ingestion.MediaDiscoveryService;
import com.youtube.analytics.videoanalysis.model.MediaReadAnalysis;
import com.youtube.analytics.videoanalysis.service.ApprovedMediaReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/video-analysis/media")
public class MediaIngestionController {

    private final MediaDiscoveryService discoveryService;
    private final MediaApprovalService approvalService;
    private final ApprovedMediaReadService mediaReadService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<LocalMediaFile>>> discoverMedia() {
        return ResponseEntity.ok(ApiResponse.success(discoveryService.discover()));
    }

    @PostMapping("/approve/{relativePath:.+}")
    public ResponseEntity<ApiResponse<LocalMediaFile>> approveMedia(@PathVariable String relativePath) {
        return ResponseEntity.ok(ApiResponse.success(approvalService.approve(relativePath)));
    }

    @PostMapping("/read/{relativePath:.+}")
    public ResponseEntity<ApiResponse<MediaReadAnalysis>> readApprovedMedia(@PathVariable String relativePath) {
        return ResponseEntity.ok(ApiResponse.success(mediaReadService.readAndAnalyze(relativePath)));
    }
}
