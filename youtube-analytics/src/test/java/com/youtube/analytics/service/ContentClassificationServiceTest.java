package com.youtube.analytics.service;

import com.youtube.analytics.model.ContentClassification;
import com.youtube.analytics.model.VideoMeta;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentClassificationServiceTest {

    private final ContentClassificationService service = new ContentClassificationService();

    @Test
    void classifiesShortTravelVideo() {
        ContentClassification result = service.classify(new VideoMeta(
                "id", "India’s Most Beautiful Toy Train | Nilgiris #nilgiris #mountains #shorts", null));

        assertThat(result.getContentType()).isEqualTo(ContentClassification.ContentType.SHORT);
        assertThat(result.getCategory()).isEqualTo(ContentClassification.Category.TRAVEL);
        assertThat(result.getTopics()).contains("TRAVEL", "NILGIRIS", "MOUNTAINS");
    }

    @Test
    void classifiesLongFormTravelVideo() {
        ContentClassification result = service.classify(new VideoMeta(
                "id", "Weekend Trip from Bangalore: Devarayanadurga Hills | Missed Sunrise, Found Sunset", null));

        assertThat(result.getContentType()).isEqualTo(ContentClassification.ContentType.LONG_FORM);
        assertThat(result.getCategory()).isEqualTo(ContentClassification.Category.TRAVEL);
        assertThat(result.getTopics()).contains("BANGALORE", "TRAVEL", "MOUNTAINS", "WEEKEND");
    }

    @Test
    void classifiesFoodVideo() {
        ContentClassification result = service.classify(new VideoMeta(
                "id", "Cooking Aloo Dum Biriyani at Home", null));

        assertThat(result.getContentType()).isEqualTo(ContentClassification.ContentType.LONG_FORM);
        assertThat(result.getCategory()).isEqualTo(ContentClassification.Category.FOOD);
        assertThat(result.getTopics()).contains("FOOD");
    }

    @Test
    void classifiesCorporateLifeVideo() {
        ContentClassification result = service.classify(new VideoMeta(
                "id", "A Day in the Life of a Software Engineer | Corporate Life", null));

        assertThat(result.getContentType()).isEqualTo(ContentClassification.ContentType.LONG_FORM);
        assertThat(result.getCategory()).isEqualTo(ContentClassification.Category.CORPORATE_LIFE);
        assertThat(result.getTopics()).contains("CORPORATE_LIFE");
    }

    @Test
    void fallsBackToOtherCategory() {
        ContentClassification result = service.classify(new VideoMeta(
                "id", "My Random Weekend", null));

        assertThat(result.getCategory()).isEqualTo(ContentClassification.Category.LIFESTYLE);
        assertThat(result.getContentType()).isEqualTo(ContentClassification.ContentType.LONG_FORM);
    }

    @Test
    void handlesNullVideo() {
        ContentClassification result = service.classify(null);

        assertThat(result.getContentType()).isEqualTo(ContentClassification.ContentType.LONG_FORM);
        assertThat(result.getCategory()).isEqualTo(ContentClassification.Category.OTHER);
        assertThat(result.getTopics()).isEmpty();
    }
}
