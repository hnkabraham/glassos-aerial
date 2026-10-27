"use strict";
const $ = (s) => document.querySelector(s);
const token =
  location.hash.slice(1) || sessionStorage.getItem("glass-token") || "";
if (location.hash) {
  sessionStorage.setItem("glass-token", token);
  history.replaceState(null, "", location.pathname);
}
let state = null,
  page = "overview",
  filter = "all",
  selectedFile = null,
  selectedDevice = localStorage.getItem("glass-device") || "",
  sceneSignature = "",
  backupSignature = "",
  scheduleLoaded = false;
// A footer note set by this page (not by a job) stays until the jobs report something newer. A job keeps only its
// last 100 messages, so its latest message is part of the key too.
let footerNote = null;
const jobKey = (j) =>
  j ? `${j.id} ${j.status} ${j.messages.length} ${j.messages.at(-1) ?? ""}` : "";
function footer(text) {
  footerNote = { text, key: jobKey(state?.jobs?.at(-1)) };
  $("#job-message").textContent = text;
}
const posters = new Map();
const posterRequests = new Map();
const posterLanes = Array.from({ length: 4 }, () => Promise.resolve());
let nextPosterLane = 0;

function posterFor(id) {
  if (posters.has(id)) return Promise.resolve(posters.get(id));
  if (posterRequests.has(id)) return posterRequests.get(id);
  const lane = nextPosterLane++ % posterLanes.length;
  const task = posterLanes[lane].then(async () => {
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        const response = await fetch("/api/poster/" + id, {
          headers: { "X-GlassOS-Token": token },
        });
        if (!response.ok) throw Error("Preview unavailable");
        const url = URL.createObjectURL(await response.blob());
        posters.set(id, url);
        return url;
      } catch (error) {
        if (attempt === 2) throw error;
        await new Promise((resolve) =>
          setTimeout(resolve, 250 * (attempt + 1)),
        );
      }
    }
  });
  posterLanes[lane] = task.catch(() => {});
  const pending = task.finally(() => posterRequests.delete(id));
  posterRequests.set(id, pending);
  return pending;
}
const descriptions = {
  catalog: [
    "A WORLD OF QUIET MOMENTS",
    "Find a new horizon.",
    "Choose your next scene. We’ll take care of the format.",
  ],
  appearance: [
    "MAKE IT YOURS",
    "A home with character.",
    "Subtle palettes and familiar apps, dressed your way.",
  ],
  profiles: [
    "KNOW YOUR DEVICES",
    "Every TV, accounted for.",
    "Compare what changed and keep a baseline you trust.",
  ],
  overview: [
    "THE BIG PICTURE",
    "A little more calm.",
    "Your home screen, scenery and settings, together.",
  ],
  library: [
    "PLACES TO PAUSE",
    "Keep the view.",
    "A personal collection of quiet moments, ready for your TV.",
  ],
  schedule: [
    "FROM MORNING TO MIDNIGHT",
    "Follow the light.",
    "Let your scenery change with the day.",
  ],
  backups: [
    "FEEL AT HOME, ANYWHERE",
    "Take your setup along.",
    "Keep a copy, or bring your familiar layout to another TV.",
  ],
  tv: [
    "ON YOUR TV, FROM HERE",
    "Quiet controls.",
    "Look after your TV’s protected settings without reaching for the remote.",
  ],
};
function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
}
function notice(s) {
  $("#notice").textContent = s;
  $("#notice").hidden = !s;
}
async function api(path, data) {
  const r = await fetch(path, {
    method: data === undefined ? "GET" : "POST",
    headers: {
      "X-GlassOS-Token": token,
      ...(data === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: data === undefined ? undefined : JSON.stringify(data),
  });
  const out = await r.json();
  if (!r.ok) throw Error(out.error || "Request failed");
  return out;
}
const size = (n) =>
  n >= 1e9
    ? (n / 1e9).toFixed(2) + " GB"
    : n >= 1e6
      ? (n / 1e6).toFixed(1) + " MB"
      : (n / 1000).toFixed(1) + " KB";
function go(next) {
  page = next;
  document.querySelectorAll(".page").forEach((n) => (n.hidden = n.id !== next));
  document
    .querySelectorAll(".nav")
    .forEach((n) => n.classList.toggle("active", n.dataset.page === next));
  const d = descriptions[next];
  $("#eyebrow").textContent = d[0];
  $("#page-title").textContent = d[1];
  $("#page-description").textContent = d[2];
}
function options(select, all = true, current = "") {
  select.replaceChildren();
  for (const [id, label] of Object.entries(state.collections)) {
    if (!all && id === "all") continue;
    const o = el("option", "", label);
    o.value = id;
    o.selected = id === current;
    select.append(o);
  }
}
function row(parent, label, value, ok = true) {
  const n = el("div", "check-row");
  n.append(el("span", "", label), el("strong", ok ? "" : "warn", value));
  parent.append(n);
}
function render() {
  const devices = $("#device");
  const old = devices.value;
  if (
    JSON.stringify([...devices.options].map((o) => [o.value, o.text])) !==
    JSON.stringify(state.devices.map((d) => [d.id, d.name]))
  ) {
    devices.replaceChildren();
    if (!state.devices.length) {
      const o = el("option", "", "Add a device");
      o.value = "";
      devices.append(o);
    }
    state.devices.forEach((d) => {
      const o = el("option", "", d.name);
      o.value = d.id;
      devices.append(o);
    });
  }
  if (!state.devices.some((d) => d.id === selectedDevice))
    selectedDevice = state.devices[0]?.id || "";
  devices.value = selectedDevice;
  const d = state.devices.find((d) => d.id === selectedDevice),
    h = d?.health;
  $("#version").textContent = h?.version || "Not checked";
  $("#model").textContent = h
    ? `${h.model} · ${h.build}`
    : d
      ? "Run a device check"
      : "Choose a device to get started";
  $("#free").textContent = h ? size(h.free_bytes) : "—";
  $("#scene-count").textContent =
    state.scenes.length + (state.scenes.length === 1 ? " scene" : " scenes");
  $("#scene-duration").textContent = state.scenes.length
    ? `${Math.max(1, Math.round(state.scenes.reduce((a, s) => a + s.duration, 0) / 60))} minutes · ${size(state.scenes.reduce((a, s) => a + s.bytes, 0))} on this Mac`
    : "Add your saved collection or a video";
  $("#checked").textContent = h
    ? "Checked " + new Date(h.checked_at * 1000).toLocaleString()
    : "Check a device to see its status";
  if (h) {
    const p = $("#privacy");
    p.replaceChildren();
    row(
      p,
      "Home handler",
      h.home.includes("com.wolf.tn/")
        ? "Launcher Manager"
        : h.home.includes("glassos.aerial/")
          ? "GlassOS Aerial"
          : h.home.includes("tv.launcher")
            ? "Amazon Home"
            : h.home.includes("tvlauncher")
              ? "Android TV Home"
              : "Another home app",
    );
    const labels = [
      "Device usage data",
      "App usage collection",
      "Interest-based ads",
      "Marketing metrics",
      "Usage uploads",
    ];
    Object.entries(h.privacy).forEach(([k, v], i) =>
      row(
        p,
        labels[i],
        v === "0" ? "Off" : v === "1" ? "On" : "Unknown",
        v === "0",
      ),
    );
    const off = Object.values(h.disabled).filter(Boolean).length;
    row(p, "Recorded privacy packages", `${off} / 4 disabled`, off === 4);
    const b = $("#blocks");
    b.replaceChildren();
    Object.entries(h.instagram).forEach(([k, v], i) =>
      row(
        b,
        ["Instagram TV", "Instagram mobile", "Instagram Lite"][i],
        v,
        v === "Blocker verified",
      ),
    );
  } else {
    $("#privacy").textContent =
      "Run a device check to see the current settings.";
    $("#blocks").textContent =
      "The manager checks the TV, mobile and Lite packages.";
  }
  const selected = state.scenes
    .filter((s) => s.enabled)
    .reduce((a, s) => a + s.bytes, 0);
  $("#selected-size").textContent = size(selected) + " selected";
  if (document.activeElement !== $("#budget"))
    $("#budget").value = state.budget_mb;
  $("#budget-progress").value = Math.min(
    100,
    (selected / (state.budget_mb * 1e6)) * 100,
  );
  const signature = JSON.stringify(state.scenes) + filter;
  if (
    signature !== sceneSignature &&
    !$("#scenes").contains(document.activeElement)
  ) {
    sceneSignature = signature;
    renderScenes();
  }
  if (!scheduleLoaded) {
    renderSchedule(state.schedule);
    scheduleLoaded = true;
  }
  document.querySelectorAll("#periods .period").forEach(previewPeriod);
  renderSun(d);
  sunLookupDone();
  const bs = JSON.stringify(state.backups);
  if (bs !== backupSignature) {
    backupSignature = bs;
    renderBackups();
  }
  renderExtras(d);
  const job = state.jobs.at(-1),
    busy = state.jobs.some((j) => j.status === "running");
  document
    .querySelectorAll("[data-action]")
    .forEach(
      (b) =>
        (b.disabled =
          busy ||
          (!selectedDevice &&
            !["bundle", "catalog", "catalog-import", "sun-lookup"].includes(
              b.dataset.action,
            )) ||
          (b.dataset.action === "bundle" && !state.bundle_available)),
    );
  $('#import-form button[type="submit"]')?.toggleAttribute("disabled", busy);
  if (job) {
    $("#job-title").textContent =
      job.title +
      (job.status === "running"
        ? " · Working…"
        : job.status === "done"
          ? " · Complete"
          : " · Needs attention");
    if (footerNote && footerNote.key !== jobKey(job)) footerNote = null;
    $("#job-message").textContent = footerNote
      ? footerNote.text
      : job.error || job.messages.at(-1) || "Starting…";
    $("#job-log").textContent = state.jobs
      .map(
        (j) =>
          `${j.title} — ${j.status}\n${j.messages.join("\n")}${j.error ? "\n" + j.error : ""}`,
      )
      .join("\n\n");
  }
}
function renderScenes() {
  const filters = $("#filters");
  filters.replaceChildren();
  for (const [id, label] of Object.entries(state.collections)) {
    if (id !== "all" && !state.scenes.some((s) => s.collection === id))
      continue;
    const b = el("button", "filter" + (filter === id ? " active" : ""), label);
    b.onclick = () => {
      filter = id;
      sceneSignature = "";
      render();
    };
    filters.append(b);
  }
  const grid = $("#scenes");
  grid.replaceChildren();
  const list = state.scenes.filter(
    (s) => filter === "all" || s.collection === filter,
  );
  if (!list.length) {
    grid.append(
      el(
        "div",
        "empty",
        "Your next view is waiting. Add the saved collection or import a video.",
      ),
    );
    return;
  }
  list.forEach((s) => {
    const card = el("article", "scene-card"),
      poster = el("div", "poster"),
      img = el("img");
    img.alt = s.title;
    poster.append(img);
    const unavailable = () => {
      posters.delete(s.id);
      img.remove();
      poster.append(el("span", "", "Preview unavailable · Refresh to retry"));
    };
    img.onerror = unavailable;
    posterFor(s.id)
      .then((url) => {
        img.src = url;
      })
      .catch(unavailable);
    const label = el("label"),
      check = el("input");
    check.type = "checkbox";
    check.checked = s.enabled;
    check.setAttribute("aria-label", "Sync " + s.title);
    label.append(check, document.createTextNode("Sync"));
    poster.append(label);
    check.onchange = () => editScene(s, { enabled: check.checked });
    const info = el("div", "scene-info"),
      title = el("input");
    title.value = s.title;
    title.maxLength = 120;
    title.setAttribute("aria-label", "Scene title");
    title.onchange = () => editScene(s, { title: title.value });
    info.append(title);
    const meta = el("div", "meta"),
      category = el("select");
    category.setAttribute("aria-label", "Collection for " + s.title);
    options(category, false, s.collection);
    category.onchange = () => editScene(s, { collection: category.value });
    meta.append(
      category,
      el("span", "", Math.round(s.duration) + "s · " + size(s.bytes)),
    );
    info.append(meta);
    card.append(poster, info);
    grid.append(card);
  });
}
async function editScene(scene, change) {
  try {
    await api("/api/configure", { scene: { id: scene.id, ...change } });
    notice("");
    await refresh();
  } catch (e) {
    notice(e.message);
    sceneSignature = "";
    await refresh();
  }
}
function renderSchedule(periods) {
  $("#periods").replaceChildren();
  periods.forEach((p, i) => addPeriod(p, i));
}
// Each row's controls are found by class: a row has two inputs (time, offset) and two selects.
function addPeriod(
  p = { start: 1200, collection: "space" },
  i = $("#periods").children.length,
) {
  const row = el("div", "period");
  row.append(el("span", "number", String(i + 1).padStart(2, "0")));
  const modeLabel = el("label", "", "STARTS AT"),
    mode = el("select", "period-mode");
  for (const [value, label] of [
    ["time", "Time"],
    ["sunrise", "Sunrise"],
    ["sunset", "Sunset"],
  ]) {
    const o = el("option", "", label);
    o.value = value;
    mode.append(o);
  }
  mode.value = p.anchor || "time";
  modeLabel.append(mode);
  const timeLabel = el("label", "", "FROM"),
    time = el("input", "period-time");
  time.type = "time";
  time.required = true;
  time.value =
    String(Math.floor(p.start / 60)).padStart(2, "0") +
    ":" +
    String(p.start % 60).padStart(2, "0");
  timeLabel.append(time);
  const offsetLabel = el("label", "", "OFFSET (MINUTES)"),
    offset = el("input", "period-offset");
  offset.type = "number";
  offset.step = 5;
  offset.min = -240;
  offset.max = 240;
  offset.value = p.offset || 0;
  offsetLabel.append(offset);
  const colLabel = el("label", "period-collection-label", "SHOW THIS COLLECTION"),
    col = el("select", "period-collection");
  options(col, true, p.collection);
  colLabel.append(col);
  const remove = el("button", "remove", "×");
  remove.setAttribute("aria-label", "Remove schedule entry");
  remove.onclick = () => row.remove();
  const preview = el("p", "fine period-preview");
  preview.setAttribute("aria-live", "polite");
  const update = () => {
    timeLabel.hidden = mode.value !== "time";
    offsetLabel.hidden = mode.value === "time";
    previewPeriod(row);
  };
  mode.onchange = update;
  offset.oninput = () => previewPeriod(row);
  row.append(modeLabel, timeLabel, offsetLabel, colLabel, remove, preview);
  $("#periods").append(row);
  update();
}
const clock = (m) =>
  new Date(2000, 0, 1, Math.floor(m / 60), m % 60).toLocaleTimeString([], {
    hour: "numeric",
    minute: "2-digit",
  });
// "Sunset +30 → 6:42 PM today", from the times this Mac works out for the selected TV (the same math as the TV).
function previewPeriod(row) {
  const anchor = row.querySelector(".period-mode").value,
    out = row.querySelector(".period-preview");
  out.hidden = anchor === "time";
  if (anchor === "time") return;
  const offset = Math.round(Number(row.querySelector(".period-offset").value) || 0),
    name = anchor === "sunrise" ? "Sunrise" : "Sunset",
    label =
      name + (offset > 0 ? " +" + offset : offset < 0 ? " −" + -offset : ""),
    sun = state?.devices.find((d) => d.id === selectedDevice)?.sun_today;
  if (!sun?.zone)
    out.textContent = `${label} · check the TV to see today’s time.`;
  else if (sun.source === "none")
    out.textContent = `${label} · ${sun.zone} has no place for sun times: set one below.`;
  else if (sun.polar)
    out.textContent = `${label} → no ${anchor} today, so this entry keeps its saved time.`;
  else
    out.textContent = `${label} → ${clock((((sun[anchor] + offset) % 1440) + 1440) % 1440)} today`;
}
function renderBackups() {
  const list = $("#backup-list");
  list.replaceChildren();
  if (!state.backups.length)
    list.append(
      el("div", "empty", "No saved settings yet. Back up a TV to start."),
    );
  state.backups.forEach((b) => {
    const r = el("div", "backup-row"),
      info = el("div");
    info.append(
      el("b", "", b.name),
      el(
        "small",
        "",
        Math.ceil(b.bytes / 1024) + " KB · portable GlassOS settings",
      ),
    );
    const button = el("button", "secondary", "Copy to selected TV");
    button.onclick = () => action("restore", { backup: b.id });
    r.append(info, button);
    list.append(r);
  });
}
async function refresh() {
  try {
    state = await api("/api/state");
    render();
  } catch (e) {
    notice(e.message);
  }
}
async function action(name, extra = {}) {
  try {
    if (
      !["bundle", "catalog", "catalog-import"].includes(name) &&
      !selectedDevice
    )
      throw Error("Add or choose a Fire Stick first.");
    notice("");
    await api("/api/action", {
      action: name,
      device: selectedDevice,
      ...extra,
    });
    await refresh();
  } catch (e) {
    notice(e.message);
  }
}
document
  .querySelectorAll("[data-page]")
  .forEach((b) => (b.onclick = () => go(b.dataset.page)));
document
  .querySelectorAll("[data-go]")
  .forEach((b) => (b.onclick = () => go(b.dataset.go)));
document
  .querySelectorAll("[data-action]")
  .forEach((b) => (b.onclick = () => action(b.dataset.action)));
document
  .querySelectorAll("[data-close]")
  .forEach((b) => (b.onclick = () => $("#" + b.dataset.close).close()));
$("#device").onchange = (e) => {
  selectedDevice = e.target.value;
  localStorage.setItem("glass-device", selectedDevice);
  render();
};
$("#refresh").onclick = () => {
  sceneSignature = "";
  refresh();
};
$("#add-device").onclick = () => $("#device-dialog").showModal();
$("#toggle-log").onclick = () => ($("#job-log").hidden = !$("#job-log").hidden);
$("#device-form").onsubmit = async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  try {
    await api("/api/device", {
      name: f.get("name"),
      address: f.get("address"),
    });
    $("#device-dialog").close();
    e.target.reset();
    await refresh();
    selectedDevice = state.devices.at(-1).id;
    render();
    await action("inspect");
  } catch (err) {
    notice(err.message);
    $("#device-dialog").close();
  }
};
$("#save-budget").onclick = async () => {
  try {
    await api("/api/configure", { budget_mb: Number($("#budget").value) });
    notice("");
    await refresh();
  } catch (e) {
    notice(e.message);
  }
};
$("#add-period").onclick = () => addPeriod();
$("#save-schedule").onclick = async () => {
  try {
    const periods = [...$("#periods").children].map((r) => {
      const anchor = r.querySelector(".period-mode").value,
        collection = r.querySelector(".period-collection").value;
      if (anchor !== "time")
        return {
          anchor,
          offset: Number(r.querySelector(".period-offset").value),
          collection,
        };
      const [h, m] = r
        .querySelector(".period-time")
        .value.split(":")
        .map(Number);
      return { start: h * 60 + m, collection };
    });
    // The selected TV's place and time zone turn sun entries into today's times.
    await api("/api/configure", {
      schedule: periods,
      device: selectedDevice || null,
    });
    scheduleLoaded = false;
    notice("");
    await refresh();
    footer("Daily rhythm saved. Sync scenery to send it to your TV.");
  } catch (e) {
    notice(e.message);
  }
};
function chooseFile(file) {
  if (!file) return;
  selectedFile = file;
  const f = $("#import-form");
  f.elements.title.value = file.name
    .replace(/\.[^.]+$/, "")
    .replace(/[-_]/g, " ")
    .slice(0, 120);
  options($("#import-collection"), false, "other");
  $("#import-filename").textContent = file.name + " · " + size(file.size);
  $("#import-dialog").showModal();
}
$("#drop-zone").onclick = () => $("#video-file").click();
$("#drop-zone").onkeydown = (e) => {
  if (e.key === "Enter" || e.key === " ") {
    e.preventDefault();
    $("#video-file").click();
  }
};
$("#video-file").onchange = (e) => {
  chooseFile(e.target.files[0]);
  e.target.value = "";
};
["dragenter", "dragover"].forEach((t) =>
  $("#drop-zone").addEventListener(t, (e) => {
    e.preventDefault();
    $("#drop-zone").classList.add("drag");
  }),
);
["dragleave", "drop"].forEach((t) =>
  $("#drop-zone").addEventListener(t, (e) => {
    e.preventDefault();
    $("#drop-zone").classList.remove("drag");
  }),
);
$("#drop-zone").addEventListener("drop", (e) =>
  chooseFile(e.dataTransfer.files[0]),
);
$("#import-form").onsubmit = async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  $("#import-dialog").close();
  notice("");
  $("#job-title").textContent = "Uploading video to this Mac…";
  try {
    const r = await fetch("/api/upload", {
      method: "POST",
      headers: {
        "X-GlassOS-Token": token,
        "X-Filename": encodeURIComponent(selectedFile.name),
        "X-Title": encodeURIComponent(f.get("title")),
        "X-Collection": f.get("collection"),
        "X-Seconds": f.get("seconds"),
      },
      body: selectedFile,
    });
    const out = await r.json();
    if (!r.ok) throw Error(out.error);
    await refresh();
  } catch (err) {
    notice(err.message);
  }
  selectedFile = null;
};
refresh();
setInterval(refresh, 2000);

