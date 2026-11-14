"use strict";
// What the game page can ask the app for: which build it is, and updates.
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("bbDesktop", {
  build: ipcRenderer.sendSync("boop:build"),
  check: () => ipcRenderer.invoke("boop:check"),
  apply: () => ipcRenderer.invoke("boop:apply"),
});
