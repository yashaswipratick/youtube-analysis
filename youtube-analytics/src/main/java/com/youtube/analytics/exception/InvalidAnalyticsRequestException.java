package com.youtube.analytics.exception;

/** Raised before a malformed analytics request is sent to YouTube. */
public class InvalidAnalyticsRequestException extends RuntimeException {
    public InvalidAnalyticsRequestException(String message) {
        super(message);
    }
}