let catalogSignature = "",
  iconSignature = "",
  appSignature = "";
function renderChanges(target, changes, empty) {
  target.replaceChildren();
  if (!changes?.length) {
    target.append(el("p", "fine", empty));
    return;
  }
  for (const c of changes)
    row(
      target,
      c.label,
      `${String(c.before)} → ${String(c.after)}`,
      !c.attention,
    );
}
function renderExtras(device) {
  const h = device?.health,
    profile = device?.profile;
  $("#profile-title").textContent = profile?.title || "Get to know your TV.";
  const details = $("#profile-details");
  details.replaceChildren();
  if (h) {
    row(details, "Platform", profile?.platform || "Unknown");
    row(details, "Model", h.model);
    row(details, "Android", `${h.android || "—"} · API ${h.sdk}`);
    row(details, "Firmware", h.build);
    row(
      details,
      "RAM visible to Android",
      h.memory_bytes ? size(h.memory_bytes) : "Not recorded",
    );
    row(details, "Supported ABIs", h.abi || "Not recorded");
    row(details, "GlassOS", h.version);
  } else
    details.append(el("p", "fine", "Run a device check to build its profile."));
  renderChanges(
    $("#profile-changes"),
    device?.changes,
    device?.previous_health
      ? "No tracked configuration changes."
      : "A second check will show what changed.",
  );
  const delta =
    h && device.previous_health
      ? h.free_bytes - device.previous_health.free_bytes
      : null;
  $("#storage-change").textContent =
    delta === null
      ? ""
      : `Free storage: ${delta >= 0 ? "+" : "−"}${size(Math.abs(delta))} since the last check.`;
  $("#baseline-date").textContent = device?.baseline
    ? "Saved " + new Date(device.baseline.checked_at * 1000).toLocaleString()
    : "Save a baseline before setting up or changing a device.";
  renderChanges(
    $("#baseline-changes"),
    device?.baseline_changes,
    device?.baseline
      ? "Matches the saved configuration baseline."
      : "No baseline saved yet.",
  );
  const apps = device?.apps || [],
    aSig = JSON.stringify(apps);
  if (aSig !== appSignature) {
    appSignature = aSig;
    const select = $("#icon-package"),
      old = select.value;
    select.replaceChildren();
    const prompt = el(
      "option",
      "",
      apps.length ? "Choose an app" : "Read apps from your TV first",
    );
    prompt.value = "";
    select.append(prompt);
    apps.forEach((pkg) => {
      const o = el("option", "", pkg);
      o.value = pkg;
      select.append(o);
    });
    if (apps.includes(old)) select.value = old;
  }
  const icons = state.icons || {},
    iSig = JSON.stringify(icons) + selectedDevice;
  if (iSig !== iconSignature) {
    iconSignature = iSig;
    const list = $("#icon-list");
    list.replaceChildren();
    if (!Object.keys(icons).length)
      list.append(el("p", "fine", "No custom app images saved yet."));
    for (const [pkg, info] of Object.entries(icons)) {
      const r = el("div", "check-row");
      r.append(el("span", "", pkg), el("small", "", size(info.bytes)));
      const button = el("button", "secondary", "Restore original");
      button.dataset.action = "icon-reset";
      button.onclick = () => action("icon-reset", { package: pkg });
      r.append(button);
      list.append(r);
    }
  }
  const sig =
    JSON.stringify(state.catalog) +
    $("#catalog-search").value +
    JSON.stringify(state.scenes.map((s) => s.catalog_id));
  if (sig !== catalogSignature) {
    catalogSignature = sig;
    renderCatalog();
  }
  renderAppLock(device);
  renderScreensaver(device);
  renderMirroring(device);
}
function renderCatalog() {
  const entries = state.catalog?.entries || [],
    query = $("#catalog-search").value.trim().toLowerCase();
  const visible = entries.filter((e) =>
    `${e.title} ${e.place}`.toLowerCase().includes(query),
  );
  $("#catalog-count").textContent = entries.length
    ? `${visible.length} of ${entries.length} scenes`
    : "Load a feed to begin";
  const list = $("#catalog-list");
  list.replaceChildren();
  if (!visible.length)
    list.append(
      el(
        "p",
        "fine",
        entries.length
          ? "No scenes match your search."
          : "Your catalog will appear here.",
      ),
    );
  for (const entry of visible.slice(0, 200)) {
    const card = el("article", "panel catalog-card");
    card.append(
      el("span", "eyebrow", entry.quality + " · SILENT AFTER IMPORT"),
      el("h3", "", entry.title),
      el("p", "", entry.place || "Aerial scene"),
    );
    const select = el("select");
    options(select, false, entry.collection);
    select.setAttribute("aria-label", "Collection for " + entry.title);
    card.append(select);
    const imported = state.scenes.some((s) => s.catalog_id === entry.id);
    const button = el(
      "button",
      "primary",
      imported ? "In your library" : "Download & compress",
    );
    if (!imported) {
      button.dataset.action = "catalog-import";
      button.onclick = () =>
        action("catalog-import", {
          entry: entry.id,
          seconds: $("#catalog-seconds").value,
          collection: select.value,
        });
    }
    button.disabled = imported;
    card.append(button);
    const link = el("a", "fine", "View source video ↗");
    link.href = entry.url;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    card.append(link);
    list.append(card);
  }
  if (visible.length > 200)
    list.append(
      el(
        "p",
        "fine",
        "Showing the first 200 matches. Search to narrow this catalog.",
      ),
    );
}
$("#catalog-search").oninput = () => render();
$("#catalog-form").onsubmit = (e) => {
  e.preventDefault();
  action("catalog", { url: $("#feed-url").value });
};
$("#icon-form").onsubmit = async (e) => {
  e.preventDefault();
  try {
    const file = $("#icon-file").files[0];
    if (!file) throw Error("Choose an image first.");
    const response = await fetch("/api/icon", {
      method: "POST",
      headers: {
        "X-GlassOS-Token": token,
        "X-Package": $("#icon-package").value,
      },
      body: file,
    });
    const result = await response.json();
    if (!response.ok) throw Error(result.error);
    $("#icon-file").value = "";
    notice("");
    await refresh();
  } catch (error) {
    notice(error.message);
  }
};

