package com.youtube.analytics.videoanalysis.review;

import com.youtube.analytics.videoanalysis.model.EditPlan;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EditReviewService {
    private final Map<String, Review> reviews = new ConcurrentHashMap<>();

    public Review create(EditPlan plan) {
        String id = UUID.randomUUID().toString();
        Review review = new Review(id, plan, Status.PENDING, null);
        reviews.put(id, review);
        return review;
    }

    public Review get(String id) {
        Review review = reviews.get(id);
        if (review == null) throw new IllegalArgumentException("Review not found: " + id);
        return review;
    }

    public Review decide(String id, Status status, String comment) {
        if (status != Status.APPROVED && status != Status.REJECTED) throw new IllegalArgumentException("Review status must be APPROVED or REJECTED");
        Review current = get(id);
        Review updated = new Review(current.reviewId(), current.plan(), status, comment);
        reviews.put(id, updated);
        return updated;
    }

    public Review removeSequenceItem(String id, int sequenceNumber) {
        Review current = get(id);
        var sequence = current.plan().sequence().stream().filter(i -> i.sequenceNumber() != sequenceNumber).toList();
        if (sequence.size() == current.plan().sequence().size()) throw new IllegalArgumentException("Sequence item not found: " + sequenceNumber);
        EditPlan plan = new EditPlan(current.plan().projectId(), current.plan().storyIntent(), sequence,
                sequence.stream().mapToLong(i -> i.timelineEndMs() - i.timelineStartMs()).sum(), current.plan().warnings());
        Review updated = new Review(current.reviewId(), plan, Status.PENDING, current.comment());
        reviews.put(id, updated);
        return updated;
    }

    public record Review(String reviewId, EditPlan plan, Status status, String comment) {}
    public enum Status { PENDING, APPROVED, REJECTED }
}
