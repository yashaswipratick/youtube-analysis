# YouTube Analytics API

Spring Boot REST API for Google OAuth and YouTube Studio Analytics, including channel/video reports, retention, traffic sources, geography, discovery analysis, recommendations, and AI-assisted analysis of supplied analytics evidence.

## Run

Requires Java 17 and Maven. Set `youtube.oauth.credentials-file` to a local JSON file containing the Google OAuth `client-id` and `client-secret`, then run:

```sh
mvn spring-boot:run
```

The API is rooted at `/api/youtube/analytics`. OpenAPI documentation is available at `/swagger-ui/index.html` while the application is running.

OpenAI analysis is optional and uses `OPENAI_API_KEY_FILE`, `OPENAI_BASE_URL`, and `OPENAI_MODEL`.