// ---------- TV controls ----------
// Panels render from the selected device's record; buttons that open a dialog keep data-action, so they are disabled
// with the others while a job runs, and get their own onclick below (after the data-action wiring above).

let appLockSignature = "",
  codeFirst = "",
  codeEntry = "";
function renderAppLock(device) {
  const a = device?.applock,
    sdk = Number(device?.health?.sdk),
    sig = JSON.stringify(a || null) + (device?.id || "") + sdk;
  if (sig === appLockSignature) return;
  appLockSignature = sig;
  // Below Android 10 the guard needs no Display over other apps (unknown until a device Check: as if it did).
  const overlayNeeded = !(sdk > 0) || sdk >= 29;
  $("#applock-checked").textContent = a
    ? "Checked " + new Date(a.checked_at * 1000).toLocaleString()
    : "Not checked yet";
  $("#applock-off").hidden = !!a && !a.code && !a.locked;
  // Display over other apps only helps when the guard cannot run without it.
  $("#applock-guard").hidden = !a || a.guard || a.overlay || !overlayNeeded;
  const list = $("#applock-status");
  list.replaceChildren();
  if (!a) {
    list.append(
      el(
        "p",
        "fine",
        device
          ? "Choose Check to read this TV’s app lock."
          : "Choose a device to get started.",
      ),
    );
    return;
  }
  row(list, "Lock code", a.code ? "Set" : "Not set");
  const n = a.installed_locked,
    gone = a.locked - a.installed_locked;
  row(
    list,
    "Locked apps",
    (n === 0 ? "None" : n === 1 ? "1 app" : `${n} apps`) +
      (gone > 0 ? ` · ${gone} more, not installed` : ""),
  );
  const missing = [
    !a.usage && "Usage access",
    overlayNeeded && !a.overlay && "Display over other apps",
  ]
    .filter(Boolean)
    .join(" and ");
  row(
    list,
    "Casting protection",
    !a.guard
      ? "Off — needs " + missing
      : a.guard_running !== false
        ? "On"
        : a.locked
          ? "Allowed — starts when GlassOS next opens"
          : "Allowed — on while an app is locked",
    a.guard,
  );
}

