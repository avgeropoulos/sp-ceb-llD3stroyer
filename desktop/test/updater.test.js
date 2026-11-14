"use strict";
const test = require("node:test");
const assert = require("node:assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const http = require("http");
const { Updater, sha256, validManifest } = require("../updater");

function gameDir(root, name, build, html) {
  const dir = path.join(root, name);
  fs.mkdirSync(dir, { recursive: true });
  const files = [["index.html", html], ["hercules.jpg", "H" + build]].map(([n, body]) => {
    fs.writeFileSync(path.join(dir, n), body);
    return { name: n, size: Buffer.byteLength(body), sha256: sha256(Buffer.from(body)) };
  });
  fs.writeFileSync(path.join(dir, "version.json"), JSON.stringify({ build, files }));
  return dir;
}

async function setup(t, remoteBuild) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "boop-"));
  const bundled = gameDir(root, "bundled", 5, "<p>five</p>");
  const served = gameDir(root, "served", remoteBuild, "<p>new</p>");
  const server = http.createServer((req, res) => {
    const file = path.join(served, path.basename(req.url.split("?")[0]));
    if (!fs.existsSync(file)) { res.writeHead(404); return res.end(); }
    res.end(fs.readFileSync(file));
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  t.after(() => { server.close(); fs.rmSync(root, { recursive: true, force: true }); });
  const up = new Updater({ bundledDir: bundled, dataDir: path.join(root, "data"), manifestUrl: `http://127.0.0.1:${server.address().port}/rel/version.json` });
  return { up, root, served, bundled };
}

test("runs the bundled game until an update is downloaded", async (t) => {
  const { up, bundled } = await setup(t, 9);
  assert.strictEqual(up.current().build, 5);
  assert.strictEqual(up.resolve("index.html"), path.join(bundled, "index.html"));
  assert.deepStrictEqual(await up.check(), { available: true, build: 9, current: 5 });
  assert.deepStrictEqual(await up.apply(), { updated: true, build: 9 });
  assert.strictEqual(up.current().build, 9);
  assert.strictEqual(fs.readFileSync(up.resolve("index.html"), "utf8"), "<p>new</p>");
  assert.deepStrictEqual(await up.check(), { available: false, build: 9, current: 9 });
  assert.deepStrictEqual(await up.apply(), { updated: false, build: 9 });
});

test("does nothing when the published game is not newer", async (t) => {
  const { up } = await setup(t, 5);
  assert.strictEqual((await up.check()).available, false);
  assert.deepStrictEqual(await up.apply(), { updated: false, build: 5 });
});

test("a download that does not match is thrown away", async (t) => {
  const { up, served, root } = await setup(t, 9);
  fs.writeFileSync(path.join(served, "hercules.jpg"), "XX");   // same size, different content
  await assert.rejects(up.apply(), /did not match/);
  assert.strictEqual(up.current().build, 5);
  assert.deepStrictEqual(fs.readdirSync(path.join(root, "data")), []);
});

test("a damaged download folder falls back to the bundled game", async (t) => {
  const { up } = await setup(t, 9);
  await up.apply();
  fs.rmSync(up.resolve("hercules.jpg"));
  assert.strictEqual(up.current().build, 5);
});

test("only serves files the game lists", async (t) => {
  const { up } = await setup(t, 9);
  assert.strictEqual(up.resolve("version.json"), null);
  assert.strictEqual(up.resolve("../served/index.html"), null);
});

test("old downloads are removed after a newer one", async (t) => {
  const { up, root, served } = await setup(t, 9);
  await up.apply();
  fs.rmSync(served, { recursive: true }); gameDir(root, "served", 12, "<p>newer</p>");
  await up.apply();
  assert.deepStrictEqual(fs.readdirSync(path.join(root, "data")), ["build-12"]);
});

test("rejects version files that could write outside the game folder", () => {
  const ok = { name: "index.html", size: 1, sha256: "a".repeat(64) };
  assert.ok(validManifest({ build: 1, files: [ok] }));
  assert.ok(!validManifest({ build: 1, files: [ok, { ...ok, name: "../evil.js" }] }));
  assert.ok(!validManifest({ build: 1, files: [{ ...ok, name: "other.html" }] }));
  assert.ok(!validManifest({ build: 0, files: [ok] }));
});
