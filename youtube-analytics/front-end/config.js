// Frontend-only runtime config. Change this value when the backend host/port changes.
// Spring Boot does not serve this file; it is served by the independent frontend server.
window.YOUTUBE_ANALYTICS_CONFIG = Object.freeze({
  apiBaseUrl: `http://${window.location.hostname === "127.0.0.1" ? "127.0.0.1" : "localhost"}:8080`
});
