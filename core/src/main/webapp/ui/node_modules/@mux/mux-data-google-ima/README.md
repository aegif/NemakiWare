<p align="center">
  <a href="https://mux.com/">
    <img src="https://avatars.githubusercontent.com/u/16199997?s=200&v=4" alt="Mux Logo">
  </a>
</p>

# Google IMA Extension to Mux Embed Data SDK for Monitoring Client-Side Ads

![npm (tag)](https://img.shields.io/npm/v/@mux/mux-data-google-ima/latest)
![npm bundle size](https://img.shields.io/bundlephobia/min/@mux/mux-data-google-ima)
![Snyk Vulnerabilities for npm package](https://img.shields.io/snyk/vulnerabilities/npm/@mux/mux-data-google-ima)
![npm](https://img.shields.io/npm/dm/@mux/mux-data-google-ima)
![npm](https://img.shields.io/npm/dt/@mux/mux-data-google-ima)

Mux Data gives you insight into video engagement and Quality of Experience using client-side SDKs for your player.

This Google IMA SDK extension adds monitoring of client-side ads via the [Google IMA HTML5 SDK](https://developers.google.com/interactive-media-ads/docs/sdks/html5/client-side) along with your main content played using
HTML5 Video, HLS.js, or dash.js. See all [supported players here](https://docs.mux.com/guides/data).

# Documentation

## Quick Start

If you're not already familiar with monitoring your media using `mux-embed`, it's recommended you read those docs first:

- [Monitor HTML5 Video Element](https://docs.mux.com/guides/data/monitor-html5-video-element)
- [Monitor HLS.js](https://docs.mux.com/guides/data/monitor-hls-js)
- [Monitor Dash.js](https://docs.mux.com/guides/data/monitor-dash-js)

If you've already used `mux-embed` before, working with the `google-ima` extension will feel fairly familiar.

### Install the google-ima extended `mux-embed`

The `google-ima` package _extends_ `mux-embed` to add Google IMA specific integrations and monitoring, so instead of
installing `mux-embed`, you can simply install `@mux/google-ima`.

**npm:**

```sh
npm install --save @mux/mux-data-google-ima
```

**yarn:**

```sh
yarn add @mux/mux-data-google-ima
```

**cdn:**

```html
<script src="https://src.litix.io/google-ima/0/google-ima-mux.js"></script>
```

### Example HTML5 implementation using HLS.js

```html
<script blocking type="text/javascript" src="//imasdk.googleapis.com/js/sdkloader/ima3.js"></script>
<script type="text/javascript" src="https://src.litix.io/google-ima/0/google-ima-mux.js"></script>
<script type="text/javascript" src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
<!-- Scrtipt for your Google IMA integration -->
<script type="text/javascript" src="./ads.js"></script>
<div id="videoplayer">
  <video id="content" width="960" height="400"></video>
  <div id="adcontainer"></div>
</div>
<script>
  const player_init_time = mux.utils.now();
  // ... code to setup ima integration
  // For examples, see: https://github.com/googleads/googleads-ima-html5
  const imaAdsLoader = codeToSetupIMAAndGetAdsLoader();

  if (Hls.isSupported()) {
    hls = new Hls({ debug: true });
    hls.loadSource('https://stream.mux.com/a4nOgmxGWg6gULfcBbAa00gXyfcwPnAFldF8RdsNyk8M.m3u8');
    hls.attachMedia(mediaEl);
  }

  // Use mux-embed just like you normally would
  mux.monitor('#content', {
    data: {
      video_title: 'My Video Title',
      player_init_time,
      env_key: 'YOUR_MUX_DATA_ENV_KEY',
    },
    hlsjs: hls,
    // Just pass in your IMA AdsLoader reference, just like Hls.js
    imaAdsLoader,
  });

  // ... code to request ads
  // NOTE: While not necessary, unless you're autoplaying, it's recommended
  // that you don't request ads until after monitoring to get more accurate
  // metrics and not miss events in your view
  requestAds();
</script>
```