// The code lives only in these two variables while the dialog is open, never in storage.
function forgetCode() {
  codeFirst = "";
  codeEntry = "";
}
function showCode() {
  $("#code-dialog")
    .querySelectorAll(".code-dots i")
    .forEach((d, i) => d.classList.toggle("on", i < codeEntry.length));
  $("#code-progress").textContent = `${codeEntry.length} of 4 buttons pressed`;
  $("#code-next").disabled = codeEntry.length !== 4;
  $("#code-next").textContent = codeFirst ? "Save to TV" : "Next";
  $("#code-title").textContent = codeFirst
    ? "Enter it again"
    : "A new lock code";
  $("#code-step").textContent = codeFirst
    ? "Press the same 4 buttons, in the same order."
    : "Press 4 buttons, as you would on the remote: the arrows, Rewind, Play/Pause or Fast forward. A button can repeat.";
}
function pressKey(key) {
  if (codeEntry.length >= 4) return;
  codeEntry += key;
  $("#code-error").hidden = true;
  showCode();
}
$("#code-dialog")
  .querySelectorAll("[data-key]")
  .forEach((b) => (b.onclick = () => pressKey(b.dataset.key)));
$("#code-dialog").onkeydown = (e) => {
  const key = { ArrowUp: "U", ArrowDown: "D", ArrowLeft: "L", ArrowRight: "R" }[
    e.key
  ];
  if (key) {
    e.preventDefault();
    pressKey(key);
  } else if (e.key === "Backspace") {
    e.preventDefault();
    codeEntry = codeEntry.slice(0, -1);
    showCode();
  }
};
$("#code-clear").onclick = () => {
  codeEntry = "";
  showCode();
};
$("#code-dialog").addEventListener("close", () => {
  forgetCode();
  showCode();
});
$("#applock-code").onclick = () => {
  forgetCode();
  $("#code-error").hidden = true;
  showCode();
  $("#code-dialog").showModal();
};
$("#code-form").onsubmit = (e) => {
  e.preventDefault();
  if (codeEntry.length !== 4) return;
  // Focus moves into the pad before Next is disabled, so the arrow keys keep reaching the dialog.
  if (!codeFirst) {
    codeFirst = codeEntry;
    codeEntry = "";
    $("#code-dialog .key").focus();
    showCode();
    return;
  }
  if (codeEntry !== codeFirst) {
    forgetCode();
    $("#code-dialog .key").focus();
    showCode();
    $("#code-error").textContent =
      "The codes did not match, so nothing was sent. Start again.";
    $("#code-error").hidden = false;
    return;
  }
  const code = codeEntry;
  forgetCode();
  $("#code-dialog").close();
  action("applock-code", { code });
};
$("#applock-off").onclick = () => $("#applock-off-dialog").showModal();
$("#applock-off-form").onsubmit = (e) => {
  e.preventDefault();
  $("#applock-off-dialog").close();
  action("applock-off");
};
$("#applock-guard").onclick = () => $("#applock-guard-dialog").showModal();
$("#applock-guard-form").onsubmit = (e) => {
  e.preventDefault();
  $("#applock-guard-dialog").close();
  action("applock-guard");
};

