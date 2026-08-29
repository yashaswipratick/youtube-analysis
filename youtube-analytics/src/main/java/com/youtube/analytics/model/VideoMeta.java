package com.youtube.analytics.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class VideoMeta {
    private String videoId;
    private String title;
    private String publishedAt;
}
