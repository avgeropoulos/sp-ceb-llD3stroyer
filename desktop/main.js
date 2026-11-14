"use strict";
// Backyard Boop for the computer: a window that runs the game, plus an updater
// the game's main menu talks to ("Check for updates" / "Update now").
const { app, BrowserWindow, Menu, ipcMain, net, protocol, shell } = require("electron");
const path = require("path");
const { pathToFileURL } = require("url");
const { Updater } = require("./updater");

const MANIFEST_URL = "https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/backyard-boop-desktop/version.json";
const HOME = "boop://game/index.html";
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".jpg": "image/jpeg", ".png": "image/png" };

// The game is served from its own address so saved scores and stickers stay
// put when an update moves the files.
protocol.registerSchemesAsPrivileged([{ scheme: "boop", privileges: { standard: true, secure: true, supportFetchAPI: true } }]);

let win = null, updater = null;

function createWindow() {
  win = new BrowserWindow({
    width: 1280, height: 800, minWidth: 640, minHeight: 420,
    backgroundColor: "#2f6b2a", autoHideMenuBar: true, title: "Backyard Boop",
    webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true, sandbox: true, nodeIntegration: false },
  });
  // The game never leaves its own page; links open in the normal browser.
  win.webContents.on("will-navigate", (e, url) => { if (!url.startsWith("boop://game/")) e.preventDefault(); });
  win.webContents.setWindowOpenHandler(({ url }) => { if (url.startsWith("https://")) shell.openExternal(url); return { action: "deny" }; });
  win.on("closed", () => { win = null; });
  win.loadURL(HOME);
}

app.whenReady().then(() => {
  updater = new Updater({
    bundledDir: app.isPackaged ? path.join(process.resourcesPath, "game") : path.join(__dirname, "game"),
    dataDir: path.join(app.getPath("userData"), "games"),
    manifestUrl: process.env.BOOP_MANIFEST_URL || MANIFEST_URL,
    fetchImpl: (url, opts) => net.fetch(url, opts),
  });

  protocol.handle("boop", async (req) => {
    const name = decodeURIComponent(new URL(req.url).pathname).replace(/^\/+/, "") || "index.html";
    const file = updater.resolve(name);
    if (!file) return new Response("Not found", { status: 404 });
    const res = await net.fetch(pathToFileURL(file).toString());
    const type = TYPES[path.extname(file).toLowerCase()];
    return type ? new Response(res.body, { headers: { "content-type": type, "cache-control": "no-store" } }) : res;
  });

  const fromGame = (e) => !!win && e.sender === win.webContents && e.senderFrame.url.startsWith("boop://game/");
  ipcMain.on("boop:build", (e) => { e.returnValue = fromGame(e) ? updater.current().build : 0; });
  ipcMain.handle("boop:check", (e) => { if (!fromGame(e)) throw new Error("not allowed"); return updater.check(); });
  ipcMain.handle("boop:apply", async (e) => {
    if (!fromGame(e)) throw new Error("not allowed");
    const r = await updater.apply();
    if (r.updated && win) win.loadURL(HOME);   // start the new game
    return r;
  });

  Menu.setApplicationMenu(Menu.buildFromTemplate([
    ...(process.platform === "darwin" ? [{ role: "appMenu" }] : []),
    { label: "Game", submenu: [
      { label: "Back to the main menu", accelerator: "CmdOrCtrl+R", click: () => win && win.loadURL(HOME) },
      { role: "togglefullscreen" },
      { type: "separator" },
      { role: "quit" },
    ] },
  ]));

  createWindow();
  app.on("activate", () => { if (!win) createWindow(); });
});

app.on("window-all-closed", () => app.quit());
