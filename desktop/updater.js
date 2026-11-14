"use strict";
// Keeps the game up to date without reinstalling the app.
//
// The app ships with a copy of the game (bundledDir). Newer copies are
// downloaded next to a manifest, version.json, published with every build:
//   { "build": 346, "files": [{ "name": "index.html", "size": 123, "sha256": "..." }] }
// Each download is checked against the manifest and only swapped in once every
// file is complete, so a failed update leaves the game as it was.
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const MAX_FILES = 20, MAX_SIZE = 20 * 1024 * 1024;
const SAFE_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$/;

function validManifest(m) {
  if (!m || !Number.isInteger(m.build) || m.build < 1) return false;
  if (!Array.isArray(m.files) || !m.files.length || m.files.length > MAX_FILES) return false;
  for (const f of m.files) {
    if (!f || typeof f.name !== "string" || !SAFE_NAME.test(f.name)) return false;
    if (!Number.isInteger(f.size) || f.size < 0 || f.size > MAX_SIZE) return false;
    if (typeof f.sha256 !== "string" || !/^[0-9a-f]{64}$/.test(f.sha256)) return false;
  }
  return m.files.some((f) => f.name === "index.html");
}
const sha256 = (buf) => crypto.createHash("sha256").update(buf).digest("hex");

class Updater {
  constructor({ bundledDir, dataDir, manifestUrl, fetchImpl }) {
    this.bundledDir = bundledDir;
    this.dataDir = dataDir;
    this.manifestUrl = manifestUrl;
    this.fetch = fetchImpl || fetch;
    this.busy = null;
  }

  /** The manifest in a game folder, if the folder holds a complete game. */
  load(dir) {
    try {
      const m = JSON.parse(fs.readFileSync(path.join(dir, "version.json"), "utf8"));
      if (!validManifest(m)) return null;
      for (const f of m.files) if (fs.statSync(path.join(dir, f.name)).size !== f.size) return null;
      return { build: m.build, files: m.files, dir };
    } catch (e) { return null; }
  }

  /** The game to run: the newest of the bundled copy and the downloaded ones. */
  current() {
    let best = this.load(this.bundledDir) || { build: 0, files: [], dir: this.bundledDir };
    let names = [];
    try { names = fs.readdirSync(this.dataDir); } catch (e) {}
    for (const name of names) {
      if (!/^build-\d+$/.test(name)) continue;
      const g = this.load(path.join(this.dataDir, name));
      if (g && g.build > best.build) best = g;
    }
    return best;
  }

  /** The file on disk for a name the current game lists, or null. */
  resolve(name) {
    const g = this.current();
    if (!g.files.some((f) => f.name === name)) return null;
    return path.join(g.dir, name);
  }

  async remote() {
    const res = await this.fetch(this.manifestUrl + "?t=" + Date.now(), { cache: "no-store" });
    if (!res.ok) throw new Error("update check failed: HTTP " + res.status);
    const m = await res.json();
    if (!validManifest(m)) throw new Error("update check failed: bad version file");
    return m;
  }

  async check() {
    const m = await this.remote(), have = this.current().build;
    return { available: m.build > have, build: m.build, current: have };
  }

  /** Download the newest game if there is one. Resolves to { updated, build }. */
  apply() {
    if (!this.busy) this.busy = this.download().finally(() => { this.busy = null; });
    return this.busy;
  }

  async download() {
    const m = await this.remote(), have = this.current().build;
    if (m.build <= have) return { updated: false, build: have };
    const base = this.manifestUrl.slice(0, this.manifestUrl.lastIndexOf("/") + 1);
    const tmp = path.join(this.dataDir, "tmp-" + m.build + "-" + process.pid);
    fs.rmSync(tmp, { recursive: true, force: true });
    fs.mkdirSync(tmp, { recursive: true });
    try {
      for (const f of m.files) {
        const res = await this.fetch(base + f.name + "?t=" + Date.now(), { cache: "no-store" });
        if (!res.ok) throw new Error("download failed: " + f.name + " HTTP " + res.status);
        const buf = Buffer.from(await res.arrayBuffer());
        if (buf.length !== f.size || sha256(buf) !== f.sha256) throw new Error("download failed: " + f.name + " did not match");
        fs.writeFileSync(path.join(tmp, f.name), buf);
      }
      fs.writeFileSync(path.join(tmp, "version.json"), JSON.stringify({ build: m.build, files: m.files }));
      const dest = path.join(this.dataDir, "build-" + m.build);
      fs.rmSync(dest, { recursive: true, force: true });
      fs.renameSync(tmp, dest);
    } catch (e) {
      fs.rmSync(tmp, { recursive: true, force: true });
      throw e;
    }
    this.cleanup(m.build);
    return { updated: true, build: m.build };
  }

  /** Remove downloaded games older than the one in use. */
  cleanup(keep) {
    let names = [];
    try { names = fs.readdirSync(this.dataDir); } catch (e) {}
    for (const name of names) {
      const old = /^build-(\d+)$/.exec(name);
      if ((old && Number(old[1]) < keep) || /^tmp-/.test(name)) fs.rmSync(path.join(this.dataDir, name), { recursive: true, force: true });
    }
  }
}

module.exports = { Updater, validManifest, sha256 };
