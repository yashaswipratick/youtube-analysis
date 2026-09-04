package com.youtube.analytics.videoanalysis.service;

import com.youtube.analytics.videoanalysis.model.EditJob;
import com.youtube.analytics.videoanalysis.model.EditPlan;
import com.youtube.analytics.videoanalysis.model.RawVideoAnalysisRequest;
import com.youtube.analytics.videoanalysis.render.EditRendererService;
import com.youtube.analytics.videoanalysis.config.RenderJobProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class EditJobService {
    private final RawVideoAnalysisService analysisService;
    private final EditRendererService renderer;
    private final EditingProgressReporter progressReporter;
    private final Map<String, EditJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public EditJobService(RawVideoAnalysisService analysisService,
                          EditRendererService renderer,
                          RenderJobProperties properties,
                          EditingProgressReporter progressReporter) {
        this.analysisService = analysisService;
        this.renderer = renderer;
        this.progressReporter = progressReporter;
        this.executor = Executors.newFixedThreadPool(properties.maxConcurrentJobs(), runnable -> {
            Thread thread = new Thread(runnable, "video-edit-job");
            thread.setDaemon(true);
            return thread;
        });
    }

    public EditJob submit(RawVideoAnalysisRequest request) {
        String id = UUID.randomUUID().toString();
        EditJob queued = new EditJob(id, request.projectId(), EditJob.Status.QUEUED, 0,
                "Queued", 0, 0, null, null, Instant.now(), null);
        jobs.put(id, queued);
        System.out.printf("[EDITING] [jobId=%s] Job accepted and queued%n", id);
        executor.submit(() -> run(id, request));
        return queued;
    }

    public EditJob get(String jobId) {
        EditJob job = jobs.get(jobId);
        if (job == null) throw new IllegalArgumentException("Edit job not found: " + jobId);
        return job;
    }

    private void run(String id, RawVideoAnalysisRequest request) {
        update(id, EditJob.Status.RUNNING, 0, "Starting intelligent video editing", null, null);
        try {
            EditPlan plan = analysisService.buildEditPlan(request, id);
            update(id, EditJob.Status.RUNNING, 88, "Edit plan ready; rendering", null, null);
            progressReporter.report(id, 90, "Rendering final edit with FFmpeg");
            var output = renderer.render(plan);
            progressReporter.report(id, 97, "Finalizing audio, effects and captions");
            progressReporter.complete(id, "Editing completed successfully: " + output.getFileName());
            update(id, EditJob.Status.COMPLETED, 100, "Completed", output.toString(), null);
        } catch (RuntimeException ex) {
            progressReporter.report(id, 100, "Editing failed: " + ex.getMessage());
            update(id, EditJob.Status.FAILED, 100, "Failed", null, ex.getMessage());
        }
    }

    private void update(String id, EditJob.Status status, int progress, String stage, String outputPath, String error) {
        jobs.computeIfPresent(id, (key, job) -> new EditJob(job.jobId(), job.projectId(), status, progress, stage,
                job.discoveredVideos(), job.eligibleVideos(), outputPath == null ? job.outputPath() : outputPath,
                error, job.createdAt(), status == EditJob.Status.COMPLETED || status == EditJob.Status.FAILED ? Instant.now() : null));
    }
}
