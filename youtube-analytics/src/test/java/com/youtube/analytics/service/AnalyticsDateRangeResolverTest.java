package com.youtube.analytics.service;

import com.youtube.analytics.exception.InvalidAnalyticsRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsDateRangeResolverTest {

    private static final String VIDEO_ID = "sMMJS_Hp8KI";

    @Test
    void defaultsToTheMostRecentTwentyEightFinalizedReportingDays() {
        AnalyticsDateRangeResolver resolver = new AnalyticsDateRangeResolver(mock(YouTubePublicApiService.class));
        LocalDate safeEnd = LocalDate.now(AnalyticsDateRangeResolver.REPORTING_ZONE)
                .minusDays(AnalyticsDateRangeResolver.DEFAULT_REPORTING_LAG_DAYS);

        AnalyticsDateRangeResolver.DateRange range = resolver.resolve(null);

        assertThat(range.startDate()).isEqualTo(safeEnd.minusDays(27));
        assertThat(range.endDate()).isEqualTo(safeEnd);
    }

    @Test
    void lastNDaysIsAnInclusiveNumberOfFinalizedReportingDays() {
        AnalyticsDateRangeResolver resolver = new AnalyticsDateRangeResolver(mock(YouTubePublicApiService.class));
        LocalDate safeEnd = LocalDate.now(AnalyticsDateRangeResolver.REPORTING_ZONE)
                .minusDays(AnalyticsDateRangeResolver.DEFAULT_REPORTING_LAG_DAYS);

        AnalyticsDateRangeResolver.DateRange range = resolver.resolve(7);

        assertThat(range.startDate()).isEqualTo(safeEnd.minusDays(6));
        assertThat(range.endDate()).isEqualTo(safeEnd);
    }

    @Test
    void clampsARecentWindowAtTheVideoPublishDate() {
        YouTubePublicApiService publicApi = mock(YouTubePublicApiService.class);
        LocalDate safeEnd = LocalDate.now(AnalyticsDateRangeResolver.REPORTING_ZONE)
                .minusDays(AnalyticsDateRangeResolver.DEFAULT_REPORTING_LAG_DAYS);
        LocalDate publishDay = safeEnd.minusDays(2);
        String publishedAt = publishDay.atTime(17, 0)
                .atZone(AnalyticsDateRangeResolver.REPORTING_ZONE).toInstant().toString();
        when(publicApi.getVideoPublishDate(VIDEO_ID)).thenReturn(publishedAt);
        AnalyticsDateRangeResolver resolver = new AnalyticsDateRangeResolver(publicApi);

        AnalyticsDateRangeResolver.DateRange range = resolver.resolveForVideo(VIDEO_ID, 90);

        assertThat(range.startDate()).isEqualTo(publishDay);
        assertThat(range.endDate()).isEqualTo(safeEnd);
    }

    @Test
    void acceptsVideoPublishedWithinTheReportingLagButBeforeToday() {
        YouTubePublicApiService publicApi = mock(YouTubePublicApiService.class);
        LocalDate today = LocalDate.now(AnalyticsDateRangeResolver.REPORTING_ZONE);
        String publishedAt = today.minusDays(1).atTime(17, 0)
                .atZone(AnalyticsDateRangeResolver.REPORTING_ZONE).toInstant().toString();
        when(publicApi.getVideoPublishDate(VIDEO_ID)).thenReturn(publishedAt);
        AnalyticsDateRangeResolver resolver = new AnalyticsDateRangeResolver(publicApi);

        AnalyticsDateRangeResolver.DateRange range = resolver.resolveForVideo(VIDEO_ID, 7);

        assertThat(range.endDate()).isBefore(LocalDate.parse(publishedAt.substring(0, 10)));
    }

    @Test
    void rejectsVideoThatHasNotBeenPublishedYet() {
        YouTubePublicApiService publicApi = mock(YouTubePublicApiService.class);
        String publishedAt = LocalDate.now(AnalyticsDateRangeResolver.REPORTING_ZONE).plusDays(1)
                .atStartOfDay(AnalyticsDateRangeResolver.REPORTING_ZONE).toInstant().toString();
        when(publicApi.getVideoPublishDate(VIDEO_ID)).thenReturn(publishedAt);
        AnalyticsDateRangeResolver resolver = new AnalyticsDateRangeResolver(publicApi);

        assertThatThrownBy(() -> resolver.resolveForVideo(VIDEO_ID, 7))
                .isInstanceOf(InvalidAnalyticsRequestException.class)
                .hasMessageContaining("has not been published yet");
    }

    @Test
    void rejectsOutOfBoundsLastNDays() {
        AnalyticsDateRangeResolver resolver = new AnalyticsDateRangeResolver(mock(YouTubePublicApiService.class));

        assertThatThrownBy(() -> resolver.resolve(0))
                .isInstanceOf(InvalidAnalyticsRequestException.class)
                .hasMessageContaining("between 1 and");
        assertThatThrownBy(() -> resolver.resolve(3651))
                .isInstanceOf(InvalidAnalyticsRequestException.class)
                .hasMessageContaining("between 1 and");
    }
}
