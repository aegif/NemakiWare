import { lazy } from "react";
import { canPlay } from "./patterns.js";
import HtmlPlayer from "./HtmlPlayer.js";
const Players = [
  {
    key: "hls",
    name: "hls.js",
    canPlay: canPlay.hls,
    canEnablePIP: () => true,
    player: lazy(
      () => import(
        /* webpackChunkName: 'reactPlayerHls' */
        "hls-video-element/react"
      )
    )
  },
  {
    key: "dash",
    name: "dash.js",
    canPlay: canPlay.dash,
    canEnablePIP: () => true,
    player: lazy(
      () => import(
        /* webpackChunkName: 'reactPlayerDash' */
        "dash-video-element/react"
      )
    )
  },
  {
    key: "mux",
    name: "Mux",
    canPlay: canPlay.mux,
    canEnablePIP: () => true,
    player: lazy(
      () => import(
        /* webpackChunkName: 'reactPlayerMux' */
        "@mux/mux-player-react"
      )
    )
  },
  {
    key: "youtube",
    name: "YouTube",
    canPlay: canPlay.youtube,
    player: lazy(
      () => import(
        /* webpackChunkName: 'reactPlayerYouTube' */
        "youtube-video-element/react"
      )
    )
  },
  {
    key: "vimeo",
    name: "Vimeo",
    canPlay: canPlay.vimeo,
    player: lazy(
      () => import(
        /* webpackChunkName: 'reactPlayerVimeo' */
        "vimeo-video-element/react"
      )
    )
  },
  {
    key: "wistia",
    name: "Wistia",
    canPlay: canPlay.wistia,
    canEnablePIP: () => true,
    player: lazy(
      () => import(
        /* webpackChunkName: 'reactPlayerWistia' */
        "wistia-video-element/react"
      )
    )
  },
  {
    key: "spotify",
    name: "Spotify",
    canPlay: canPlay.spotify,
    canEnablePIP: () => false,
    player: lazy(
      () => import(
        /* webpackChunkName: 'reactPlayerSpotify' */
        "spotify-audio-element/react"
      )
    )
  },
  {
    key: "twitch",
    name: "Twitch",
    canPlay: canPlay.twitch,
    canEnablePIP: () => false,
    player: lazy(
      () => import(
        /* webpackChunkName: 'reactPlayerTwitch' */
        "twitch-video-element/react"
      )
    )
  },
  {
    key: "tiktok",
    name: "TikTok",
    canPlay: canPlay.tiktok,
    canEnablePIP: () => false,
    player: lazy(
      () => import(
        /* webpackChunkName: 'reactPlayerTiktok' */
        "tiktok-video-element/react"
      )
    )
  },
  {
    key: "html",
    name: "html",
    canPlay: canPlay.html,
    canEnablePIP: () => true,
    player: HtmlPlayer
  }
];
var players_default = Players;
export {
  players_default as default
};
