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
  n >= 1e9 ? (n / 1e9).toFixed(2) + " GB" : (n / 1e6).toFixed(1) + " MB";
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
  const bs = JSON.stringify(state.backups);
  if (bs !== backupSignature) {
    backupSignature = bs;
    renderBackups();
  }
  const job = state.jobs.at(-1),
    busy = state.jobs.some((j) => j.status === "running");
  document
    .querySelectorAll("[data-action]")
    .forEach(
      (b) =>
        (b.disabled =
          busy ||
          (!selectedDevice && b.dataset.action !== "bundle") ||
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
    $("#job-message").textContent =
      job.error || job.messages.at(-1) || "Starting…";
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
function addPeriod(
  p = { start: 1200, collection: "space" },
  i = $("#periods").children.length,
) {
  const row = el("div", "period");
  row.append(el("span", "number", String(i + 1).padStart(2, "0")));
  const timeLabel = el("label", "", "FROM"),
    time = el("input");
  time.type = "time";
  time.required = true;
  time.value =
    String(Math.floor(p.start / 60)).padStart(2, "0") +
    ":" +
    String(p.start % 60).padStart(2, "0");
  timeLabel.append(time);
  const colLabel = el("label", "", "SHOW THIS COLLECTION"),
    col = el("select");
  options(col, true, p.collection);
  colLabel.append(col);
  const remove = el("button", "remove", "×");
  remove.setAttribute("aria-label", "Remove schedule entry");
  remove.onclick = () => row.remove();
  row.append(timeLabel, colLabel, remove);
  $("#periods").append(row);
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
    if (name !== "bundle" && !selectedDevice)
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
      const [h, m] = r.querySelector("input").value.split(":").map(Number);
      return { start: h * 60 + m, collection: r.querySelector("select").value };
    });
    await api("/api/configure", { schedule: periods });
    scheduleLoaded = false;
    notice("");
    await refresh();
    $("#job-message").textContent =
      "Daily rhythm saved. Sync scenery to send it to your TV.";
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
