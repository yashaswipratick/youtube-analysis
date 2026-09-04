package com.youtube.analytics.videoanalysis.render;

import com.youtube.analytics.videoanalysis.model.EditPlan;
import com.youtube.analytics.videoanalysis.model.RenderJob;
import com.youtube.analytics.videoanalysis.config.RenderJobProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class RenderJobService {
    private final EditRendererService renderer;
    private final Map<String, RenderJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public RenderJobService(EditRendererService renderer, RenderJobProperties properties) {
        this.renderer = renderer;
        this.executor = Executors.newFixedThreadPool(properties.maxConcurrentJobs(), runnable -> {
            Thread thread = new Thread(runnable, "video-render-job");
            thread.setDaemon(true);
            return thread;
        });
    }

    public RenderJob submit(EditPlan plan) {
        String id = UUID.randomUUID().toString();
        RenderJob queued = new RenderJob(id, plan.projectId(), RenderJob.Status.QUEUED, null, null, Instant.now(), null);
        jobs.put(id, queued);
        executor.submit(() -> run(id, plan));
        return queued;
    }

    public RenderJob get(String jobId) {
        RenderJob job = jobs.get(jobId);
        if (job == null) throw new IllegalArgumentException("Render job not found: " + jobId);
        return job;
    }

    private void run(String id, EditPlan plan) {
        jobs.computeIfPresent(id, (key, job) -> new RenderJob(job.jobId(), job.projectId(), RenderJob.Status.RUNNING,
                null, null, job.createdAt(), null));
        try {
            var output = renderer.render(plan);
            jobs.computeIfPresent(id, (key, job) -> new RenderJob(job.jobId(), job.projectId(), RenderJob.Status.COMPLETED,
                    output.toString(), null, job.createdAt(), Instant.now()));
        } catch (RuntimeException ex) {
            jobs.computeIfPresent(id, (key, job) -> new RenderJob(job.jobId(), job.projectId(), RenderJob.Status.FAILED,
                    null, "Render failed", job.createdAt(), Instant.now()));
        }
    }
}
