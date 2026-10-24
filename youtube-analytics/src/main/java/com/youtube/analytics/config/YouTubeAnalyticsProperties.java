package com.youtube.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed configuration for Analytics API metric groups and report data archival.
 */
@ConfigurationProperties(prefix = "youtube.analytics")
public class YouTubeAnalyticsProperties {

    private int reportingLagDays = 3;
    private String archiveDirectory = "./data/youtube-analytics/reports";
    private Metrics metrics = new Metrics();

    public int getReportingLagDays() {
        return reportingLagDays;
    }

    public void setReportingLagDays(int reportingLagDays) {
        this.reportingLagDays = Math.max(0, Math.min(reportingLagDays, 7));
    }

    public String getArchiveDirectory() {
        return archiveDirectory;
    }

    public void setArchiveDirectory(String archiveDirectory) {
        this.archiveDirectory = archiveDirectory;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public void setMetrics(Metrics metrics) {
        this.metrics = metrics == null ? new Metrics() : metrics;
    }

    public static class Metrics {
        private List<String> core = csv("views,engagedViews,estimatedMinutesWatched,averageViewDuration,averageViewPercentage,likes,dislikes,comments,shares,subscribersGained,subscribersLost,videosAddedToPlaylists,videosRemovedFromPlaylists");
        private List<String> cards = csv("cardImpressions,cardClicks,cardClickRate,cardTeaserImpressions,cardTeaserClicks,cardTeaserClickRate");
        private List<String> premium = csv("redViews,estimatedRedMinutesWatched");
        private List<String> retention = csv("audienceWatchRatio,relativeRetentionPerformance");
        private List<String> demographics = csv("viewerPercentage");
        private List<String> traffic = csv("engagedViews,views,estimatedMinutesWatched");
        private List<String> trend = csv("views,estimatedMinutesWatched,subscribersGained,shares,uniques");
        private List<String> revenue = csv("estimatedRevenue,estimatedAdRevenue,estimatedRedPartnerRevenue,grossRevenue,cpm,playbackBasedCpm,monetizedPlaybacks,adImpressions");
        private List<String> optional = csv("videoThumbnailImpressions,videoThumbnailImpressionsClickRate,endScreenElementImpressions,endScreenElementClicks,endScreenElementClickRate");
        private List<String> retentionEvents = csv("startedWatching,stoppedWatching,totalSegmentImpressions");
        private List<String> livestream = csv("averageConcurrentViewers,peakConcurrentViewers");
        private List<String> format = csv("views,estimatedMinutesWatched,averageViewDuration,averageViewPercentage");
        private List<String> playlist = csv("averageTimeInPlaylist,playlistAverageViewDuration,playlistEstimatedMinutesWatched,playlistSaves,playlistStarts,playlistViews,viewsPerPlaylistStart");
        private List<String> geography = csv("engagedViews,views,estimatedMinutesWatched,averageViewDuration,averageViewPercentage");

        public List<String> getCore() { return core; }
        public void setCore(List<String> core) { this.core = copy(core); }
        public List<String> getCards() { return cards; }
        public void setCards(List<String> cards) { this.cards = copy(cards); }
        public List<String> getPremium() { return premium; }
        public void setPremium(List<String> premium) { this.premium = copy(premium); }
        public List<String> getRetention() { return retention; }
        public void setRetention(List<String> retention) { this.retention = copy(retention); }
        public List<String> getDemographics() { return demographics; }
        public void setDemographics(List<String> demographics) { this.demographics = copy(demographics); }
        public List<String> getTraffic() { return traffic; }
        public void setTraffic(List<String> traffic) { this.traffic = copy(traffic); }
        public List<String> getTrend() { return trend; }
        public void setTrend(List<String> trend) { this.trend = copy(trend); }
        public List<String> getRevenue() { return revenue; }
        public void setRevenue(List<String> revenue) { this.revenue = copy(revenue); }
        public List<String> getOptional() { return optional; }
        public void setOptional(List<String> optional) { this.optional = copy(optional); }
        public List<String> getRetentionEvents() { return retentionEvents; }
        public void setRetentionEvents(List<String> retentionEvents) { this.retentionEvents = copy(retentionEvents); }
        public List<String> getLivestream() { return livestream; }
        public void setLivestream(List<String> livestream) { this.livestream = copy(livestream); }
        public List<String> getFormat() { return format; }
        public void setFormat(List<String> format) { this.format = copy(format); }
        public List<String> getPlaylist() { return playlist; }
        public void setPlaylist(List<String> playlist) { this.playlist = copy(playlist); }
        public List<String> getGeography() { return geography; }
        public void setGeography(List<String> geography) { this.geography = copy(geography); }

        private static List<String> csv(String value) {
            return List.of(value.split(","));
        }

        private static List<String> copy(List<String> values) {
            return values == null ? new ArrayList<>() : List.copyOf(values);
        }
    }
}
