"use client";
import players from "./players.js";
import { createReactPlayer } from "./ReactPlayer.js";
const fallback = players[players.length - 1];
var src_default = createReactPlayer(players, fallback);
export {
  src_default as default
};
