package com.youtube.analytics.videoanalysis.controller;

import com.youtube.analytics.model.ApiResponse;
import com.youtube.analytics.videoanalysis.model.EditPlan;
import com.youtube.analytics.videoanalysis.model.RawVideoAnalysisRequest;
import com.youtube.analytics.videoanalysis.model.RenderJob;
import com.youtube.analytics.videoanalysis.render.EditRendererService;
import com.youtube.analytics.videoanalysis.render.RenderJobService;
import com.youtube.analytics.videoanalysis.service.RawVideoAnalysisService;
import com.youtube.analytics.videoanalysis.sequencing.BenchmarkService;
import com.youtube.analytics.videoanalysis.review.EditReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/video-analysis")
@Validated
public class RawVideoAnalysisController {
    private final RawVideoAnalysisService rawVideoAnalysisService;
    private final EditRendererService editRendererService;
    private final RenderJobService renderJobService;
    private final BenchmarkService benchmarkService;
    private final EditReviewService editReviewService;

    @PostMapping("/edit-plan")
    public ResponseEntity<ApiResponse<EditPlan>> buildEditPlan(@Valid @RequestBody RawVideoAnalysisRequest request) {
        return ResponseEntity.ok(ApiResponse.success(rawVideoAnalysisService.buildEditPlan(request)));
    }

    @PostMapping("/render")
    public ResponseEntity<ApiResponse<RenderJob>> renderEdit(@Valid @RequestBody RawVideoAnalysisRequest request) {
        EditPlan plan = rawVideoAnalysisService.buildEditPlan(request);
        return ResponseEntity.accepted().body(ApiResponse.success(renderJobService.submit(plan)));
    }

    @GetMapping("/render/{jobId}")
    public ResponseEntity<ApiResponse<RenderJob>> renderStatus(@PathVariable String jobId) {
        return ResponseEntity.ok(ApiResponse.success(renderJobService.get(jobId)));
    }

    @PostMapping("/benchmark")
    public ResponseEntity<ApiResponse<BenchmarkService.BenchmarkResult>> benchmark(@RequestBody EditPlan plan,
                                                                                     @RequestParam @Positive @Max(10800000) long targetDurationMs) {
        return ResponseEntity.ok(ApiResponse.success(benchmarkService.evaluate(
                plan.sequence().stream().map(EditPlan.EditSequenceItem::clip).toList(), targetDurationMs)));
    }

    @PostMapping("/review")
    public ResponseEntity<ApiResponse<EditReviewService.Review>> createReview(@Valid @RequestBody RawVideoAnalysisRequest request) {
        return ResponseEntity.ok(ApiResponse.success(editReviewService.create(rawVideoAnalysisService.buildEditPlan(request))));
    }

    @GetMapping("/review/{reviewId}")
    public ResponseEntity<ApiResponse<EditReviewService.Review>> getReview(@PathVariable String reviewId) {
        return ResponseEntity.ok(ApiResponse.success(editReviewService.get(reviewId)));
    }

    @PostMapping("/review/{reviewId}/decision")
    public ResponseEntity<ApiResponse<EditReviewService.Review>> decideReview(@PathVariable String reviewId,
                                                                                @RequestBody ReviewDecision request) {
        return ResponseEntity.ok(ApiResponse.success(editReviewService.decide(reviewId, request.status(), request.comment())));
    }

    @PostMapping("/review/{reviewId}/remove/{sequenceNumber}")
    public ResponseEntity<ApiResponse<EditReviewService.Review>> removeSequenceItem(@PathVariable String reviewId,
                                                                                      @PathVariable int sequenceNumber) {
        return ResponseEntity.ok(ApiResponse.success(editReviewService.removeSequenceItem(reviewId, sequenceNumber)));
    }

    public record ReviewDecision(EditReviewService.Status status, String comment) {}
}
