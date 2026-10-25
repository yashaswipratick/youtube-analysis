package com.youtube.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Typed configuration for asynchronous YouTube Reporting API field names. */
@ConfigurationProperties(prefix = "youtube.reporting")
public class YouTubeReportingProperties {

    private Metrics metrics = new Metrics();

    public Metrics getMetrics() {
        return metrics;
    }

    public void setMetrics(Metrics metrics) {
        this.metrics = metrics == null ? new Metrics() : metrics;
    }

    public static class Metrics {
        private List<String> reach = List.of("video_thumbnail_impressions", "video_thumbnail_impressions_ctr");
        private List<String> reachCombined = List.of("video_thumbnail_impressions", "video_thumbnail_impressions_ctr");
        private List<String> endScreens = List.of("end_screen_element_impressions", "end_screen_element_clicks", "end_screen_element_click_rate");

        public List<String> getReach() { return reach; }
        public void setReach(List<String> reach) { this.reach = reach == null ? List.of() : List.copyOf(reach); }
        public List<String> getReachCombined() { return reachCombined; }
        public void setReachCombined(List<String> reachCombined) { this.reachCombined = reachCombined == null ? List.of() : List.copyOf(reachCombined); }
        public List<String> getEndScreens() { return endScreens; }
        public void setEndScreens(List<String> endScreens) { this.endScreens = endScreens == null ? List.of() : List.copyOf(endScreens); }
    }
}
