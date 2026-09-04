package com.youtube.analytics.videoanalysis.render;

import com.youtube.analytics.videoanalysis.config.AudioMixProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioMixServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void skipsMixingWhenMusicIsNotConfigured() {
        AudioMixService service = new AudioMixService(new AudioMixProperties(null, null, 0.12, true));
        Path video = tempDirectory.resolve("edit.mp4");

        assertThat(service.mix(video)).isEqualTo(video);
    }

    @Test
    void rejectsMissingConfiguredMusicFile() {
        AudioMixService service = new AudioMixService(
                new AudioMixProperties(tempDirectory.toString(), "missing.mp3", 0.12, true));

        assertThrows(IllegalArgumentException.class, () -> service.mix(tempDirectory.resolve("edit.mp4")));
    }

    @Test
    void acceptsAudioConfigurationWithoutReadingMusicDuringConstruction() throws Exception {
        Path music = tempDirectory.resolve("music.mp3");
        Files.writeString(music, "placeholder");
        AudioMixProperties properties = new AudioMixProperties(tempDirectory.toString(), "music.mp3", 0.20, false);

        assertThat(properties.musicDirectory()).isEqualTo(tempDirectory.toString());
        assertThat(properties.musicFileName()).isEqualTo("music.mp3");
        assertThat(properties.musicVolume()).isEqualTo(0.20);
        assertThat(properties.duckSpeech()).isFalse();
    }
}