// The screensaver panel. "Use GlassOS scenery" offers to turn off what would stop it, as the last Check found it.

let screensaverSignature = "";
const settingOn = (v) => v !== undefined && v !== "null" && v !== "0";
function renderScreensaver(device) {
  const s = device?.screensaver,
    sig = JSON.stringify(s || null) + (device?.id || "");
  if (sig === screensaverSignature) return;
  screensaverSignature = sig;
  $("#screensaver-checked").textContent = s?.checked_at
    ? "Checked " + new Date(s.checked_at * 1000).toLocaleString()
    : "Not checked yet";
  $("#screensaver-set").hidden = !s?.installed;
  $("#screensaver-restore").hidden = !s?.previous;
  const list = $("#screensaver-status");
  list.replaceChildren();
  const v = s?.current;
  if (!v) {
    list.append(
      el(
        "p",
        "fine",
        device
          ? "Choose Check to read this TV’s screensaver settings."
          : "Choose a device to get started.",
      ),
    );
    return;
  }
  const components = v.screensaver_components;
  row(
    list,
    "Screensaver",
    s.active
      ? "GlassOS scenery"
      : components === "null"
        ? "Not set"
        : components,
  );
  row(
    list,
    "GlassOS screensaver",
    s.installed ? "On this TV" : "Not on this TV — update GlassOS",
    s.installed,
  );
  const idle =
    v.screensaver_enabled === "1" && v.screensaver_activate_on_sleep === "1";
  row(list, "Starts when idle", idle ? "Yes" : "No", idle || !s.active);
  row(
    list,
    "Stay awake while plugged in",
    settingOn(v.stay_on_while_plugged_in) ? "On" : "Off",
    !settingOn(v.stay_on_while_plugged_in),
  );
  if (v.amazon_ambient_enabled !== "null")
    row(
      list,
      "Amazon Ambient Experience",
      settingOn(v.amazon_ambient_enabled) ? "On" : "Off",
      !settingOn(v.amazon_ambient_enabled),
    );
  row(
    list,
    "Saved for Restore",
    s.previous
      ? "Since " + new Date(s.saved_at * 1000).toLocaleDateString()
      : "Nothing saved",
  );
  (s.blockers || []).forEach((t) => row(list, "Stops it", t, false));
  (s.warnings || []).forEach((t) => row(list, "Note", t, false));
}
// The Screen Mirroring panel: read only, as the last Check found it.
let mirroringSignature = "";
const MIRROR_RES = { auto: "Auto", "1080p": "1080p", "720p": "720p" };
function renderMirroring(device) {
  const m = device?.mirroring,
    sig = JSON.stringify(m || null) + (device?.id || "");
  if (sig === mirroringSignature) return;
  mirroringSignature = sig;
  $("#mirroring-checked").textContent = m?.checked_at
    ? "Checked " + new Date(m.checked_at * 1000).toLocaleString()
    : "Not checked yet";
  const list = $("#mirroring-status");
  list.replaceChildren();
  if (!m) {
    list.append(
      el(
        "p",
        "fine",
        device
          ? "Choose Check to read this TV’s Screen Mirroring."
          : "Choose a device to get started.",
      ),
    );
    return;
  }
  if (!m.supported) {
    row(
      list,
      "Screen Mirroring",
      "Not on this TV or GlassOS build (needs Fire OS 6 or later)",
      false,
    );
    return;
  }
  // Off reads Off, whatever the receiver last did; as GlassOS Settings shows it.
  const state = !m.on
    ? "Off"
    : m.gave_up || m.error === "crashed"
      ? "Stopped unexpectedly"
      : m.state === "ERROR"
        ? "Could not start"
        : m.state !== "RUNNING"
          ? "Starting"
          : m.pin_shown
            ? m.screen
              ? "Showing a code on the TV"
              : "Code requested (hidden on the TV)"
            : m.mode === "MIRROR" || m.mode === "VIDEO"
              ? "Mirroring" + (m.screen ? "" : " (hidden on the TV)")
              : m.mode === "AUDIO"
                ? "Playing music"
                : `Ready as “${m.name}”`;
  row(list, "Screen Mirroring", state, !m.on || !(m.gave_up || m.state === "ERROR"));
  row(list, "Require PIN", m.pin ? "On" : "Off");
  row(list, "Resolution", MIRROR_RES[m.resolution] || m.resolution);
  row(list, "Sender controls TV volume", m.sync_volume ? "On" : "Off");
  if (m.restarts > 0)
    row(list, "Restarts after a crash", String(m.restarts), false);
}

