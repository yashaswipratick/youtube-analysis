package com.youtube.analytics.videoanalysis.service;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MediaToolResolver {
    private MediaToolResolver() {
    }

    public static String resolve(String executable) {
        String homebrewPath = "/opt/homebrew/bin/" + executable;
        if (Files.isExecutable(Path.of(homebrewPath))) return homebrewPath;
        return executable;
    }
}
