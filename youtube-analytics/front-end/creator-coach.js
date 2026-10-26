(() => {
  "use strict";

  const API_ORIGIN = String(window.YOUTUBE_ANALYTICS_CONFIG?.apiBaseUrl || "http://localhost:8080").trim().replace(/\/+$/, "");
  const API = "/api/youtube-analytics/v1";
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
  const app = $("#cc-app");
  const sidebar = $("#cc-sidebar");
  const toast = $("#cc-toast");
  const dialog = $("#cc-dialog");
  const state = {
    page: "videos", auth: null, videos: [], profile: null, progress: null,
    currentVideoId: "", currentReportId: "", currentPlanId: "",
    report: null, transcript: null, inputs: null, curve: null,
    findingsFilter: "all", activeSegment: 0, addStep: 1, videoSearch: "",
    manualDraft: {}, transcriptDraft: "", creatorNotes: "",
    plans: [], planDraft: null, plannerTimer: null, answerTimer: null,
    draftTimer: null, glossary: [], lastFocus: null, reportHistory: [],
    busy: false
  };
  const escapeHtml = value => String(value ?? "").replace(/[&<>"']/g, char => ({
    "&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;", "'":"&#39;"
  }[char]));
  const list = value => Array.isArray(value) ? value : [];
  const object = value => value && typeof value === "object" && !Array.isArray(value) ? value : {};
  const number = value => value === null || value === undefined || value === "" || !Number.isFinite(Number(value)) ? null : Number(value);
  const sumFirst = (...values) => values.find(value => value !== null && value !== undefined && value !== "");
  const fmtNumber = value => number(value) === null ? "Not available yet" : new Intl.NumberFormat("en-IN", {maximumFractionDigits:0}).format(Number(value));
  const fmtPercent = (value, digits = 1) => number(value) === null ? "Not available yet" : new Intl.NumberFormat("en-IN", {maximumFractionDigits:digits, minimumFractionDigits:0}).format(Number(value)) + "%";
  const fmtDuration = value => {
    const seconds = number(value);
    if (seconds === null) return "Not available yet";
    const rounded = Math.max(0, Math.round(seconds));
    const minutes = Math.floor(rounded / 60);
    const remaining = rounded % 60;
    if (!minutes) return remaining + " s";
    if (!remaining) return minutes + " min";
    return minutes + " min " + remaining + " s";
  };
  const fmtDate = value => {
    if (!value) return "Upload date unavailable";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value).slice(0, 10);
    return new Intl.DateTimeFormat("en-GB", {day:"2-digit", month:"short", year:"numeric"}).format(date);
  };
  const stamp = value => {
    const seconds = number(value);
    if (seconds === null) return "Time unavailable";
    const s = Math.max(0, Math.round(seconds));
    if (s >= 3600) return String(Math.floor(s / 3600)).padStart(2,"0") + ":" + String(Math.floor((s % 3600) / 60)).padStart(2,"0") + ":" + String(s % 60).padStart(2,"0");
    return String(Math.floor(s / 60)).padStart(2,"0") + ":" + String(s % 60).padStart(2,"0");
  };
  const friendlyError = error => {
    const raw = String(error?.message || "");
    if (/401|sign.?in|authentication|connect.*youtube|reconnect/i.test(raw)) return "Please connect or reconnect YouTube, then try again.";
    if (/403|not owned|forbidden/i.test(raw)) return "This video does not appear to belong to the connected YouTube channel.";
    if (/429|daily analysis limit/i.test(raw)) return "You have reached today's analysis limit. Please try again tomorrow.";
    if (/failed to fetch|network|load failed/i.test(raw)) return "We could not reach your video numbers. Check your connection and try again.";
    return raw || "Something went wrong. Please try again.";
  };
  const query = params => new URLSearchParams(Object.entries(params || {}).filter(([, value]) => value !== undefined && value !== null && value !== "")).toString();

  async function request(path, options = {}) {
    const url = path.startsWith("http") ? path : API_ORIGIN + API + path;
    const init = {credentials:"include", headers:{Accept:"application/json"}, ...options};
    if (init.body && typeof init.body !== "string") {
      init.headers = {...init.headers, "Content-Type":"application/json"};
      init.body = JSON.stringify(init.body);
    }
    const response = await fetch(url, init);
    if (response.redirected && !response.url.includes(location.host)) throw new Error("Please connect or reconnect YouTube.");
    const text = await response.text();
    let body = {};
    try { body = text ? JSON.parse(text) : {}; } catch { body = {message:text.slice(0,300)}; }
    if (!response.ok) throw new Error(body.message || body.detail || body.error || ("Request failed (" + response.status + ")."));
    if (body && body.success === false) throw new Error(body.message || "The request did not complete.");
    return body && Object.prototype.hasOwnProperty.call(body, "data") ? body.data : body;
  }
  const get = (path, params) => request(path + (params && Object.keys(params).length ? "?" + query(params) : ""));
  const post = (path, body = {}) => request(path, {method:"POST", body});
  const put = (path, body = {}) => request(path, {method:"PUT", body});
  const deleteRequest = path => request(path, {method:"DELETE"});
  const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
  function notify(message) {
    toast.textContent = message;
    toast.classList.add("show");
    clearTimeout(notify.timer);
    notify.timer = setTimeout(() => toast.classList.remove("show"), 3500);
  }
  function setBusy(isBusy) {
    state.busy = isBusy;
    app.setAttribute("aria-busy", String(isBusy));
  }
  function skeleton() {
    app.innerHTML = `<div class="cc-skeleton-page" aria-label="Loading your videos">
      <div class="cc-skeleton cc-skeleton-short"></div><div class="cc-skeleton cc-skeleton-title"></div>
      <div class="cc-grid two"><div class="cc-skeleton cc-skeleton-card"></div><div class="cc-skeleton cc-skeleton-card"></div></div>
      <div class="cc-skeleton cc-skeleton-card"></div></div>`;
    app.setAttribute("aria-busy","true");
  }
  function errorScreen(error, retryPage = state.page) {
    app.innerHTML = `<div class="cc-page-head"><div><div class="cc-eyebrow">YOUR CREATOR WORKSPACE</div><h1>We could not load this page</h1><p>Your saved work is still separate from this screen. Try again after checking your connection.</p></div></div>
      <div class="cc-error"><strong>Let's try again.</strong><p>${escapeHtml(friendlyError(error))}</p><button class="cc-btn primary" data-retry-page="${escapeHtml(retryPage)}">Try again</button></div>`;
    app.setAttribute("aria-busy","false");
  }
  function pageHead(title, subtitle, actions = "") {
    return `<div class="cc-page-head"><div><div class="cc-eyebrow">CREATOR STORY COACH</div><h1>${escapeHtml(title)}</h1><p>${escapeHtml(subtitle)}</p></div><div class="cc-page-actions">${actions}</div></div>`;
  }
  function card(title, subtitle, body, extraClass = "") {
    return `<section class="cc-card ${extraClass}"><div class="cc-card-head"><div><h2>${escapeHtml(title)}</h2>${subtitle ? `<p class="cc-card-sub">${escapeHtml(subtitle)}</p>` : ""}</div></div>${body}</section>`;
  }
  function button(label, action, cls = "secondary", attrs = "") {
    return `<button type="button" class="cc-btn ${cls}" data-action="${escapeHtml(action)}" ${attrs}>${label}</button>`;
  }
  function emptyState(title, text, actionLabel = "Add a new video to check", action = "add-video") {
    return `<div class="cc-empty"><div class="cc-empty-icon" aria-hidden="true">✳</div><h2>${escapeHtml(title)}</h2><p>${escapeHtml(text)}</p>${button(escapeHtml(actionLabel), action, "primary")}</div>`;
  }
  function videoIdFromInput(value) {
    const raw = String(value || "").trim();
    if (/^[A-Za-z0-9_-]{11}$/.test(raw)) return raw;
    try {
      const url = new URL(raw);
      if (url.hostname === "youtu.be" || url.hostname.endsWith(".youtu.be")) return url.pathname.split("/").filter(Boolean)[0] || "";
      if (url.searchParams.get("v")) return url.searchParams.get("v");
      const parts = url.pathname.split("/").filter(Boolean);
      const marker = parts.findIndex(part => ["shorts","embed","live"].includes(part));
      if (marker >= 0) return parts[marker + 1] || "";
    } catch {}
    const match = raw.match(/(?:v=|youtu\.be\/|shorts\/|embed\/)([A-Za-z0-9_-]{11})/);
    return match ? match[1] : "";
  }
  function videoKey(video) { return String(sumFirst(video.videoId, video.id, video.video_id, "")); }
  function videoTitle(video) { return String(sumFirst(video.title, video.name, video.videoTitle, "Untitled video")); }
  function videoThumbnail(video) { return String(sumFirst(video.thumbnail_url, video.thumbnailUrl, video.thumbnail, video.thumbnailURL, "")); }
  function videoPublished(video) { return sumFirst(video.publish_date, video.publishedAt, video.published_at, video.publishDate); }
  function videoDurationLabel(video) {
    if (video.duration_formatted) return String(video.duration_formatted);
    const seconds = number(sumFirst(video.duration_seconds, video.durationSeconds));
    if (seconds !== null) return fmtDuration(seconds);
    const duration = String(video.duration || "");
    return /^PT/i.test(duration) ? "Duration unavailable" : duration;
  }
  function videoHref(id, seconds = 0) {
    return "https://www.youtube.com/watch?v=" + encodeURIComponent(id) + (number(seconds) !== null ? "&t=" + Math.max(0, Math.round(Number(seconds))) + "s" : "");
  }
  function statusChip(video) {
    const status = String(video.status || "");
    if (status === "TOO_NEW") return `<span class="cc-chip wait">Too new · wait 3 days</span>`;
    if (status === "NEEDS_YOUR_NUMBERS") return `<span class="cc-chip wait">Needs your numbers</span>`;
    if (status === "REPORT_READY" && video.latestReportId) return `<span class="cc-chip ready">Report ready</span>`;
    return `<span class="cc-chip accent">Ready to check</span>`;
  }
  function navTo(page) {
    state.page = page;
    const titles = {videos:"My videos", add:"Add a video", report:"Your video report", progress:"My progress", planner:"Next video planner", style:"My style", help:"Help & word meanings"};
    $("#cc-page-title").textContent = titles[page] || "Your workspace";
    $$(".cc-nav-item").forEach(item => item.classList.toggle("active", item.dataset.page === page));
    sidebar.classList.remove("open");
    loadPage(page);
  }
  async function checkAuth() {
    try {
      state.auth = await get("/auth/status");
    } catch (error) {
      state.auth = {authenticated:false, connected:false, reconnectRequired:true, message:friendlyError(error)};
    }
    const connected = Boolean(state.auth?.authenticated && state.auth?.connected);
    $("#cc-auth-dot").classList.toggle("warn", !connected);
    $("#cc-auth-title").textContent = connected ? "YouTube connected" : "Connect YouTube";
    $("#cc-auth-copy").textContent = connected ? "Private video numbers stay in your account." : (state.auth?.message || "Connect your channel to open your videos.");
    if (!connected) {
      app.innerHTML = `${pageHead("Your stories, clearer","A calm workspace to understand your own videos, protect your storytelling style and practise one improvement at a time.")}
        <div class="cc-card cc-progress-locked"><div class="cc-brand-mark" style="margin:0 auto"><svg viewBox="0 0 44 44" aria-hidden="true"><rect x="1" y="1" width="42" height="42" rx="14" fill="currentColor" opacity=".15"/><path d="M12 25.5c5-1.5 6-10 11-10s6 12.5 10 12.5" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"/></svg></div><h2>Connect your YouTube channel</h2><p class="cc-muted">We use the connected account to find your videos and available private numbers. Your access tokens are never shown here.</p>
          <a class="cc-btn primary" href="${escapeHtml(API_ORIGIN + API + "/oauth/start")}">Connect or reconnect YouTube ↗</a></div>`;
      app.setAttribute("aria-busy","false");
      return false;
    }
    return true;
  }
  async function loadPage(page) {
    navToTitleOnly(page);
    skeleton();
    try {
      const connected = state.auth?.authenticated && state.auth?.connected;
      if (!connected && page !== "help") {
        const okay = await checkAuth();
        if (!okay) return;
      }
      if (page === "videos") return await renderVideos();
      if (page === "add") return await renderAdd();
      if (page === "report") return await renderReport();
      if (page === "progress") return await renderProgress();
      if (page === "planner") return await renderPlanner();
      if (page === "style") return await renderStyle();
      if (page === "help") return await renderHelp();
      return await renderVideos();
    } catch (error) {
      errorScreen(error, page);
    } finally {
      app.setAttribute("aria-busy","false");
    }
  }
  function navToTitleOnly(page) {
    state.page = page;
    const titles = {videos:"My videos", add:"Add a video", report:"Your video report", progress:"My progress", planner:"Next video planner", style:"My style", help:"Help & word meanings"};
    $("#cc-page-title").textContent = titles[page] || "Your workspace";
    $$(".cc-nav-item").forEach(item => item.classList.toggle("active", item.dataset.page === page));
  }
  async function fetchVideos(force = false) {
    if (!force && state.videos.length) return state.videos;
    const result = await get("/videos");
    state.videos = list(result.videos);
    return state.videos;
  }
  async function fetchProfile(force = false) {
    if (!force && state.profile) return state.profile;
    state.profile = await get("/profile");
    return state.profile;
  }
  async function fetchProgress(force = false) {
    if (!force && state.progress) return state.progress;
    state.progress = await get("/progress");
    return state.progress;
  }
  function focusFromReport(report) {
    return {
      reportId: report.id, videoId: report.videoId, title: report.title,
      action: object(report.fix_first).action || "Choose one small improvement for your next video.",
      why: object(report.fix_first).why || "",
      status: report.fixFirstStatus || "NOT_YET",
      remind: Boolean(report.remindBeforeNextVideo)
    };
  }
  async function loadLatestFocus() {
    try {
      const progress = await fetchProgress(true);
      const trend = list(progress.trend).slice().reverse();
      for (const item of trend) {
        if (!item.reportId) continue;
        try {
          const report = await get("/reports/" + encodeURIComponent(item.reportId));
          if (report.fix_first) {
            state.lastFocus = focusFromReport(report);
            return state.lastFocus;
          }
        } catch {}
      }
    } catch {}
    state.lastFocus = null;
    return null;
  }
  async function renderVideos() {
    const [videos] = await Promise.all([fetchVideos(true)]);
    let focus = state.lastFocus;
    if (focus === null) focus = await loadLatestFocus();
    const addAction = button("＋ Add a new video to check", "add-video", "primary");
    const listHtml = videos.length ? `<div class="cc-video-list">${videos.map(video => {
      const id = videoKey(video);
      const reportId = video.latestReportId;
      const thumb = videoThumbnail(video);
      const action = reportId ? "open-report" : "select-video";
      const label = reportId ? "Open report" : "Check video";
      const status = statusChip(video);
      return `<article class="cc-video-row">
        ${thumb ? `<img class="cc-thumb" loading="lazy" src="${escapeHtml(thumb)}" alt="Thumbnail for ${escapeHtml(videoTitle(video))}">` : `<div class="cc-thumb cc-thumb-fallback" role="img" aria-label="No thumbnail available">▶</div>`}
        <div class="cc-grow"><span class="cc-video-title">${escapeHtml(videoTitle(video))}</span><div class="cc-video-meta">${escapeHtml(fmtDate(videoPublished(video)))} ${videoDurationLabel(video) ? " · " + escapeHtml(videoDurationLabel(video)) : ""}</div><div style="margin-top:8px">${status}</div></div>
        <div class="cc-video-actions">${button(escapeHtml(label), action, "secondary small", `data-video-id="${escapeHtml(id)}" ${reportId ? `data-report-id="${escapeHtml(reportId)}"` : ""}`)}</div>
      </article>`;
    }).join("")}</div>` : emptyState("Your first video is waiting","Connect a video from your channel. We’ll look at your story and available numbers, then choose one useful next step.");
    app.innerHTML = `${pageHead("My videos","A simple place to review your travel videos, see what needs attention and keep track of one change at a time.",addAction)}
      ${focus ? `<section class="cc-card cc-focus-card" style="margin-bottom:18px"><div class="cc-focus-label">✳ Your current focus</div><div class="cc-row" style="align-items:flex-start"><div class="cc-grow"><h2>${escapeHtml(focus.action)}</h2><p>${escapeHtml(focus.title || "From your latest video report")}</p>${focus.why ? `<p class="cc-small" style="margin-top:9px">Our best guess: ${escapeHtml(focus.why)}</p>` : ""}</div><span class="cc-chip ${focus.status === "DONE" ? "ready" : "accent"}">${focus.status === "DONE" ? "Done" : "Not yet"}</span></div><div class="cc-focus-actions">${focus.status === "DONE" ? "" : button("Mark as done", "focus-done", "mint")}${focus.reportId ? button("Open this report", "open-report", "secondary", `data-report-id="${escapeHtml(focus.reportId)}"`) : ""}</div></section>` : ""}
      <div class="cc-card"><div class="cc-card-head"><div><h2>Your uploads</h2><p class="cc-card-sub">A video needs at least three days of data before we try to analyse its performance.</p></div><label class="cc-field cc-filter-input"><span class="cc-label">Find a video</span><input id="cc-video-search" type="search" placeholder="Search by title" value="${escapeHtml(state.videoSearch)}"></label></div>
        <div id="cc-video-list">${listHtml}</div>
      </div>`;
    filterVideoList();
  }
  function filterVideoList() {
    const field = $("#cc-video-search");
    const listRoot = $("#cc-video-list");
    if (!field || !listRoot) return;
    field.addEventListener("input", () => {
      state.videoSearch = field.value;
      const needle = field.value.trim().toLowerCase();
      $$(".cc-video-row", listRoot).forEach(row => row.classList.toggle("cc-hidden", !row.textContent.toLowerCase().includes(needle)));
    });
  }
  async function startAdd(videoId = "") {
    state.currentVideoId = videoId || "";
    state.inputs = null; state.report = null; state.currentReportId = "";
    state.addStep = 1; state.transcriptDraft = ""; state.creatorNotes = ""; state.manualDraft = {};
    navTo("add");
  }
  function normalizeInputVideo(input) {
    const resource = object(input);
    const video = object(resource.video);
    return {
      id: String(sumFirst(video.videoId, video.id, state.currentVideoId, "")),
      title: String(sumFirst(video.title, "Your selected video")),
      thumbnail: String(sumFirst(video.thumbnail_url, video.thumbnailUrl, video.thumbnail, "")),
      published: sumFirst(video.publish_date, video.publishedAt),
      duration: sumFirst(video.duration_seconds, video.duration_ms ? Number(video.duration_ms)/1000 : null),
      age: number(resource.videoAgeDays),
      status: resource.status || "NEEDS_YOUR_NUMBERS"
    };
  }
  async function loadSelectedInputs(videoId) {
    if (!videoId || !/^[A-Za-z0-9_-]{11}$/.test(videoId)) throw new Error("Paste a valid YouTube video link or its 11-character video ID.");
    state.currentVideoId = videoId;
    const data = await get("/videos/" + encodeURIComponent(videoId) + "/inputs", {lastNDays:365});
    state.inputs = data;
    state.transcript = object(data.transcript);
    state.transcriptDraft = String(data.transcript?.text || "");
    state.manualDraft = {...object(data.manualMetrics)};
    state.creatorNotes = String(state.manualDraft.creator_notes || "");
    try {
      const draftText = localStorage.getItem("creator-coach-draft:" + videoId);
      if (draftText) {
        const draft = JSON.parse(draftText);
        if (draft && draft.savedAt && Date.now() - new Date(draft.savedAt).getTime() < 7 * 86400000) {
          state.manualDraft = {...state.manualDraft, ...object(draft.manual)};
          if (typeof draft.transcript === "string") state.transcriptDraft = draft.transcript;
          if (typeof draft.notes === "string") state.creatorNotes = draft.notes;
        }
      }
    } catch {}
    return data;
  }
  async function renderAdd() {
    const videos = await fetchVideos();
    let selected = state.inputs ? normalizeInputVideo(state.inputs) : null;
    if (state.currentVideoId && !selected) {
      try { await loadSelectedInputs(state.currentVideoId); selected = normalizeInputVideo(state.inputs); } catch {}
    }
    const step = state.addStep;
    const steps = `<div class="cc-stepper">
      <div class="cc-step ${step===1?"active":step>1?"done":""}"><b>1</b><span>Pick your video</span></div>
      <div class="cc-step ${step===2?"active":step>2?"done":""}"><b>2</b><span>Add optional numbers</span></div>
      <div class="cc-step ${step===3?"active":""}"><b>3</b><span>Check my video</span></div></div>
      <div class="cc-progress-track"><span style="width:${step/3*100}%"></span></div>`;
    let body = "";
    if (step === 1) {
      body = `<div class="cc-grid two"><div class="cc-field"><label for="cc-video-url">Paste a YouTube link or video ID</label><input id="cc-video-url" type="text" placeholder="https://www.youtube.com/watch?v=…" value="${escapeHtml(state.currentVideoId)}"><small>Use a video from the YouTube channel currently connected to this account.</small></div><div class="cc-field"><label for="cc-add-search">Or find one of your uploads</label><input id="cc-add-search" type="search" placeholder="Search your video titles"><div class="cc-small">${videos.length} recent uploads available in this list.</div></div></div>
        <div class="cc-video-list" id="cc-add-video-list" style="margin-top:17px">${renderPickRows(videos, state.currentVideoId)}</div>
        <div class="cc-form-actions"><span class="cc-small">The app checks that the video belongs to your connected channel.</span>${button("Continue to numbers →", "add-next-step", "primary", 'id="cc-step1-continue"')}</div>`;
    } else if (step === 2) {
      const video = selected;
      const m = state.manualDraft;
      const help = state.inputs?.howToGetMissingInputs || {};
      body = `${video ? `<div class="cc-preview"><img src="${escapeHtml(video.thumbnail || "")}" alt="Thumbnail for ${escapeHtml(video.title)}"><div><div class="cc-chip accent">Selected video</div><div class="cc-preview-title" style="margin-top:8px">${escapeHtml(video.title)}</div><p>${escapeHtml(fmtDate(video.published))} ${video.duration != null ? " · " + escapeHtml(fmtDuration(video.duration)) : ""}</p>${video.age !== null && video.age < 3 ? '<div class="cc-chip wait">Too new · wait 3 days</div>' : ""}</div></div>` : ""}
        ${video && video.age !== null && video.age < 3 ? '<div class="cc-warning-banner neutral" style="margin-top:14px"><span class="cc-banner-icon">◷</span><div><strong>Too early to judge.</strong>Please wait until the video is at least three days old before asking for a report.</div></div>' : ""}
        <div class="cc-divider"></div><h2 style="font:750 18px Manrope;margin:0 0 5px">Add numbers from YouTube Studio</h2><p class="cc-card-sub" style="margin-bottom:16px">Every field below is optional. The analysis can still run when Studio has not provided a number.</p>
        <div class="cc-grid two">
          ${manualField("cc-impressions","Times your thumbnail was shown","thumbnail_impressions",m.thumbnail_impressions,"number","e.g. 1,250","impressions",help)}
          ${manualField("cc-click-percent","Out of people who saw your thumbnail, how many clicked","thumbnail_click_percent",m.thumbnail_click_percent,"number","e.g. 3.8","click",help)}
          ${manualField("cc-30s-percent","People who stayed past 30 seconds","stayed_past_30s_percent",m.stayed_past_30s_percent,"number","e.g. 62","stayed",help)}
          ${manualField("cc-intro-percent","People who watched the intro","intro_watched_percent",m.intro_watched_percent,"number","e.g. 75","intro",help)}
          ${manualField("cc-thumbnail-desc","What does your thumbnail show?","thumbnail_description",m.thumbnail_description,"text","One sentence about the image","thumbnail",help)}
          <div class="cc-field"><label for="cc-creator-notes">Optional notes · How did you start this video?</label><textarea id="cc-creator-notes" rows="3" placeholder="For example: a cinematic Hindi voice-over, then I speak on camera about why I came…">${escapeHtml(state.creatorNotes)}</textarea><small>These notes help the coach understand which parts are on-camera and which are voice-over.</small></div>
        </div>
        <details class="cc-details" style="margin-top:17px"><summary>Edit or add your transcript</summary><div class="cc-details-body"><p>We need at least about 100 words for a full story review. Keep timestamps such as [00:35] if you have them.</p><div class="cc-field"><label for="cc-transcript">Video transcript</label><textarea id="cc-transcript" rows="12" maxlength="60000" placeholder="Paste the complete transcript here…">${escapeHtml(state.transcriptDraft)}</textarea><small id="cc-transcript-count">${state.transcriptDraft.length.toLocaleString("en-IN")} / 60,000 characters · ${list(state.transcript?.segments).length} timestamped lines may be available.</small></div><div class="cc-form-actions">${button("Save transcript", "save-transcript", "secondary")}</div></div></details>
        <div class="cc-form-actions"><button type="button" class="cc-btn ghost" data-action="add-back-step">← Back</button>${button("Save numbers & continue →", "save-inputs-next", "primary")}</div>`;
    } else {
      const transcriptWords = Number(state.inputs?.transcript?.wordCount || wordCount(state.transcriptDraft));
      body = `<div class="cc-preview">${selected?.thumbnail ? `<img src="${escapeHtml(selected.thumbnail)}" alt="Selected video thumbnail">` : '<div class="cc-thumb cc-thumb-fallback" role="img" aria-label="No thumbnail">▶</div>'}<div><div class="cc-preview-title">${escapeHtml(selected?.title || "Your selected video")}</div><p>${escapeHtml(selected ? fmtDate(selected.published) : "")} ${selected?.duration != null ? " · " + escapeHtml(fmtDuration(selected.duration)) : ""}</p><p>${transcriptWords} words in the saved transcript.</p></div></div>
        <div class="cc-warning-banner" style="margin-top:17px"><span class="cc-banner-icon">✦</span><div><strong>Reading your story...</strong>We’ll map the opening, check how the video delivers its title, look at the numbers available, and choose one useful change for your next upload.</div></div>
        ${transcriptWords < 100 ? '<div class="cc-warning-banner neutral"><span class="cc-banner-icon">◷</span><div><strong>Add a complete transcript first.</strong>We need about 100 words for a full story review. Paste the transcript in the previous step, then come back here.</div></div>' : ""}
        <p class="cc-small">We won’t copy another creator or suggest removing the storytelling + on-camera style you want to keep.</p>
        <div class="cc-form-actions"><button type="button" class="cc-btn ghost" data-action="add-back-step">← Back</button>${button("Check my video", "run-analysis", "primary", 'id="cc-run-analysis"')}</div>`;
    }
    app.innerHTML = `${pageHead("Add a video","Three small steps. You can fill in as much or as little Studio data as you have.")}${steps}<section class="cc-card"><div class="cc-card-head"><div><h2>${step===1?"Step 1 · Pick the video":step===2?"Step 2 · Add optional numbers":"Step 3 · Check your video"}</h2><p class="cc-card-sub">${step===1?"Choose an upload from your channel.":step===2?"Your own notes help us give the report more context.":"The report will show what happened and one thing to improve first."}</p></div></div>${body}</section>`;
    if (step === 1) bindAddSearch(videos);
    if (step === 2) bindInputDrafts();
    if (step === 3) {
      const tooNew = selected?.age !== null && selected?.age !== undefined && selected.age < 3;
      $("#cc-run-analysis")?.toggleAttribute("disabled", tooNew || transcriptWords < 100);
      if (tooNew) $("#cc-run-analysis")?.setAttribute("title", "Wait until the video is at least three days old.");
      if (transcriptWords < 100) $("#cc-run-analysis")?.setAttribute("title", "Add a complete transcript of at least about 100 words.");
    }
  }
  function wordCount(text) { return String(text || "").trim() ? String(text).trim().split(/\s+/).length : 0; }
  function renderPickRows(videos, selectedId) {
    if (!videos.length) return '<div class="cc-empty"><h2>No uploads found</h2><p>Try a different title or paste your YouTube link above.</p></div>';
    return videos.map(video => {
      const id = videoKey(video);
      const active = id === selectedId;
      return `<button type="button" class="cc-video-row" style="width:100%;text-align:left;background:${active?"var(--accent-soft)":"var(--surface)"};border-color:${active?"var(--accent)":"var(--line)"}" data-action="pick-video" data-video-id="${escapeHtml(id)}">
        ${videoThumbnail(video) ? `<img class="cc-thumb" loading="lazy" src="${escapeHtml(videoThumbnail(video))}" alt="Thumbnail for ${escapeHtml(videoTitle(video))}">` : '<div class="cc-thumb cc-thumb-fallback" aria-hidden="true">▶</div>'}
        <div class="cc-grow"><span class="cc-video-title">${escapeHtml(videoTitle(video))}</span><div class="cc-video-meta">${escapeHtml(fmtDate(videoPublished(video)))} ${videoDurationLabel(video) ? " · " + escapeHtml(videoDurationLabel(video)) : ""}</div></div>
        <div class="cc-video-actions">${active ? '<span class="cc-chip accent">Selected</span>' : '<span class="cc-chip neutral">Choose</span>'}</div>
      </button>`;
    }).join("");
  }
  function manualField(id, label, key, value, type, placeholder, helpKey, helpMap) {
    const helpText = helpMap?.[key] || studioHelp(helpKey);
    return `<div class="cc-field"><label for="${id}">${escapeHtml(label)}</label><div class="cc-row"><input id="${id}" data-manual-key="${escapeHtml(key)}" type="${type}" ${type==="number"?'min="0" step="any"':''} placeholder="${escapeHtml(placeholder)}" value="${escapeHtml(value ?? "")}"><button type="button" class="cc-info-btn" data-action="show-help" data-help-key="${escapeHtml(helpKey)}" aria-label="Where to find ${escapeHtml(label)}">?</button></div><small>${escapeHtml(helpText)}</small></div>`;
  }
  function studioHelp(key) {
    const help = {
      impressions:"YouTube Studio → Content → choose your video → Analytics → Reach → thumbnail impressions.",
      click:"YouTube Studio → Content → choose your video → Analytics → Reach → impressions click-through percentage.",
      stayed:"YouTube Studio → Content → choose your video → Analytics → Engagement. Audience details may show how many stayed in the first 30 seconds.",
      intro:"YouTube Studio → Content → choose your video → Analytics → Engagement. Look for the graph showing which moments people watched, if it is available.",
      thumbnail:"Describe the image in your own words. We cannot see your thumbnail unless you tell us what is in it."
    };
    return help[key] || "Open YouTube Studio, select your video, and look under Analytics for this information if available.";
  }
  function bindAddSearch(videos) {
    const search = $("#cc-add-search");
    const box = $("#cc-add-video-list");
    search?.addEventListener("input", () => {
      const needle = search.value.trim().toLowerCase();
      const filtered = videos.filter(video => videoTitle(video).toLowerCase().includes(needle));
      box.innerHTML = renderPickRows(filtered, state.currentVideoId);
    });
    $("#cc-video-url")?.addEventListener("keydown", event => {
      if (event.key === "Enter") {
        event.preventDefault();
        const id = videoIdFromInput(event.currentTarget.value);
        if (!id) return notify("Paste a valid YouTube video link or video ID.");
        loadSelectedInputs(id).then(() => { state.addStep = 2; renderAdd(); }).catch(error => notify(friendlyError(error)));
      }
    });
  }
  function bindInputDrafts() {
    $$("[data-manual-key]").forEach(input => input.addEventListener("input", () => {
      state.manualDraft[input.dataset.manualKey] = input.value;
      scheduleDraftSave();
    }));
    $("#cc-creator-notes")?.addEventListener("input", event => {
      state.creatorNotes = event.currentTarget.value;
      state.manualDraft.creator_notes = state.creatorNotes;
      scheduleDraftSave();
    });
    $("#cc-transcript")?.addEventListener("input", event => {
      state.transcriptDraft = event.currentTarget.value;
      const count = $("#cc-transcript-count");
      if (count) count.textContent = state.transcriptDraft.length.toLocaleString("en-IN") + " / 60,000 characters · " + wordCount(state.transcriptDraft) + " words";
      scheduleDraftSave();
    });
  }
  function scheduleDraftSave() {
    clearTimeout(state.draftTimer);
    const id = state.currentVideoId;
    if (!id) return;
    state.draftTimer = setTimeout(async () => {
      try {
        localStorage.setItem("creator-coach-draft:" + id, JSON.stringify({
          manual: state.manualDraft, transcript: state.transcriptDraft, notes: state.creatorNotes, savedAt: new Date().toISOString()
        }));
      } catch {}
    }, 400);
  }
  async function saveManualDraft() {
    if (!state.currentVideoId) throw new Error("Choose a video first.");
    const payload = {...state.manualDraft, creator_notes: state.creatorNotes};
    for (const key of ["thumbnail_impressions","thumbnail_click_percent","stayed_past_30s_percent","intro_watched_percent"]) {
      const raw = payload[key];
      if (raw === "" || raw === null || raw === undefined) payload[key] = "";
      else payload[key] = Number(raw);
    }
    const data = await put("/videos/" + encodeURIComponent(state.currentVideoId) + "/manual-metrics", payload);
    state.inputs = data;
    state.manualDraft = {...object(data.manualMetrics)};
    state.creatorNotes = String(state.manualDraft.creator_notes || "");
    try { localStorage.removeItem("creator-coach-draft:" + state.currentVideoId); } catch {}
    return data;
  }
  async function saveTranscriptDraft() {
    if (!state.currentVideoId) throw new Error("Choose a video first.");
    if (state.transcriptDraft.length > 60000) throw new Error("The transcript must be 60,000 characters or shorter.");
    const data = await put("/videos/" + encodeURIComponent(state.currentVideoId) + "/transcript", {text:state.transcriptDraft, creator_notes:state.creatorNotes});
    state.transcript = data;
    if (state.inputs) state.inputs.transcript = data;
    state.transcriptDraft = data.text || "";
    return data;
  }
  async function beginAnalysis() {
    setBusy(true);
    app.innerHTML = `${pageHead("Reading your story…","We are checking the transcript and collecting the numbers that are available.")}<div class="cc-card"><div class="cc-stack"><div class="cc-skeleton cc-skeleton-title"></div><div class="cc-skeleton cc-skeleton-card"></div><div class="cc-skeleton cc-skeleton-card"></div><p class="cc-muted">Your report will focus on one practical improvement. This can take a little while.</p></div></div>`;
    try {
      await saveManualDraft();
      await saveTranscriptDraft();
      const started = await post("/videos/" + encodeURIComponent(state.currentVideoId) + "/analyses?lastNDays=365", {});
      if (!started.jobId) {
        if (started.status === "TOO_NEW") throw new Error(started.message || "Too early to judge. Wait until this video is at least three days old.");
        if (started.status === "NEEDS_YOUR_NUMBERS") throw new Error("We need a longer transcript before we can review your story. Add the complete transcript, then try again.");
        throw new Error(started.message || "The report could not be started. Please try again.");
      }
      let job = started;
      for (let attempt = 0; attempt < 90; attempt++) {
        if (["done","failed"].includes(String(job.status || "").toLowerCase())) break;
        await wait(1800);
        job = await get("/analyses/" + encodeURIComponent(started.jobId));
        app.innerHTML = `${pageHead("Reading your story…","A few steps happen in the background so the page stays responsive.")}<div class="cc-card"><div class="cc-warning-banner"><span class="cc-banner-icon">✦</span><div><strong>${escapeHtml(job.progressMessage || "Reading your story…")}</strong>We are checking the title promise, transcript evidence and available numbers. We never invent missing data.</div></div><div class="cc-skeleton cc-skeleton-title"></div><div class="cc-skeleton cc-skeleton-card" style="margin-top:16px"></div><p class="cc-small">Step ${attempt + 1} of the progress check. You can leave this page and return to your videos later.</p></div>`;
      }
      if (String(job.status).toLowerCase() !== "done" || !job.reportId) throw new Error(job.error || "We could not finish the report. Check your transcript and try again.");
      state.currentReportId = job.reportId;
      state.currentVideoId = job.videoId || state.currentVideoId;
      state.report = null; state.curve = null;
      state.lastFocus = null;
      notify("Your story report is ready.");
      navTo("report");
    } catch (error) {
      app.innerHTML = `${pageHead("We could not finish the report","Your draft is still available. Please check the message below.")}<div class="cc-error"><strong>Let's try one small thing.</strong><p>${escapeHtml(friendlyError(error))}</p>${button("Back to your video inputs", "back-to-inputs", "secondary")}</div>`;
    } finally {
      setBusy(false);
      app.setAttribute("aria-busy","false");
    }
  }
  async function openVideoFromList(videoId) {
    state.currentReportId = "";
    state.report = null;
    await loadSelectedInputs(videoId);
    state.addStep = 2;
    navTo("add");
  }
  async function loadReportData() {
    if (!state.currentReportId && state.currentVideoId) {
      const report = await get("/videos/" + encodeURIComponent(state.currentVideoId) + "/report");
      state.currentReportId = report.id; state.report = report;
    } else if (state.currentReportId) {
      state.report = await get("/reports/" + encodeURIComponent(state.currentReportId));
    } else {
      throw new Error("Choose a video report first.");
    }
    state.currentVideoId = String(state.report.videoId || state.currentVideoId);
    const requests = [
      get("/videos/" + encodeURIComponent(state.currentVideoId) + "/inputs", {lastNDays:365}).catch(() => null),
      get("/reports/" + encodeURIComponent(state.currentReportId) + "/audience-curve").catch(() => ({available:false,points:[],moments:[],message:"We could not load the audience graph."}))
    ];
    const [inputs, curve] = await Promise.all(requests);
    if (inputs) {
      state.inputs = inputs;
      state.transcript = object(inputs.transcript);
    } else {
      state.transcript = {segments:[],text:""};
    }
    state.curve = curve;
    return state.report;
  }
  function reportVideoLink(seconds) {
    return videoHref(state.currentVideoId, seconds);
  }
  function excerptFor(start, end, limit = 330) {
    const cues = list(state.transcript?.segments).filter(cue => {
      const sec = number(cue.start_sec);
      return sec !== null && sec >= Number(start) && sec <= Number(end);
    });
    const text = cues.map(cue => String(cue.text || "")).join(" ").trim();
    if (text) return text.length > limit ? text.slice(0, limit).trimEnd() + "…" : text;
    const matching = list(state.transcript?.segments).find(cue => {
      const sec = number(cue.start_sec);
      return sec !== null && sec <= Number(end) && sec >= Number(start) - 8;
    });
    if (matching?.text) return String(matching.text).slice(0, limit) + (String(matching.text).length > limit ? "…" : "");
    return "The transcript does not include a timestamped line for this exact part.";
  }
  const segmentPalette = ["#b6a0ff","#8dc8bc","#9ec3ff","#d8b5e7","#d9bc88","#83c3dc","#c6d3a0","#d5a6a9"];
  function segmentList(report) {
    const segments = list(report.segments);
    const duration = number(report.duration_seconds);
    return segments.map((segment, index) => {
      const start = number(segment.start_sec) ?? 0;
      const end = number(segment.end_sec) ?? start;
      const seconds = Math.max(0, end - start);
      const percent = number(segment.percent_of_runtime) ?? (duration > 0 ? seconds / duration * 100 : 0);
      return {...segment, sourceIndex:index, start_sec:start, end_sec:end, percent_of_runtime:percent, width:Math.max(.8, percent), color:segmentPalette[index % segmentPalette.length]};
    });
  }
  function renderSegmentMap(report) {
    const segments = segmentList(report);
    if (!segments.length) return `<div class="cc-empty"><h2>No part-by-part map yet</h2><p>The transcript did not provide enough timing information to label the video. You can still use the story notes below.</p></div>`;
    const active = segments[Math.min(state.activeSegment, segments.length - 1)] || segments[0];
    return `<div class="cc-small">Each percentage is the share of your full video's runtime. Select a part to see a short transcript excerpt and open that moment on YouTube.</div>
      <div class="cc-segment-bar" role="group" aria-label="Your video, part by part">${segments.map(segment => `<button type="button" class="cc-segment-piece ${segment.sourceIndex === active.sourceIndex ? "active" : ""}" style="width:${segment.width}%;--seg-color:${segment.color}" data-action="select-segment" data-segment-index="${segment.sourceIndex}" title="${escapeHtml(segment.label)} · ${stamp(segment.start_sec)} to ${stamp(segment.end_sec)}"></button>`).join("")}</div>
      <div class="cc-segment-legend">${segments.map(segment => `<button type="button" class="cc-legend-item" data-action="select-segment" data-segment-index="${segment.sourceIndex}" style="--seg-color:${segment.color}"><span class="cc-legend-dot"></span><span>${escapeHtml(segment.label)}</span><strong>${fmtPercent(segment.percent_of_runtime)}</strong></button>`).join("")}</div>
      <div class="cc-transcript-box"><div class="cc-row"><div class="cc-grow"><h3>${escapeHtml(active.label)}</h3><div class="cc-small">${stamp(active.start_sec)} – ${stamp(active.end_sec)} · ${fmtDuration(active.end_sec - active.start_sec)}</div></div><a class="cc-link" href="${escapeHtml(reportVideoLink(active.start_sec))}" target="_blank" rel="noreferrer">Open this moment ↗</a></div><p>${escapeHtml(active.what_happens || "A part of your journey.")}</p><div class="cc-small">From your transcript</div><p>${escapeHtml(excerptFor(active.start_sec, active.end_sec))}</p></div>`;
  }
  function renderTitleCheck(report) {
    const titleCheck = object(report.title_check);
    const delivered = number(titleCheck.first_delivered_at_sec);
    const percent = number(titleCheck.percent_on_title_promise);
    const pct = percent === null ? null : Math.max(0,Math.min(100,percent));
    return `<div class="cc-title-promise"><div><div class="cc-chip accent">Title promise</div><h3 style="margin-top:10px">${escapeHtml(titleCheck.title_promise || "The title promise is not available in this report.")}</h3></div><div class="cc-delivery-time">${delivered === null ? "First delivery time not clear" : "First delivered around " + stamp(delivered)}</div></div>
      <div class="cc-meter" aria-label="Time marked as delivering the title promise">${pct === null ? "" : `<span style="width:${pct}%"></span>`}</div>
      <div class="cc-row"><strong>${pct === null ? "Time on the promise is unavailable" : fmtPercent(pct,1) + " of the video"} </strong><span class="cc-small">${pct === null ? "We will not guess this number." : "was labelled as delivering the title promise."}</span></div>
      <p class="cc-card-sub" style="margin-top:12px">${escapeHtml(titleCheck.explanation || "Check that the first part of the finished video gives viewers a fair idea of the journey promised by your title.")}</p>`;
  }
  function audienceChart(curve, report) {
    const points = list(curve?.points).map(point => ({sec:number(point.at_sec), value:number(point.relative_watch_percent)})).filter(point => point.sec !== null && point.value !== null);
    if (!curve?.available || points.length < 3) {
      return `<div class="cc-warning-banner"><span class="cc-banner-icon">◌</span><div><strong>We need your audience graph to show this.</strong>${escapeHtml(curve?.message || "Audience data is not available for this video yet.")}<div style="margin-top:9px"><a class="cc-link" href="https://studio.youtube.com/" target="_blank" rel="noreferrer">Open YouTube Studio ↗</a></div></div></div>`;
    }
    const width = 850, height = 255, left = 39, right = 18, top = 22, bottom = 32;
    const maxSec = Math.max(1, ...points.map(point => point.sec));
    const maxValue = Math.max(1, ...points.map(point => point.value));
    const x = sec => left + (width-left-right) * sec / maxSec;
    const y = value => top + (height-top-bottom) * (1 - value / maxValue);
    const d = points.map((point,index) => (index ? "L" : "M") + x(point.sec).toFixed(1) + " " + y(point.value).toFixed(1)).join(" ");
    const moments = list(curve.moments);
    const momentNodes = moments.map(moment => {
      const sec = number(moment.at_sec);
      if (sec === null) return "";
      const nearest = points.reduce((best,point) => Math.abs(point.sec-sec) < Math.abs(best.sec-sec) ? point : best,points[0]);
      const type = moment.type === "drop" ? "drop" : moment.type === "spike" ? "spike" : "plateau";
      return `<circle class="dot ${type}" cx="${x(sec)}" cy="${y(nearest.value)}" r="6" tabindex="0" role="button" data-jump-seconds="${sec}" data-action="inspect-moment" data-moment-id="${escapeHtml(moment.id)}" aria-label="${escapeHtml(moment.type === "drop" ? "Drop" : moment.type === "spike" ? "Rise" : "Steady section")} around ${stamp(sec)}"><title>${stamp(sec)} · ${escapeHtml(type === "drop" ? "Drop to inspect" : type === "spike" ? "Rise to inspect" : "Steady section to inspect")}</title></circle>`;
    }).join("");
    const yTicks = [0,.33,.66,1].map(f => {
      const yy = top + (height-top-bottom)*f;
      return `<line class="grid" x1="${left}" y1="${yy}" x2="${width-right}" y2="${yy}"/>`;
    }).join("");
    return `<svg class="cc-audience-chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="Your available audience data over time. Select a marker to inspect that moment.">${yTicks}<path class="curve" d="${d}"/>${momentNodes}<text x="${left}" y="${height-8}">00:00</text><text x="${width-right}" y="${height-8}" text-anchor="end">${stamp(maxSec)}</text></svg>
      <div class="cc-chart-caption"><span>Real points from your available audience data</span><span>Changes are clues to inspect, not proof of cause.</span></div>
      <div class="cc-curve-moments">${moments.map(moment => {
        const sec = number(moment.at_sec);
        if (sec === null) return "";
        const related = list(report.audience_explanations).find(item => item.moment_id === moment.id);
        const title = moment.type === "drop" ? "A drop appears around " + stamp(sec) : moment.type === "spike" ? "A rise appears around " + stamp(sec) : "A steady stretch appears around " + stamp(sec);
        const amount = number(moment.change_percent_points);
        return `<button type="button" class="cc-moment" data-action="inspect-moment" data-moment-id="${escapeHtml(moment.id)}" data-jump-seconds="${sec}" style="text-align:left;background:var(--surface)">
          <span class="cc-moment-time">${stamp(sec)}</span><span><strong>${escapeHtml(title)}</strong><p>${escapeHtml(related?.what_was_happening || excerptFor(sec,sec+12,220))}</p><p><b>Our best guess:</b> ${escapeHtml(related?.best_guess || "Look at this moment in context. The graph cannot tell us by itself why someone kept watching or left.")}</p>${amount !== null ? `<span class="cc-small">Measured change in the graph: ${fmtNumber(amount)} percentage points. This is not a count of people.</span>` : ""}</span>
        </button>`;
      }).join("")}</div>`;
  }
  function renderStrengths(report) {
    const items = list(report.keep_doing).slice(0,3);
    if (!items.length) return '<div class="cc-empty"><h2>Nothing to quote yet</h2><p>We only show strengths when the report has evidence from your transcript.</p></div>';
    return `<div class="cc-grid ${items.length > 1 ? "two" : ""}">${items.map(item => {
      const quote = item.quote;
      const at = number(item.at_sec);
      return `<article class="cc-strength"><h3>${escapeHtml(item.strength || "Keep this in your style")}</h3>${quote ? `<blockquote class="cc-quote">“${escapeHtml(quote)}”</blockquote>${at !== null ? `<a class="cc-quote-link" href="${escapeHtml(reportVideoLink(at))}" target="_blank" rel="noreferrer">Hear it at ${stamp(at)} ↗</a>` : '<span class="cc-small">Timestamp unavailable</span>'}` : `<p class="cc-small">${escapeHtml(item.note || "The report could not verify a short quote for this strength.")}</p>`}</article>`;
    }).join("")}</div>`;
  }
  const areaLabel = area => ({
    first_30_seconds:"Your first 30 seconds", story:"Story", ending:"Ending",
    title_and_thumbnail:"Title and thumbnail", useful_details:"Useful details", style:"Your style"
  }[area] || "Story");
  const sureLabel = sure => "How sure we are: " + ({very:"Very",fairly:"Fairly",not_very:"Not very"}[sure] || "Not very");
  function renderFindings(report) {
    const all = list(report.findings);
    const options = [{id:"all",label:"All"},{id:"first_30_seconds",label:"First 30 seconds"},{id:"story",label:"Story"},{id:"ending",label:"Ending"},{id:"title_and_thumbnail",label:"Title and thumbnail"}];
    const filtered = state.findingsFilter === "all" ? all : all.filter(item => item.area === state.findingsFilter);
    if (!all.length) return `<div class="cc-empty"><h2>No extra findings</h2><p>The coach has not found another point that adds enough evidence beyond Fix this first.</p></div>`;
    return `<div class="cc-filters" role="group" aria-label="Filter what we noticed">${options.map(option => `<button type="button" data-action="filter-findings" data-filter="${option.id}" class="cc-filter-chip ${state.findingsFilter===option.id?"active":""}" aria-pressed="${state.findingsFilter===option.id}">${escapeHtml(option.label)}</button>`).join("")}</div>
      <div class="cc-stack">${filtered.map(item => `<article class="cc-finding"><div class="cc-row" style="flex-wrap:wrap"><span class="cc-chip">${escapeHtml(areaLabel(item.area))}</span><span class="cc-grow"></span><span class="cc-small">${escapeHtml(sureLabel(item.how_sure))}</span></div><h3 style="margin-top:10px">${escapeHtml(item.title || "What we noticed")}</h3><p>${escapeHtml(item.observation || "")}</p>${list(item.evidence).map(evidence => {
        const at = number(evidence.at_sec);
        return `<div class="cc-transcript-box"><div class="cc-small">Evidence from your transcript</div>${evidence.quote ? `<p style="font-style:italic">“${escapeHtml(evidence.quote)}”</p>` : `<p>${escapeHtml(evidence.note || "The point is about something missing from the transcript, so there is no quote to show.")}</p>`}${at !== null && evidence.quote ? `<a class="cc-link" href="${escapeHtml(reportVideoLink(at))}" target="_blank" rel="noreferrer">Open at ${stamp(at)} ↗</a>` : ""}</div>`;
      }).join("")}<div class="cc-guess"><b>Our best guess:</b> ${escapeHtml(item.best_guess || "We do not have enough evidence to explain this yet.")}</div><span class="cc-sure">${escapeHtml(sureLabel(item.how_sure))}</span></article>`).join("") || '<div class="cc-empty"><p>No findings in this group.</p></div>'}</div>`;
  }
  function renderRecommendations(report) {
    const recommendations = list(report.recommendations).slice(0,2);
    if (!recommendations.length) return '<p class="cc-small">One change is enough for now. Finish Fix this first before adding another experiment.</p>';
    return `<div class="cc-stack">${recommendations.map(item => {
      const experiment = item.tag === "try_and_see";
      return `<article class="cc-rec"><div class="cc-row"><span class="cc-chip ${experiment?"wait":"ready"}">${experiment?"Try it and see":"Backed by your video"}</span><span class="cc-grow"></span><span class="cc-small">${escapeHtml(sureLabel(item.how_sure))}</span></div><h3>${escapeHtml(item.action || "A possible next step")}</h3><p>${escapeHtml(item.reason || "")}</p>${experiment ? `<div class="cc-divider"></div><strong style="font-size:13px">How to test it</strong><ol class="cc-steps">${list(item.test_steps).map(step => `<li>${escapeHtml(step)}</li>`).join("")}</ol><p><b>How you will know it worked:</b> ${escapeHtml(item.success_signal || "Compare a similar next upload; keep other changes as steady as possible.")}</p>` : ""}</article>`;
    }).join("")}</div>`;
  }
  function numberCard(label, value, meaning, key) {
    return `<article class="cc-number"><div class="cc-number-label"><span>${escapeHtml(label)}</span><button type="button" class="cc-info-btn" data-action="metric-help" data-metric="${escapeHtml(key)}" aria-label="Meaning of ${escapeHtml(label)}">?</button></div><div class="cc-number-value">${escapeHtml(value)}</div><div class="cc-number-meaning">${escapeHtml(meaning)}</div></article>`;
  }
  function renderNumbers(report) {
    const m = object(report.metrics);
    const specs = [
      ["views","Views",fmtNumber(m.views),n(m.views) === null ? "YouTube has not supplied a view count for this time window." : "Views in the selected reporting window."],
      ["avg_watch_seconds","How long people watch on average",fmtDuration(m.avg_watch_seconds),n(m.avg_watch_seconds) === null ? "Average watch time is unavailable for this report." : fmtDuration(m.avg_watch_seconds) + " watched per view, on average. This is not an exact stopping point."],
      ["average_watch_percent","How much of your video people watch",fmtPercent(m.average_watch_percent),n(m.average_watch_percent) === null ? "The average share of the video watched is unavailable." : "On average, each view watched about this share of the video."],
      ["impressions","Times your thumbnail was shown",fmtNumber(m.impressions),n(m.impressions) === null ? "This number was not returned by Studio or supplied manually." : "This is how often YouTube counted your thumbnail as shown."],
      ["thumbnail_click_percent","Out of people who saw your thumbnail, how many clicked",fmtPercent(m.thumbnail_click_percent),n(m.thumbnail_click_percent) === null ? "This number was not returned by Studio or supplied manually." : "This percentage helps you assess the title and thumbnail together."],
      ["likes","Likes",fmtNumber(m.likes),n(m.likes) === null ? "Like count is not available here." : "Likes reported for the selected time window when available."],
      ["comments","Comments",fmtNumber(m.comments),n(m.comments) === null ? "Comment count is not available here." : "Comments reported for this video when available."],
      ["shares","Shares",fmtNumber(m.shares),n(m.shares) === null ? "Share count is not available here." : "How often people shared the video."],
      ["new_subscribers","New subscribers",fmtNumber(m.new_subscribers),n(m.new_subscribers) === null ? "YouTube did not return this number for the chosen window." : "Viewers who subscribed after watching this video."],
      ["lost_subscribers","Subscribers lost",fmtNumber(m.lost_subscribers),n(m.lost_subscribers) === null ? "YouTube did not return this number for the chosen window." : "Viewers who unsubscribed in the selected time window."],
      ["stayed_past_30s_percent","People who stayed past 30 seconds",fmtPercent(m.stayed_past_30s_percent),n(m.stayed_past_30s_percent) === null ? "Add this from YouTube Studio if Studio shows it." : "The share of viewers who stayed to the 30-second point."],
      ["intro_watched_percent","People who watched the intro",fmtPercent(m.intro_watched_percent),n(m.intro_watched_percent) === null ? "Add this from YouTube Studio if Studio shows it." : "The intro percentage you supplied or that was available."]
    ];
    return `<div class="cc-numbers">${specs.map(([key,label,value,meaning]) => numberCard(label,value,meaning,key)).join("")}</div>
      <div class="cc-divider"></div><h3 style="margin-bottom:13px">How people found your video</h3>${renderTraffic(m.traffic_share)}`;
  }
  function trafficSourceLabel(value) {
    const key = String(value || "").toUpperCase();
    const labels = {
      YT_SEARCH:"YouTube search", RELATED_VIDEO:"Suggested videos", BROWSE_FEATURES:"Home and browse",
      SHORTS:"Shorts feed", EXTERNAL:"External websites or apps", NOTIFICATION:"Notifications",
      PLAYLIST:"Playlists", CHANNEL:"Channel pages", END_SCREEN:"End screens", NO_LINK_OTHER:"Other YouTube features",
      SUBSCRIBER:"Subscriptions", DIRECT_OR_UNKNOWN:"Direct or unknown", ADVERTISING:"Advertising",
      NO_LINK_EMBEDDED:"Embedded players", LIVE_REDIRECT:"Live redirect", NO_LINK_CHANNEL:"Channel pages",
      NO_LINK_OTHER:"Other YouTube features", NO_LINK_PLAYLIST:"Playlists", NO_LINK_NOTIFICATION:"Notifications"
    };
    return labels[key] || (key ? key.toLowerCase().replace(/_/g," ").replace(/\b\w/g,c=>c.toUpperCase()) : "Other sources");
  }
  function renderTraffic(traffic) {
    const sources = list(object(traffic).sources);
    if (!traffic?.available || !sources.length) return `<div class="cc-warning-banner"><span class="cc-banner-icon">◌</span><div><strong>How people found your video is not available yet.</strong>We do not draw empty or made-up bars. This source breakdown may not be returned for every video or date range.</div></div>`;
    const max = Math.max(1,...sources.map(source => number(source.percent_of_views) || 0));
    return `<div class="cc-bars">${sources.slice(0,8).map(source => {
      const pct = number(source.percent_of_views);
      const label = trafficSourceLabel(source.source_type);
      return `<div class="cc-bar-row"><div class="cc-bar-label">${escapeHtml(label)}</div><div class="cc-bar-track"><span style="width:${pct===null?0:Math.min(100,pct)}%"></span></div><div class="cc-bar-value">${pct===null?"Not available":fmtPercent(pct)}</div></div>`;
    }).join("")}</div><p class="cc-meaning">Bars show the share within the sources returned for this report. Some sources may be unavailable.</p>`;
  }
  async function renderReport() {
    const report = await loadReportData();
    const age = number(report.video_age_days);
    const metrics = object(report.metrics);
    const views = number(metrics.views);
    const early = report.small_sample === true || report.small_sample === "true" || views === null || views < 1000;
    const fix = object(report.fix_first);
    const status = String(report.fixFirstStatus || "NOT_YET");
    const checked = object(report.checklistState);
    const answers = object(report.creatorAnswers);
    const checklist = list(report.next_video_checklist).slice(0,5);
    const videoTitleText = String(report.title || state.inputs?.video?.title || "Your video");
    const video = state.inputs?.video || {};
    const thumb = sumFirst(video.thumbnail_url, video.thumbnailUrl, video.thumbnail, "");
    const topActions = `${button("Download my report (PDF)","download-pdf","secondary")}${button("Print","print-report","ghost")}`;
    app.innerHTML = `${pageHead("Your video report","What happened, what to fix first, and what to try in the next upload.",topActions)}
      <div class="cc-report-head">${thumb ? `<img class="cc-report-thumb" src="${escapeHtml(thumb)}" alt="Thumbnail for ${escapeHtml(videoTitleText)}">` : `<div class="cc-report-thumb cc-thumb-fallback" role="img" aria-label="No thumbnail available">▶</div>`}<div><h1>${escapeHtml(videoTitleText)}</h1><div class="cc-report-meta"><span>${escapeHtml(fmtDate(report.publish_date))}</span><span>${escapeHtml(fmtDuration(report.duration_seconds))}</span><span>${escapeHtml(age === null ? "Age unavailable" : age + " days since upload")}</span><span class="cc-chip ${status==="DONE"?"ready":"accent"}">${status==="DONE"?"Fix completed":status==="SKIPPED"?"Focus skipped":"Focus not yet done"}</span></div></div></div>
      ${early ? `<div class="cc-warning-banner neutral"><span class="cc-banner-icon">◷</span><div><strong>Too early to judge.</strong>${views === null ? "We do not have enough view numbers yet. Treat this report as a transcript review and early hints." : "Only " + fmtNumber(views) + " views are available in the selected time window. Treat the numbers as early hints, not final answers."}</div></div>` : ""}
      <section class="cc-card cc-fix-card">
        <div class="cc-focus-label">✳ Fix this first</div><h2>${escapeHtml(fix.action || "Choose one small improvement for your next video.")}</h2>
        <div class="cc-why"><strong>Our best guess:</strong> ${escapeHtml(fix.why || "This is the most practical next test based on the evidence available.")}</div>
        <h3 style="margin-top:17px">How to do it</h3><ol class="cc-steps">${list(fix.steps).slice(0,4).map(step => `<li>${escapeHtml(step)}</li>`).join("")}</ol>
        <div class="cc-fix-footer">${status==="DONE" ? '<span class="cc-chip ready">Marked as done</span>' : button("✓ Mark as done","fix-done","mint")}${status==="SKIPPED" ? button("Bring focus back","fix-reset","secondary") : ""}<label class="cc-reminder"><input id="cc-remind-next" type="checkbox" ${report.remindBeforeNextVideo ? "checked" : ""}> Remind me before my next video</label><span class="cc-small">How sure we are: ${escapeHtml(({very:"Very",fairly:"Fairly",not_very:"Not very"}[fix.how_sure] || "Not very"))}</span></div>
      </section>
      ${card("In simple words","The main point to remember",`<p class="cc-summary">${escapeHtml(report.plain_summary || "The report could not produce a short summary. Review Fix this first and the evidence below.")}</p>`)}
      ${card("Your video, part by part","Select a part to inspect its transcript and open the matching moment.",renderSegmentMap(report))}
      ${card("Does your title match your video?","What you promised, when the video delivers it, and how much time is marked for it.",renderTitleCheck(report))}
      ${card("Where people stopped watching","Use actual audience data as clues. A curve change is not proof of why someone left or stayed.",audienceChart(state.curve,report))}
      ${card("Keep doing this","These are the parts of your own voice worth protecting.",renderStrengths(report))}
      ${card("What we noticed","Filter the findings to focus on one kind of story choice.",renderFindings(report))}
      ${card("Try these next","No more than two extra ideas. Finish the first change before taking on more.",renderRecommendations(report))}
      ${card("Your numbers","Every number explains what it means. Missing values stay missing.",renderNumbers(report))}
      <section class="cc-card"><div class="cc-card-head"><div><h2>Before you film your next video</h2><p class="cc-card-sub">Tick each item as you prepare. Your checks save with this report.</p></div><span class="cc-chip">${Object.values(checked).filter(Boolean).length} of ${checklist.length} done</span></div>
        <div class="cc-checklist" id="cc-report-checklist">${checklist.map((item,index) => `<label class="cc-check-item ${checked[index]?"checked":""}"><input type="checkbox" data-check-index="${index}" ${checked[index]?"checked":""}><span>${escapeHtml(item)}</span></label>`).join("")}</div>
        <div class="cc-check-actions">${button("Copy checklist","copy-checklist","secondary")}${button("Print this report","print-report","ghost")}</div>
      </section>
      <section class="cc-card"><div class="cc-card-head"><div><h2>Questions only you can answer</h2><p class="cc-card-sub">Your answers can help the coach understand details the transcript cannot show.</p></div></div>
        ${list(report.questions_for_creator).length ? list(report.questions_for_creator).map((question,index) => `<div class="cc-question"><label for="cc-answer-${index}">${escapeHtml(question)}</label><textarea id="cc-answer-${index}" data-answer-index="${index}" data-answer-question="${escapeHtml(question)}" placeholder="Write your answer in your own words…">${escapeHtml(answers[question] || "")}</textarea></div>`).join("") : '<p class="cc-small">There are no extra questions for this video. You can still note anything you want the next report to know.</p><textarea id="cc-answer-general" placeholder="Notes for your next report…">${escapeHtml(answers["Notes for my next report"] || "")}</textarea>'}
        <div class="cc-form-actions"><span id="cc-answer-status" class="cc-small">Answers save automatically.</span>${button("Save answers now","save-answers","secondary small")}</div>
      </section>
      <details class="cc-details" style="margin-top:16px"><summary>What this report cannot tell you</summary><div class="cc-details-body"><ul>${list(report.cannot_tell_you || report.not_judgeable_from_transcript).map(item=>`<li>${escapeHtml(item)}</li>`).join("")}</ul><p>A transcript cannot show camera framing, B-roll, music, colour, the thumbnail itself or the rhythm of your edits. If something depends on those, the report should ask you instead of guessing.</p></div></details>
      <div class="cc-page-actions" style="margin-top:18px">${button("Back to my videos","show-videos","secondary")}${button("Check another video","add-video","primary")}</div>`;
    bindReportInputs();
  }
  function bindReportInputs() {
    $$("[data-check-index]").forEach(input => input.addEventListener("change", () => {
      input.closest(".cc-check-item")?.classList.toggle("checked", input.checked);
      const checkedItems = {};
      $$("[data-check-index]").forEach(box => checkedItems[box.dataset.checkIndex] = box.checked);
      const count = Object.values(checkedItems).filter(Boolean).length;
      const chip = input.closest(".cc-card")?.querySelector(".cc-chip");
      if (chip) chip.textContent = count + " of " + Object.keys(checkedItems).length + " done";
      saveChecklist(checkedItems).catch(error => notify(friendlyError(error)));
    }));
    $$("[data-answer-index],#cc-answer-general").forEach(textarea => textarea.addEventListener("input", () => {
      $("#cc-answer-status").textContent = "Saving your answer…";
      clearTimeout(state.answerTimer);
      state.answerTimer = setTimeout(() => saveAnswersFromUI().catch(error => {
        $("#cc-answer-status").textContent = "Could not save. Try again.";
        notify(friendlyError(error));
      }),650);
    }));
    $("#cc-remind-next")?.addEventListener("change", () => saveFixFirst({status:state.report.fixFirstStatus || "NOT_YET",remindBeforeNextVideo:$("#cc-remind-next").checked}).catch(error => notify(friendlyError(error))));
  }
  async function saveChecklist(checkedItems) {
    await put("/reports/" + encodeURIComponent(state.currentReportId) + "/checklist", {checkedItems});
    if (state.report) state.report.checklistState = checkedItems;
    notify("Checklist saved.");
  }
  async function saveAnswersFromUI() {
    const answers = {};
    $$("[data-answer-question]").forEach(textarea => answers[textarea.dataset.answerQuestion] = textarea.value);
    const general = $("#cc-answer-general");
    if (general) answers["Notes for my next report"] = general.value;
    await put("/reports/" + encodeURIComponent(state.currentReportId) + "/answers", {answers});
    if (state.report) state.report.creatorAnswers = answers;
    const status = $("#cc-answer-status");
    if (status) status.textContent = "Saved. These answers can inform your next report.";
  }
  async function saveFixFirst(data) {
    await put("/reports/" + encodeURIComponent(state.currentReportId) + "/fix-first", data);
    state.report = await get("/reports/" + encodeURIComponent(state.currentReportId));
    state.lastFocus = focusFromReport(state.report);
    notify(data.status === "DONE" ? "Focus marked as done." : "Focus saved.");
    renderReport();
  }
  async function downloadPdf() {
    const url = API_ORIGIN + API + "/reports/" + encodeURIComponent(state.currentReportId) + "/export?format=pdf";
    const response = await fetch(url,{credentials:"include",headers:{Accept:"application/pdf"}});
    if (!response.ok) {
      let message = "We could not download the PDF. Try again.";
      try { const body = await response.json(); message = body.message || message; } catch {}
      throw new Error(message);
    }
    const blob = await response.blob();
    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = objectUrl;
    link.download = "creator-story-report-" + state.currentReportId + ".pdf";
    document.body.appendChild(link); link.click(); link.remove();
    URL.revokeObjectURL(objectUrl);
    notify("Your report PDF is ready.");
  }
  async function copyChecklist() {
    const items = list(state.report?.next_video_checklist).slice(0,5);
    const checked = object(state.report?.checklistState);
    const text = "Before you film your next video\n" + items.map((item,index) => (checked[index] ? "[x] " : "[ ] ") + item).join("\n");
    try {
      await navigator.clipboard.writeText(text);
      notify("Checklist copied.");
    } catch {
      const textarea = document.createElement("textarea");
      textarea.value = text; document.body.appendChild(textarea); textarea.select();
      const ok = document.execCommand("copy"); textarea.remove();
      notify(ok ? "Checklist copied." : "Please select and copy your checklist manually.");
    }
  }
  function showDialog(title, html) {
    $("#cc-dialog-title").textContent = title;
    $("#cc-dialog-content").innerHTML = html;
    if (typeof dialog.showModal === "function") dialog.showModal(); else alert(title + "\n" + $("#cc-dialog-content").textContent);
  }
  function metricHelp(metric) {
    const m = object(state.report?.metrics);
    const map = {
      views:["Views","Views counted in the selected reporting window.",m.views],
      avg_watch_seconds:["How long people watch on average","The average seconds watched per view. It is an average, not a point where viewers stopped.",m.avg_watch_seconds],
      average_watch_percent:["How much of your video people watch","The average share of your full video watched per view.",m.average_watch_percent],
      impressions:["Times your thumbnail was shown","The number of times YouTube counted the thumbnail as shown.",m.impressions],
      thumbnail_click_percent:["Thumbnail click percentage","Out of people whose impressions were counted, how many clicked to watch.",m.thumbnail_click_percent],
      likes:["Likes","The likes number returned for this video and time window.",m.likes],
      comments:["Comments","The comments number returned for this video and time window.",m.comments],
      shares:["Shares","How often YouTube says the video was shared.",m.shares],
      new_subscribers:["New subscribers","People who subscribed after watching this video in the selected time window.",m.new_subscribers],
      lost_subscribers:["Subscribers lost","Unsubscribes associated with the video in the selected window.",m.lost_subscribers],
      stayed_past_30s_percent:["People who stayed past 30 seconds","An intro value from Studio, when supplied or available.",m.stayed_past_30s_percent],
      intro_watched_percent:["People who watched the intro","The intro value you supplied from Studio.",m.intro_watched_percent]
    };
    const entry = map[metric] || ["About this number","This value comes from available video data.",null];
    showDialog(entry[0], `<p>${escapeHtml(entry[1])}</p><p><strong>For this report:</strong> ${escapeHtml(metric === "avg_watch_seconds" ? fmtDuration(entry[2]) : metric.includes("percent") ? fmtPercent(entry[2]) : fmtNumber(entry[2]))}</p>`);
  }
  function inspectMoment(id) {
    const moment = list(state.curve?.moments).find(item => item.id === id);
    if (!moment) return;
    const related = list(state.report?.audience_explanations).find(item => item.moment_id === id);
    const seconds = number(moment.at_sec);
    showDialog("Inspect this moment", `<p><strong>${escapeHtml(stamp(seconds))}</strong></p><p>${escapeHtml(related?.what_was_happening || excerptFor(seconds, seconds+12, 220))}</p><p><strong>Our best guess:</strong> ${escapeHtml(related?.best_guess || "The graph marks a change here, but cannot tell us why people watched more or less.")}</p><p><a class="cc-link" href="${escapeHtml(reportVideoLink(seconds))}" target="_blank" rel="noreferrer">Open the video at this moment ↗</a></p>`);
  }
  function progressLineChart(items) {
    const data = list(items).map(item => ({
      label: String(item.title || fmtDate(item.publish_date)),
      date: fmtDate(item.publish_date),
      value: number(item.average_watch_percent)
    })).filter(item => item.value !== null);
    if (data.length < 2) return '<div class="cc-warning-banner"><span class="cc-banner-icon">◌</span><div><strong>Not enough watch data to draw a trend line yet.</strong>The reports are saved, but YouTube did not return average-watch values for enough videos.</div></div>';
    const width = 820, height = 250, left = 48, right = 25, top = 22, bottom = 42;
    const maxValue = Math.max(1, ...data.map(item => item.value));
    const x = index => left + (width - left - right) * index / (data.length - 1);
    const y = value => top + (height - top - bottom) * (1 - value / maxValue);
    const points = data.map((item,index) => x(index).toFixed(1) + "," + y(item.value).toFixed(1)).join(" ");
    const grid = [0,.5,1].map(fraction => {
      const yy = top + (height-top-bottom)*fraction;
      const labelValue = maxValue * (1-fraction);
      return `<line x1="${left}" y1="${yy}" x2="${width-right}" y2="${yy}" stroke="var(--line)" stroke-dasharray="4 5"/><text x="${left-9}" y="${yy+4}" text-anchor="end" fill="var(--sub)" font-size="11">${fmtPercent(labelValue,0)}</text>`;
    }).join("");
    const circles = data.map((item,index) => `<circle cx="${x(index)}" cy="${y(item.value)}" r="4.5" fill="var(--accent)" stroke="var(--surface)" stroke-width="2"><title>${escapeHtml(item.label)}: ${fmtPercent(item.value,1)} of the video watched on average</title></circle>`).join("");
    const labelIndexes = [...new Set([0, Math.floor((data.length-1)/2), data.length-1])];
    const labels = labelIndexes.map(index => {
      const anchor = index===0 ? "start" : index===data.length-1 ? "end" : "middle";
      return `<text x="${x(index)}" y="${height-12}" text-anchor="${anchor}" fill="var(--sub)" font-size="11">${escapeHtml(data[index].date)}</text>`;
    }).join("");
    return `<svg class="cc-chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="Line chart showing how much of each analysed video people watched on average. Each point has a video title and value in its tooltip.">${grid}<polyline points="${points}" fill="none" stroke="var(--accent)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>${circles}${labels}</svg><p class="cc-meaning">Each point is the average share watched for one of your videos. The line helps show a pattern; different audiences, topics and exposure can change the result.</p>`;
  }

  async function renderProgress() {
    const progress = await fetchProgress(true);
    const trend = list(progress.trend);
    const count = new Set(trend.map(item => item.videoId).filter(Boolean)).size;
    if (count < 3) {
      app.innerHTML = `${pageHead("My progress","Your progress page will grow as you analyse more of your own videos.")}<div class="cc-card cc-progress-locked"><div class="cc-progress-locked"><div class="cc-count">${count} / 3</div><h2>Add 3 videos to see your progress</h2><p class="cc-muted">For now, work on one Fix this first action per upload. Once three of your videos have reports, we can start comparing your own patterns. These comparisons are still hints, not proof.</p>${button("Check a video","add-video","primary")}</div></div>`;
      return;
    }
    const averagePercent = trend.filter(item=>number(item.average_watch_percent)!==null);
    const chart = progressLineChart(averagePercent);
    const history = await Promise.all(trend.map(async item => {
      try { return await get("/reports/" + encodeURIComponent(item.reportId)); } catch { return null; }
    }));
    state.reportHistory = history.filter(Boolean);
    const habitKeys = [
      ["opened_with_clear_question","Opened with a clear question?"],
      ["answered_title_promise_early","Delivered the title promise early?"],
      ["ended_with_clear_answer","Ended with a clear answer?"],
      ["asked_to_comment_or_subscribe","Asked viewers to comment or subscribe?"]
    ];
    const matrix = `<div class="cc-habit-scroll"><table class="cc-habit-table"><thead><tr><th>Habit to practise</th>${trend.map(item=>`<th title="${escapeHtml(item.title||"")}">${escapeHtml((item.title||fmtDate(item.publish_date)).slice(0,18))}</th>`).join("")}</tr></thead><tbody>${habitKeys.map(([key,label])=>`<tr><td>${escapeHtml(label)}</td>${trend.map(item=>`<td>${object(item.habits)[key]===true?'<span class="cc-habit-yes" aria-label="Yes">✓</span>':object(item.habits)[key]===false?'<span class="cc-habit-no" aria-label="Not in transcript">—</span>':'<span class="cc-habit-no" aria-label="Not available">?</span>'}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`;
    const pattern = [...state.reportHistory].reverse().find(report => report.previous_videos_text)?.previous_videos_text;
    const focusList = state.reportHistory.slice().reverse().map((report,index,arr) => {
      const focus = object(report.fix_first);
      const comparison = list(progress.afterFixComparisons).find(item=>item.beforeReportId===report.id);
      let outcome = "";
      if (comparison) {
        const before = number(comparison.beforeAverageWatchPercent), after = number(comparison.afterAverageWatchPercent);
        outcome = before === null || after === null ? "The next video's average watch number was not available, so there is no comparison yet."
          : "After the next upload, the average share watched changed by " + fmtPercent(Math.abs(after-before),1) + " percentage points (" + (after>before?"more":after<before?"less":"no change") + "). This is only a hint, not proof the focus item caused the change.";
      }
      const done = report.fixFirstStatus === "DONE";
      return `<div class="cc-history-item"><div><strong>${escapeHtml(focus.action || "Focus item not saved")}</strong><p>${escapeHtml(report.title || "")} · ${escapeHtml(fmtDate(report.publish_date))}</p>${outcome ? `<p>${escapeHtml(outcome)}</p>` : '<p class="cc-small">No later comparison is available yet.</p>'}</div><span class="cc-chip ${done?"ready":"neutral"}">${done?"Done":report.fixFirstStatus==="SKIPPED"?"Skipped":"Not yet"}</span></div>`;
    }).join("");
    app.innerHTML = `${pageHead("My progress","Look for patterns in your own videos. Different audiences and exposure can change the results, so treat these comparisons as clues.")}${card("How much of your video people watch","The values returned for each analysed upload.",chart)}${card("Habits across your videos","A tick means the transcript supports it. A dash is not a judgement of the finished visuals.",matrix)}${card("The pattern we noticed","Only shown after enough eligible previous videos exist.",pattern ? `<p class="cc-summary">${escapeHtml(pattern)}</p>` : '<p class="cc-small">A shared pattern has not been returned yet. Keep adding reports for similar videos.</p>')}${card("Focus history","Each next-upload comparison is only a hint.",focusList || '<p class="cc-small">Your Fix this first history will appear here.</p>')}`;
  }
  async function loadPlans(force = false) {
    if (!force && state.plans.length) return state.plans;
    const result = await get("/plans");
    state.plans = list(result.plans);
    return state.plans;
  }
  async function createPlanIfNeeded() {
    if (state.currentPlanId) return state.currentPlanId;
    const draft = state.planDraft || freshPlan();
    const saved = await post("/plans", draft);
    state.currentPlanId = saved.id;
    state.planDraft = saved;
    state.plans.unshift(saved);
    return saved.id;
  }
  function freshPlan() {
    return {title:"", output_language:state.profile?.output_language || "hinglish", outline:{opening:"",personal_reason:"",journey_discoveries:"",reflection:"",ending:""}, first_30_seconds:""};
  }
  function planFields(plan) {
    const outline = object(plan?.outline);
    return [
      {key:"opening",label:"The question or promise",help:"What makes the viewer curious in the first 30 seconds? Keep it in your own voice."},
      {key:"personal_reason",label:"Why I went",help:"Give the personal reason for this drive or trip."},
      {key:"journey_discoveries",label:"3 discoveries",help:"Three things you actually discover, experience or learn along the way."},
      {key:"reflection",label:"One emotional contrast",help:"For example: the contrast between stone and water, noise and sukoon, or expectation and reality."},
      {key:"ending",label:"My final recommendation",help:"Answer the opening promise and share what you genuinely recommend."}
    ].map(field=>({...field,value:String(outline[field.key]||"")}));
  }
  async function renderPlanner(newBlank = false) {
    await fetchProfile();
    const [plans] = await Promise.all([loadPlans(true)]);
    const last = await loadLatestFocus();
    let plan = null;
    if (!newBlank && state.currentPlanId) {
      try { plan = await get("/plans/" + encodeURIComponent(state.currentPlanId)); } catch {}
    }
    if (!newBlank && !plan) plan = plans[0] || null;
    if (!plan) plan = freshPlan();
    state.currentPlanId = plan.id || "";
    state.planDraft = JSON.parse(JSON.stringify(plan));
    const fields = planFields(state.planDraft);
    app.innerHTML = `${pageHead("Next video planner","Build your next travel story before the shoot. Keep the cinematic storytelling and your natural on-camera moments working together.",button("＋ New outline","new-plan","primary"))}
      ${last ? `<section class="cc-card cc-focus-card" style="margin-bottom:16px"><div class="cc-focus-label">A reminder from your last video</div><h2>${escapeHtml(last.action)}</h2><p>${escapeHtml(last.title)}</p><div class="cc-small" style="margin-top:7px">Try this once in the next upload. Keep the rest of the style that already feels like you.</div></section>` : ""}
      <section class="cc-card"><div class="cc-card-head"><div><h2>Your five-part story outline</h2><p class="cc-card-sub">Your words, your journey. Drafts save automatically.</p></div><span id="cc-plan-save-status" class="cc-chip">${plan.id?"Saved draft":"New draft"}</span></div>
        <div class="cc-field"><label for="cc-plan-title">Working title</label><input id="cc-plan-title" placeholder="Give this story a working title" value="${escapeHtml(plan.title||"")}"></div>
        <div class="cc-divider"></div><div class="cc-stack">${fields.map((field,index)=>`<div class="cc-field"><label for="cc-outline-${field.key}">${index+1}. ${escapeHtml(field.label)}</label><textarea id="cc-outline-${field.key}" data-outline-key="${field.key}" rows="${field.key==="journey_discoveries"?5:3}" placeholder="${escapeHtml(field.help)}">${escapeHtml(field.value)}</textarea><small>${escapeHtml(field.help)}</small></div>`).join("")}</div>
        <div class="cc-field" style="margin-top:16px"><label for="cc-first-30">What will the first 30 seconds show or say?</label><textarea id="cc-first-30" rows="3" placeholder="Describe the opening visuals and your first on-camera or voice-over line.">${escapeHtml(plan.first_30_seconds||"")}</textarea><small>Visual details come from you, not the transcript. Be as specific as you can.</small></div>
        <div class="cc-divider"></div><h2 style="margin-bottom:12px">Does the title match your opening?</h2><p class="cc-card-sub" style="margin-bottom:13px">This quick word check is a prompt for review, not a prediction of clicks.</p><div id="cc-title-check-result"></div>
        <div class="cc-form-actions"><span id="cc-plan-save-status-bottom" class="cc-small">Auto-save is on.</span><div class="cc-page-actions">${button("Check title promise","title-check","secondary")}${button("Duplicate","duplicate-plan","secondary")}${button("Export as text","export-plan","secondary")}${button("Save outline","save-plan","primary")}</div></div>
      </section>
      ${plans.length>1 ? card("Your saved outlines","Open an earlier plan.",`<div class="cc-stack">${plans.map(p=>`<button type="button" class="cc-history-item" style="width:100%;text-align:left;background:transparent;border:0" data-action="open-plan" data-plan-id="${escapeHtml(p.id)}"><span><strong>${escapeHtml(p.title||"Untitled outline")}</strong><p>Last edited ${escapeHtml(fmtDate(p.updatedAt||p._updatedAt))}</p></span><span class="cc-link">Open →</span></button>`).join("")}</div>`) : ""}`;
    bindPlannerInputs();
  }
  function bindPlannerInputs() {
    const update = () => {
      const outline = {};
      $$("[data-outline-key]").forEach(field => outline[field.dataset.outlineKey] = field.value);
      state.planDraft = {...object(state.planDraft), title:$("#cc-plan-title")?.value || "", outline, first_30_seconds:$("#cc-first-30")?.value || ""};
      const status = $("#cc-plan-save-status");
      if (status) status.textContent = "Draft changes saved locally…";
      clearTimeout(state.plannerTimer);
      state.plannerTimer = setTimeout(() => autoSavePlan().catch(error => {
        $("#cc-plan-save-status") && ($("#cc-plan-save-status").textContent = "Could not save to your account.");
        notify(friendlyError(error));
      }),700);
    };
    $("#cc-plan-title")?.addEventListener("input",update);
    $$("[data-outline-key],#cc-first-30").forEach(field => field.addEventListener("input",update));
  }
  async function autoSavePlan() {
    if (!state.planDraft) return;
    const saved = await (state.currentPlanId
      ? put("/plans/" + encodeURIComponent(state.currentPlanId), state.planDraft)
      : post("/plans", state.planDraft));
    state.currentPlanId = saved.id;
    state.planDraft = saved;
    const status = $("#cc-plan-save-status");
    if (status) status.textContent = "Saved to your account";
    const bottom = $("#cc-plan-save-status-bottom");
    if (bottom) bottom.textContent = "Last saved " + new Date().toLocaleTimeString([], {hour:"2-digit",minute:"2-digit"});
    state.plans = [saved,...state.plans.filter(p=>p.id!==saved.id)];
  }
  async function flushPlan() {
    clearTimeout(state.plannerTimer);
    const titleCheckResult = $("#cc-title-check-result");
    if (state.planDraft) {
      state.planDraft.title = $("#cc-plan-title")?.value ?? state.planDraft.title ?? "";
      $$("[data-outline-key]").forEach(field => state.planDraft.outline[field.dataset.outlineKey] = field.value);
      state.planDraft.first_30_seconds = $("#cc-first-30")?.value || "";
    }
    await autoSavePlan();
    if (titleCheckResult && !state.currentPlanId) titleCheckResult.textContent = "";
  }
  async function titleCheck() {
    await flushPlan();
    const result = await post("/plans/" + encodeURIComponent(state.currentPlanId) + "/title-check", {
      title:$("#cc-plan-title").value,
      first_30_seconds:$("#cc-first-30").value
    });
    $("#cc-title-check-result").innerHTML = `<div class="cc-warning-banner ${list(result.unsupportedTitleWords).length?"neutral":""}"><span class="cc-banner-icon">✦</span><div><strong>${escapeHtml(result.warning)}</strong>${list(result.unsupportedTitleWords).length ? `<p class="cc-small" style="margin:7px 0 0">Words to review in the opening: ${escapeHtml(result.unsupportedTitleWords.join(", "))}</p>` : ""}<p class="cc-small" style="margin:7px 0 0">This is a simple word check. It cannot promise more clicks.</p></div></div>`;
  }
  async function duplicatePlan() {
    await flushPlan();
    const copy = await post("/plans/" + encodeURIComponent(state.currentPlanId) + "/duplicate", {});
    state.currentPlanId = copy.id;
    state.planDraft = copy;
    notify("Outline duplicated.");
    await renderPlanner();
  }
  async function exportPlan() {
    await flushPlan();
    const url = API_ORIGIN + API + "/plans/" + encodeURIComponent(state.currentPlanId) + "/export?format=text";
    const response = await fetch(url,{credentials:"include"});
    if (!response.ok) throw new Error("We could not export this outline. Try again.");
    const blob = await response.blob(); const href = URL.createObjectURL(blob);
    const anchor = document.createElement("a"); anchor.href=href; anchor.download="video-plan-" + state.currentPlanId + ".txt";
    document.body.appendChild(anchor); anchor.click(); anchor.remove(); URL.revokeObjectURL(href);
    notify("Your outline was exported.");
  }
  async function renderStyle() {
    const profile = await fetchProfile(true);
    const languages = list(profile.languages).length ? list(profile.languages) : [String(profile.languages||"")];
    const mustKeep = list(profile.must_keep);
    app.innerHTML = `${pageHead("My style","Your creator identity comes first. The coach should help your storytelling + on-camera format grow without replacing your voice.")}
      <section class="cc-card"><div class="cc-card-head"><div><h2>Your creator profile</h2><p class="cc-card-sub">These preferences guide every future report.</p></div></div>
        <div class="cc-grid two"><div class="cc-field"><label for="cc-profile-stage">Where you are in your creator journey</label><input id="cc-profile-stage" value="${escapeHtml(profile.channel_stage||"")}" placeholder="New creator / under 500 subscribers"></div>
        <div class="cc-field"><label for="cc-profile-language">Language of the report</label><select id="cc-profile-language"><option value="en" ${profile.output_language==="en"?"selected":""}>English</option><option value="hi" ${profile.output_language==="hi"?"selected":""}>Hindi</option><option value="hinglish" ${profile.output_language==="hinglish"||!profile.output_language?"selected":""}>Hinglish</option></select></div>
        <div class="cc-field"><label for="cc-profile-style">My content style</label><textarea id="cc-profile-style" rows="4">${escapeHtml(profile.style||"")}</textarea></div>
        <div class="cc-field"><label for="cc-profile-goals">What I want to build</label><textarea id="cc-profile-goals" rows="4">${escapeHtml(profile.goals||"")}</textarea></div>
        <div class="cc-field" style="grid-column:1/-1"><label for="cc-profile-languages">Languages I use</label><textarea id="cc-profile-languages" rows="2">${escapeHtml(languages.join(", "))}</textarea><small>Separate languages or narration styles with commas.</small></div></div>
        <div class="cc-divider"></div><div class="cc-card-head"><div><h2>Things I never want to change</h2><p class="cc-card-sub">We will never suggest changes that remove these.</p></div>${button("＋ Add element","add-must-keep","secondary small")}</div>
        <div id="cc-must-keep" class="cc-stack">${mustKeep.map((item,index)=>`<div class="cc-mustkeep-row"><input class="cc-input" data-must-keep value="${escapeHtml(item)}" aria-label="Protected style element ${index+1}"><button type="button" class="cc-btn ghost small" data-action="remove-must-keep" data-index="${index}" aria-label="Remove style element ${index+1}">Remove</button></div>`).join("")}</div>
        <div class="cc-theme-note" style="margin-top:16px">Your core pattern is storytelling + on-camera travel vlogging: reflective, cinematic narration paired with natural lived moments. Recommendations should add clarity, not turn your video into a generic explainer or copy another creator.</div>
        <div class="cc-form-actions"><span id="cc-profile-save-status" class="cc-small">Saved preferences guide your next report.</span>${button("Save my style","save-profile","primary")}</div>
      </section>`;
  }
  function readProfileForm() {
    return {
      channel_stage:$("#cc-profile-stage").value.trim(),
      style:$("#cc-profile-style").value.trim(),
      goals:$("#cc-profile-goals").value.trim(),
      output_language:$("#cc-profile-language").value,
      languages:$("#cc-profile-languages").value.split(",").map(s=>s.trim()).filter(Boolean),
      must_keep:$$("[data-must-keep]").map(input=>input.value.trim()).filter(Boolean)
    };
  }
  async function saveProfile() {
    const profile = readProfileForm();
    state.profile = await put("/profile",profile);
    $("#cc-profile-save-status").textContent = "Saved to your account.";
    notify("Your style preferences are saved.");
  }
  async function renderHelp() {
    if (!state.glossary.length) {
      if (state.auth?.authenticated && state.auth?.connected) {
        const result = await get("/glossary");
        state.glossary = list(result.entries);
      } else {
        state.glossary = [
          {term:"Average watch time",meaning:"The average number of seconds watched per view. It does not tell us the exact second people left.",example_note:"Connect your YouTube account to see examples from your own reports."},
          {term:"Thumbnail click percentage",meaning:"Out of people who saw your thumbnail, how many clicked. This helps you understand the title and thumbnail together.",example_note:"Connect your YouTube account to see your own available numbers."},
          {term:"Audience curve",meaning:"Where people stopped watching. A line showing how viewing changes through the video. A change gives you a moment to inspect, not the reason it happened.",example_note:"Connect your YouTube account to see graph examples when available."},
          {term:"Small sample",meaning:"Not enough views yet to be sure. When a video has a small number of views, numbers can change quickly. Treat them as early hints.",example_note:"This reminder applies to videos with limited data."},
          {term:"Title promise",meaning:"What someone reasonably expects to see or experience after reading your title and thumbnail.",example_note:"You can use this when planning the opening of your next travel story."},
          {term:"Story reward",meaning:"A discovery, answer, feeling or useful detail that makes the journey worth following.",example_note:"Your storytelling + on-camera style should make room for the journey and a satisfying answer."}
        ];
      }
    }
    app.innerHTML = `${pageHead("Help & word meanings","Every term should be understandable without a tutor. Search for a word and read the plain meaning.")}
      <section class="cc-card"><div class="cc-field"><label for="cc-glossary-search">What does this word mean?</label><input id="cc-glossary-search" type="search" placeholder="Search for average watch time, title promise, or small sample…"></div>
        <div class="cc-glossary-list" id="cc-glossary-results" style="margin-top:17px">${renderGlossary(state.glossary)}</div>
      </section>
      ${card("A few rules the coach follows","Your video is a creative work, not a spreadsheet.",'<ul><li>We show one main change at the top.</li><li>We call explanations “Our best guess”, not facts.</li><li>When the numbers are small or missing, we say so plainly.</li><li>We protect the storytelling + on-camera style you choose.</li><li>We do not copy competitor scripts or phrases.</li></ul>')}`;
    $("#cc-glossary-search")?.addEventListener("input",event=>{
      const needle = event.currentTarget.value.trim().toLowerCase();
      $("#cc-glossary-results").innerHTML = renderGlossary(state.glossary.filter(item =>
        [item.term, item.meaning, item.example_from_your_video, glossaryPlainLabel(item.term)]
          .some(value => String(value||"").toLowerCase().includes(needle))));
    });
  }
  function glossaryPlainLabel(term) {
    return ({
      "Average watch time":"How long people watch on average",
      "Thumbnail click percentage":"Out of people who saw your thumbnail, how many clicked",
      "Audience curve":"Where people stopped watching",
      "Small sample":"Not enough views yet to be sure",
      "Title promise":"What your title promises",
      "Story reward":"The reward at the end"
    })[term] || term;
  }
  function renderGlossary(entries) {
    if (!entries.length) return '<div class="cc-empty"><h2>No matching words</h2><p>Try a simpler word or search for “watch”, “title” or “views”.</p></div>';
    const plain = {
      "Average watch time":["How long people watch on average","The average number of seconds watched per view. It does not tell us the exact second people left."],
      "Thumbnail click percentage":["Out of people who saw your thumbnail, how many clicked","This helps you understand how the title and thumbnail work together."],
      "Audience curve":["Where people stopped watching","A line showing how viewing changes through the video. A change gives you a moment to inspect, not the reason it happened."],
      "Small sample":["Not enough views yet to be sure","When a video has only a small number of views, numbers can change quickly. Treat them as early hints."],
      "Title promise":["What your title promises","What someone reasonably expects to see or experience after reading the title and thumbnail."],
      "Story reward":["The reward at the end","A discovery, answer, feeling or useful detail that makes the journey worth following."]
    };
    return entries.map(item => {
      const chosen = plain[item.term];
      const term = chosen ? chosen[0] : item.term;
      const meaning = chosen ? chosen[1] : item.meaning;
      return `<article class="cc-glossary-entry"><h3>${escapeHtml(term)}</h3><p>${escapeHtml(meaning)}</p>${item.example_from_your_video ? `<div class="cc-example"><strong>Example from your videos:</strong> ${escapeHtml(item.example_from_your_video)}</div>` : `<div class="cc-example">${escapeHtml(item.example_note||"No example from your own report is available yet.")}</div>`}</article>`;
    }).join("");
  }
  async function handleAction(action, target) {
    const videoId = target?.dataset.videoId;
    const reportId = target?.dataset.reportId;
    switch (action) {
      case "add-video": return startAdd();
      case "show-videos": state.videoSearch=""; state.videos=[]; return navTo("videos");
      case "select-video": return openVideoFromList(videoId);
      case "open-report":
        state.currentReportId = reportId || "";
        state.currentVideoId = videoId || state.currentVideoId;
        state.report = null; state.curve = null; state.activeSegment = 0; state.findingsFilter = "all";
        return navTo("report");
      case "pick-video":
        await loadSelectedInputs(videoId);
        $$("#cc-add-video-list .cc-video-row").forEach(row => row.style.borderColor = row.dataset.videoId === videoId ? "var(--accent)" : "var(--line)");
        notify("Video selected. Continue to the optional numbers.");
        return;
      case "add-next-step": {
        const urlValue = $("#cc-video-url")?.value || "";
        const id = videoIdFromInput(urlValue) || state.currentVideoId;
        if (!id) return notify("Choose a video or paste its YouTube link first.");
        await loadSelectedInputs(id);
        state.addStep = 2;
        return renderAdd();
      }
      case "add-back-step": state.addStep=Math.max(1,state.addStep-1); return renderAdd();
      case "save-transcript":
        if ($("#cc-transcript")) state.transcriptDraft = $("#cc-transcript").value;
        await saveTranscriptDraft(); notify("Transcript saved."); return renderAdd();
      case "save-inputs-next":
        state.transcriptDraft = $("#cc-transcript")?.value ?? state.transcriptDraft;
        state.creatorNotes = $("#cc-creator-notes")?.value ?? state.creatorNotes;
        $$("[data-manual-key]").forEach(input=>state.manualDraft[input.dataset.manualKey]=input.value);
        await saveManualDraft();
        await saveTranscriptDraft();
        const inputsVideo = normalizeInputVideo(state.inputs);
        if (inputsVideo.status === "TOO_NEW" || (inputsVideo.age !== null && inputsVideo.age < 3)) {
          notify("Too early to judge. Please wait until the video is at least three days old.");
          state.addStep = 3; return renderAdd();
        }
        state.addStep = 3; return renderAdd();
      case "run-analysis": return beginAnalysis();
      case "back-to-inputs": state.addStep=2; return navTo("add");
      case "download-pdf": return downloadPdf();
      case "print-report": window.print(); return;
      case "copy-checklist": return copyChecklist();
      case "fix-done": return saveFixFirst({status:"DONE",remindBeforeNextVideo:$("#cc-remind-next")?.checked || false});
      case "fix-reset": return saveFixFirst({status:"NOT_YET",remindBeforeNextVideo:$("#cc-remind-next")?.checked || false});
      case "filter-findings": state.findingsFilter=target.dataset.filter; return renderReport();
      case "select-segment": state.activeSegment=Number(target.dataset.segmentIndex || 0); return renderReport();
      case "jump-second": {
        const seconds = Number(target.dataset.jumpSeconds || 0);
        window.open(videoHref(state.currentVideoId,seconds),"_blank","noopener");
        return;
      }
      case "inspect-moment": return inspectMoment(target.dataset.momentId);
      case "metric-help": return metricHelp(target.dataset.metric);
      case "show-help": return showDialog("Where to find this",`<p>${escapeHtml(studioHelp(target.dataset.helpKey))}</p><p><a class="cc-link" href="https://studio.youtube.com/" target="_blank" rel="noreferrer">Open YouTube Studio ↗</a></p>`);
      case "focus-done":
        if (state.lastFocus?.reportId) {
          const reportId = state.lastFocus.reportId;
          const saved = await put("/reports/" + encodeURIComponent(reportId) + "/fix-first", {
            status:"DONE", remindBeforeNextVideo:Boolean(state.lastFocus.remind)
          });
          state.lastFocus = focusFromReport(saved);
          state.videos = [];
          state.progress = null;
          notify("Your current focus is marked as done.");
          return renderVideos();
        }
        return navTo("add");
      case "save-answers": return saveAnswersFromUI().then(()=>notify("Answers saved."));
      case "new-plan":
        state.currentPlanId=""; state.planDraft=freshPlan(); return renderPlanner(true);
      case "save-plan": await flushPlan(); notify("Outline saved."); return;
      case "title-check": return titleCheck();
      case "duplicate-plan": return duplicatePlan();
      case "export-plan": return exportPlan();
      case "open-plan":
        state.currentPlanId=target.dataset.planId; state.planDraft=null; return renderPlanner();
      case "save-profile": return saveProfile();
      case "add-must-keep": {
        const root=$("#cc-must-keep");
        const row=document.createElement("div"); row.className="cc-mustkeep-row";
        row.innerHTML='<input class="cc-input" data-must-keep placeholder="Something you want to protect" aria-label="Protected style element"><button type="button" class="cc-btn ghost small" data-action="remove-must-keep" aria-label="Remove style element">Remove</button>';
        root.appendChild(row); row.querySelector("input").focus(); return;
      }
      case "remove-must-keep": target.closest(".cc-mustkeep-row")?.remove(); return;
      case "retry-page": return navTo(target.dataset.retryPage || state.page);
      default: return;
    }
  }
  function applyTheme(theme) {
    const normalized = theme === "light" ? "light" : "dark";
    document.documentElement.dataset.theme = normalized;
    try { localStorage.setItem("creator-coach-theme",normalized); } catch {}
    const button = $("#cc-theme-toggle");
    if (button) {
      button.innerHTML = normalized === "dark" ? "☼ <span>Light mode</span>" : "☾ <span>Dark mode</span>";
      button.setAttribute("aria-label", normalized === "dark" ? "Switch to light theme" : "Switch to dark theme");
    }
    const themeColor = document.querySelector('meta[name="theme-color"]');
    if (themeColor) themeColor.content = normalized === "dark" ? "#171a22" : "#f5f5f8";
  }
  async function startup() {
    let theme = "dark";
    try { theme = localStorage.getItem("creator-coach-theme") || theme; } catch {}
    applyTheme(theme);
    $("#cc-theme-toggle").addEventListener("click",()=>applyTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark"));
    $("#cc-menu").addEventListener("click",()=>sidebar.classList.toggle("open"));
    const connected = await checkAuth();
    if (connected) await loadPage("videos");
  }
  document.addEventListener("keydown", event => {
    const marker = event.target.closest?.('circle[data-action="inspect-moment"]');
    if (marker && (event.key === "Enter" || event.key === " ")) {
      event.preventDefault();
      marker.dispatchEvent(new MouseEvent("click", {bubbles:true, cancelable:true, view:window}));
    }
  });
  document.addEventListener("click", event => {
    const navButton = event.target.closest("[data-page]");
    if (navButton) { event.preventDefault(); return navTo(navButton.dataset.page); }
    const actionButton = event.target.closest("[data-action]");
    if (actionButton) {
      event.preventDefault();
      handleAction(actionButton.dataset.action,actionButton).catch(error=>notify(friendlyError(error)));
    }
  });
  $("#cc-dialog").addEventListener("click",event=>{
    if (event.target === dialog) dialog.close();
  });
  startup().catch(error=>errorScreen(error,"videos"));
})();