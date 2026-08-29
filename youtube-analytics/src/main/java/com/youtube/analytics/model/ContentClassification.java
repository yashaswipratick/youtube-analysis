package com.youtube.analytics.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Deterministic content classification derived from a video's title.
 *
 * Content type is inferred from explicit short-form markers such as
 * #shorts, #travelshorts and minivlog. Category and topics are keyword-based
 * signals intended to provide stable input for later analysis/AI layers.
 */
@Data
@Builder
public class ContentClassification {

    private ContentType contentType;
    private Category category;
    private List<String> topics;

    public enum ContentType {
        SHORT,
        LONG_FORM
    }

    public enum Category {
        TRAVEL,
        CORPORATE_LIFE,
        FOOD,
        FITNESS,
        LIFESTYLE,
        OTHER
    }
}
