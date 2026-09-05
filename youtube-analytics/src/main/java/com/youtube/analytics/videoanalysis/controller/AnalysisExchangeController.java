package com.youtube.analytics.videoanalysis.controller;

import com.youtube.analytics.videoanalysis.service.AnalysisRequestExchangeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/video-analysis/analysis")
public class AnalysisExchangeController {

    private final AnalysisRequestExchangeService analysisExchangeService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> allAnalysis() {
        return ResponseEntity.ok(analysisExchangeService.readAllAnalysis());
    }

    @GetMapping(value = "/{fileName:.+}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> analysis(@PathVariable String fileName) {
        return ResponseEntity.ok(analysisExchangeService.readAnalysis(fileName));
    }
}