$("#screensaver-set").onclick = () => {
  const v = state.devices.find((d) => d.id === selectedDevice)?.screensaver
    ?.current;
  // Offered only when it is on (and Amazon's setting only where the TV has it); ticked by default.
  $("#screensaver-ambient").hidden = !settingOn(v?.amazon_ambient_enabled);
  $("#screensaver-stay").hidden = !settingOn(v?.stay_on_while_plugged_in);
  $("#screensaver-form")
    .querySelectorAll("input[type=checkbox]")
    .forEach((c) => (c.checked = true));
  $("#screensaver-dialog").showModal();
};
$("#screensaver-form").onsubmit = (e) => {
  e.preventDefault();
  const f = e.target;
  $("#screensaver-dialog").close();
  action("screensaver-set", {
    options: {
      ambient: !$("#screensaver-ambient").hidden && f.ambient.checked,
      stayAwake: !$("#screensaver-stay").hidden && f.stayAwake.checked,
    },
  });
};

// ---------- Sun times location (Daily rhythm page) ----------
// Coordinates are rounded to 0.1° here, before anything leaves the page; the precise position from this Mac is
// never stored.

let sunSignature = "",
  sunFormSignature = "";
const tenth = (x) => Math.round(x * 10) / 10 + 0;
function renderSun(device) {
  const s = device?.sun_status,
    sig = JSON.stringify([device?.id, device?.sun, s]),
    form = JSON.stringify([device?.id, device?.sun]);
  if (sig === sunSignature) return;
  // Fill the fields from the saved place when it (or the device) changes, so a Check keeps a place found but not
  // saved yet; never under the user's typing.
  const typing =
    document.activeElement?.tagName === "INPUT" &&
    $("#sun-panel").contains(document.activeElement);
  if (form !== sunFormSignature && !typing) {
    $("#sun-label").value = device?.sun?.label || "";
    $("#sun-lat").value = device?.sun ? device.sun.lat : "";
    $("#sun-lon").value = device?.sun ? device.sun.lon : "";
  }
  sunFormSignature = form;
  sunSignature = sig;
  $("#sun-checked").textContent = s
    ? "Checked " + new Date(s.checked_at * 1000).toLocaleString()
    : "Not checked yet";
  const list = $("#sun-status");
  list.replaceChildren();
  if (!s) {
    list.append(
      el(
        "p",
        "fine",
        device
          ? "Choose Check to see where this TV’s sun times come from."
          : "Choose a device to get started.",
      ),
    );
    return;
  }
  row(
    list,
    "On the TV",
    s.source === "manager"
      ? `${s.label || "Your place"} · set from this Mac`
      : s.source === "timezone"
        ? `${s.label} · estimate from ${s.zone}`
        : `No place for ${s.zone} · clock times`,
    s.source === "manager",
  );
  row(
    list,
    "That day",
    s.sunrise && s.sunset
      ? `Sunrise ${s.sunrise} · Sunset ${s.sunset}`
      : s.polar === "up"
        ? "The sun does not set"
        : s.polar === "down"
          ? "The sun does not rise"
          : "No sun times",
  );
}
$("#sun-here").onclick = () => {
  if (!navigator.geolocation) {
    notice(
      "This browser cannot share this Mac’s location. Enter the latitude and longitude instead.",
    );
    return;
  }
  notice("");
  sunFound("");
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      $("#sun-lat").value = tenth(pos.coords.latitude);
      $("#sun-lon").value = tenth(pos.coords.longitude);
      footer(
        "Location filled in from this Mac, rounded to 0.1°. Add a place name if you like, then choose Save to TV.",
      );
    },
    (error) =>
      notice(
        (error.code === 1
          ? "This Mac did not share its location (Location Services or the browser said no)."
          : "This Mac could not find its location just now.") +
          " Enter the latitude and longitude instead, for example from a map.",
      ),
    { enableHighAccuracy: false, timeout: 15000, maximumAge: 600000 },
  );
};
$("#sun-save").onclick = () => {
  const lat = $("#sun-lat").value.trim(),
    lon = $("#sun-lon").value.trim();
  if (!lat || !lon || !isFinite(lat) || !isFinite(lon)) {
    notice(
      "Enter a latitude and a longitude, or choose Use this Mac’s location.",
    );
    return;
  }
  sunFound("");
  action("sun-location", {
    location: {
      lat: tenth(Number(lat)),
      lon: tenth(Number(lon)),
      label: $("#sun-label").value.trim(),
    },
  });
};
$("#sun-clear").onclick = () => {
  sunFound("");
  action("sun-location", { location: null });
};
// The lookup runs in the manager on this Mac (this page may only talk to it). Its job's result fills the form; the TV
// gets the place only from Save to TV.
let sunLookupJob = "";
function sunFound(text, warn = false) {
  $("#sun-found").textContent = text;
  $("#sun-found").hidden = !text;
  $("#sun-found").classList.toggle("warn", warn);
}
function sunLookupDone() {
  const job = sunLookupJob && state.jobs.find((j) => j.id === sunLookupJob);
  if (!job || job.status === "running") return;
  sunLookupJob = "";
  if (job.status !== "done") {
    sunFound(
      "Couldn’t look up a place from this Mac’s internet address. Type the place instead.",
      true,
    );
    return;
  }
  const r = job.result;
  $("#sun-label").value = r.label;
  $("#sun-lat").value = r.lat;
  $("#sun-lon").value = r.lon;
  sunFound(
    `Found: ${r.label || `${r.lat}, ${r.lon}`} — check this is right, then Save to TV.`,
  );
}
$("#sun-lookup").onclick = async () => {
  try {
    notice("");
    sunFound("");
    sunLookupJob = (await api("/api/action", { action: "sun-lookup" })).job;
    // Quicker than the regular refresh while the lookup runs (8 s at most).
    for (let i = 0; i < 30; i++) {
      await refresh();
      if (!sunLookupJob) break;
      await new Promise((resolve) => setTimeout(resolve, 400));
    }
  } catch (e) {
    notice(e.message);
  }
};
