package com.youtube.analytics.videoanalysis.analyzer;

import com.youtube.analytics.videoanalysis.model.VisualObservation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "openai", name = "visual-analysis-enabled", havingValue = "false")
public class BasicVisualSemanticAnalyzer implements VisualSemanticAnalyzer {
    @Override
    public VisualObservation analyze(Path imageFile) {
        try {
            BufferedImage image = ImageIO.read(imageFile.toFile());
            if (image == null) throw new IllegalStateException("Unable to decode extracted frame");
            double quality = image.getWidth() * (long) image.getHeight() >= 1920L * 1080L ? 0.9 : 0.75;
            return new VisualObservation("Video frame, " + image.getWidth() + "x" + image.getHeight(), List.of(), "unknown", quality);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to analyze extracted frame", ex);
        }
    }
}
