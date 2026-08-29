package com.youtube.analytics.exception;

import org.springframework.http.HttpStatusCode;

/** A sanitized failure from the upstream YouTube Analytics API. */
public class YouTubeAnalyticsApiException extends RuntimeException {
    private final HttpStatusCode statusCode;

    public YouTubeAnalyticsApiException(HttpStatusCode statusCode) {
        super("YouTube Analytics API returned " + statusCode.value());
        this.statusCode = statusCode;
    }

    public HttpStatusCode getStatusCode() {
        return statusCode;
    }
}
