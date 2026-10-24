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
    void defaultsToTheMostRecentTwentyEightReportingDays() {
        AnalyticsDateRangeResolver resolver = new AnalyticsDateRangeResolver(mock(YouTubePublicApiService.class));
        LocalDate today = LocalDate.now(AnalyticsDateRangeResolver.REPORTING_ZONE);

        AnalyticsDateRangeResolver.DateRange range = resolver.resolve(null);

        assertThat(range.startDate()).isEqualTo(today.minusDays(27));
        assertThat(range.endDate()).isEqualTo(today);
    }

    @Test
    void lastNDaysIsAnInclusiveNumberOfReportingDays() {
        AnalyticsDateRangeResolver resolver = new AnalyticsDateRangeResolver(mock(YouTubePublicApiService.class));
        LocalDate today = LocalDate.now(AnalyticsDateRangeResolver.REPORTING_ZONE);

        AnalyticsDateRangeResolver.DateRange range = resolver.resolve(7);

        assertThat(range.startDate()).isEqualTo(today.minusDays(6));
        assertThat(range.endDate()).isEqualTo(today);
    }

    @Test
    void clampsARecentWindowAtTheVideoPublishDate() {
        YouTubePublicApiService publicApi = mock(YouTubePublicApiService.class);
        LocalDate publishDay = LocalDate.now(AnalyticsDateRangeResolver.REPORTING_ZONE).minusDays(2);
        String publishedAt = publishDay.atTime(17, 0)
                .atZone(AnalyticsDateRangeResolver.REPORTING_ZONE).toInstant().toString();
        when(publicApi.getVideoPublishDate(VIDEO_ID)).thenReturn(publishedAt);
        AnalyticsDateRangeResolver resolver = new AnalyticsDateRangeResolver(publicApi);

        AnalyticsDateRangeResolver.DateRange range = resolver.resolveForVideo(VIDEO_ID, 90);

        assertThat(range.startDate()).isEqualTo(publishDay);
        assertThat(range.endDate()).isEqualTo(LocalDate.now(AnalyticsDateRangeResolver.REPORTING_ZONE));
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
