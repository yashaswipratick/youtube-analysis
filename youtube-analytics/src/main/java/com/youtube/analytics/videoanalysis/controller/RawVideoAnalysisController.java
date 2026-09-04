package com.youtube.analytics.videoanalysis.controller;

import com.youtube.analytics.model.ApiResponse;
import com.youtube.analytics.videoanalysis.model.EditPlan;
import com.youtube.analytics.videoanalysis.model.RawVideoAnalysisRequest;
import com.youtube.analytics.videoanalysis.render.EditRendererService;
import com.youtube.analytics.videoanalysis.service.RawVideoAnalysisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/video-analysis")
public class RawVideoAnalysisController {

    private final RawVideoAnalysisService rawVideoAnalysisService;
    private final EditRendererService editRendererService;

    @PostMapping("/edit-plan")
    public ResponseEntity<ApiResponse<EditPlan>> buildEditPlan(@Valid @RequestBody RawVideoAnalysisRequest request) {
        return ResponseEntity.ok(ApiResponse.success(rawVideoAnalysisService.buildEditPlan(request)));
    }

    @PostMapping("/render")
    public ResponseEntity<ApiResponse<String>> renderEdit(@Valid @RequestBody RawVideoAnalysisRequest request) {
        EditPlan plan = rawVideoAnalysisService.buildEditPlan(request);
        return ResponseEntity.ok(ApiResponse.success(editRendererService.render(plan).toString()));
    }
}
