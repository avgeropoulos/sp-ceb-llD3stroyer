#!/usr/bin/env node
"use strict";
// Copies the game into a folder and writes its version.json.
//   node make-manifest.js <game dir> <out dir> <build number>
// Used twice per build: for the copy bundled in the app, and for the copy the
// app downloads when it updates.
const fs = require("fs");
const path = require("path");
const { sha256 } = require("./updater");

const FILES = ["index.html", "hercules.jpg", "apollo.jpg"];
const [src, out, build] = process.argv.slice(2);
if (!src || !out || !/^\d+$/.test(build || "")) { console.error("usage: make-manifest.js <game dir> <out dir> <build number>"); process.exit(1); }

fs.mkdirSync(out, { recursive: true });
const files = FILES.map((name) => {
  const buf = fs.readFileSync(path.join(src, name));
  fs.writeFileSync(path.join(out, name), buf);
  return { name, size: buf.length, sha256: sha256(buf) };
});
fs.writeFileSync(path.join(out, "version.json"), JSON.stringify({ build: Number(build), files }, null, 1) + "\n");
console.log(`build ${build}: ${files.length} files in ${out}`);
