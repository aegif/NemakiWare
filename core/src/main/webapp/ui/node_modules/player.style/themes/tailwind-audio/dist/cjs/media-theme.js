var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

// dist/media-theme.js
var media_theme_exports = {};
__export(media_theme_exports, {
  default: () => media_theme_default
});
module.exports = __toCommonJS(media_theme_exports);
var import_media_chrome = require("media-chrome");
var import_server_safe_globals = require("media-chrome/dist/utils/server-safe-globals.js");
var import_media_theme_element = require("media-chrome/dist/media-theme-element.js");
var import_menu = require("media-chrome/dist/menu/index.js");
var template = import_server_safe_globals.globalThis.document?.createElement?.("template");
if (template) {
  template.innerHTML = /*html*/
  `
    <style>
      *,:after,:before{--tw-border-spacing-x:0;--tw-border-spacing-y:0;--tw-translate-x:0;--tw-translate-y:0;--tw-rotate:0;--tw-skew-x:0;--tw-skew-y:0;--tw-scale-x:1;--tw-scale-y:1;--tw-pan-x: ;--tw-pan-y: ;--tw-pinch-zoom: ;--tw-scroll-snap-strictness:proximity;--tw-gradient-from-position: ;--tw-gradient-via-position: ;--tw-gradient-to-position: ;--tw-ordinal: ;--tw-slashed-zero: ;--tw-numeric-figure: ;--tw-numeric-spacing: ;--tw-numeric-fraction: ;--tw-ring-inset: ;--tw-ring-offset-width:0px;--tw-ring-offset-color:#fff;--tw-ring-color:rgba(59,130,246,.5);--tw-ring-offset-shadow:0 0 #0000;--tw-ring-shadow:0 0 #0000;--tw-shadow:0 0 #0000;--tw-shadow-colored:0 0 #0000;--tw-blur: ;--tw-brightness: ;--tw-contrast: ;--tw-grayscale: ;--tw-hue-rotate: ;--tw-invert: ;--tw-saturate: ;--tw-sepia: ;--tw-drop-shadow: ;--tw-backdrop-blur: ;--tw-backdrop-brightness: ;--tw-backdrop-contrast: ;--tw-backdrop-grayscale: ;--tw-backdrop-hue-rotate: ;--tw-backdrop-invert: ;--tw-backdrop-opacity: ;--tw-backdrop-saturate: ;--tw-backdrop-sepia: ;--tw-contain-size: ;--tw-contain-layout: ;--tw-contain-paint: ;--tw-contain-style: }::backdrop{--tw-border-spacing-x:0;--tw-border-spacing-y:0;--tw-translate-x:0;--tw-translate-y:0;--tw-rotate:0;--tw-skew-x:0;--tw-skew-y:0;--tw-scale-x:1;--tw-scale-y:1;--tw-pan-x: ;--tw-pan-y: ;--tw-pinch-zoom: ;--tw-scroll-snap-strictness:proximity;--tw-gradient-from-position: ;--tw-gradient-via-position: ;--tw-gradient-to-position: ;--tw-ordinal: ;--tw-slashed-zero: ;--tw-numeric-figure: ;--tw-numeric-spacing: ;--tw-numeric-fraction: ;--tw-ring-inset: ;--tw-ring-offset-width:0px;--tw-ring-offset-color:#fff;--tw-ring-color:rgba(59,130,246,.5);--tw-ring-offset-shadow:0 0 #0000;--tw-ring-shadow:0 0 #0000;--tw-shadow:0 0 #0000;--tw-shadow-colored:0 0 #0000;--tw-blur: ;--tw-brightness: ;--tw-contrast: ;--tw-grayscale: ;--tw-hue-rotate: ;--tw-invert: ;--tw-saturate: ;--tw-sepia: ;--tw-drop-shadow: ;--tw-backdrop-blur: ;--tw-backdrop-brightness: ;--tw-backdrop-contrast: ;--tw-backdrop-grayscale: ;--tw-backdrop-hue-rotate: ;--tw-backdrop-invert: ;--tw-backdrop-opacity: ;--tw-backdrop-saturate: ;--tw-backdrop-sepia: ;--tw-contain-size: ;--tw-contain-layout: ;--tw-contain-paint: ;--tw-contain-style: }/*! tailwindcss v3.4.17 | MIT License | https://tailwindcss.com*/*,:after,:before{box-sizing:border-box;border:0 solid #e5e7eb}:after,:before{--tw-content:""}:host,html{line-height:1.5;-webkit-text-size-adjust:100%;-moz-tab-size:4;-o-tab-size:4;tab-size:4;font-family:ui-sans-serif,system-ui,sans-serif,Apple Color Emoji,Segoe UI Emoji,Segoe UI Symbol,Noto Color Emoji;font-feature-settings:normal;font-variation-settings:normal;-webkit-tap-highlight-color:transparent}body{margin:0;line-height:inherit}hr{height:0;color:inherit;border-top-width:1px}abbr:where([title]){-webkit-text-decoration:underline dotted;text-decoration:underline dotted}h1,h2,h3,h4,h5,h6{font-size:inherit;font-weight:inherit}a{color:inherit;text-decoration:inherit}b,strong{font-weight:bolder}code,kbd,pre,samp{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,Liberation Mono,Courier New,monospace;font-feature-settings:normal;font-variation-settings:normal;font-size:1em}small{font-size:80%}sub,sup{font-size:75%;line-height:0;position:relative;vertical-align:baseline}sub{bottom:-.25em}sup{top:-.5em}table{text-indent:0;border-color:inherit;border-collapse:collapse}button,input,optgroup,select,textarea{font-family:inherit;font-feature-settings:inherit;font-variation-settings:inherit;font-size:100%;font-weight:inherit;line-height:inherit;letter-spacing:inherit;color:inherit;margin:0;padding:0}button,select{text-transform:none}button,input:where([type=button]),input:where([type=reset]),input:where([type=submit]){-webkit-appearance:button;background-color:transparent;background-image:none}:-moz-focusring{outline:auto}:-moz-ui-invalid{box-shadow:none}progress{vertical-align:baseline}::-webkit-inner-spin-button,::-webkit-outer-spin-button{height:auto}[type=search]{-webkit-appearance:textfield;outline-offset:-2px}::-webkit-search-decoration{-webkit-appearance:none}::-webkit-file-upload-button{-webkit-appearance:button;font:inherit}summary{display:list-item}blockquote,dd,dl,figure,h1,h2,h3,h4,h5,h6,hr,p,pre{margin:0}fieldset{margin:0}fieldset,legend{padding:0}menu,ol,ul{list-style:none;margin:0;padding:0}dialog{padding:0}textarea{resize:vertical}input::-moz-placeholder,textarea::-moz-placeholder{opacity:1;color:#9ca3af}input::placeholder,textarea::placeholder{opacity:1;color:#9ca3af}[role=button],button{cursor:pointer}:disabled{cursor:default}audio,canvas,embed,iframe,img,object,svg,video{display:block;vertical-align:middle}img,video{max-width:100%;height:auto}[hidden]:where(:not([hidden=until-found])){display:none}.relative{position:relative}.left-px{left:1px}.order-first{order:-9999}.m-2{margin:.5rem}.mx-3{margin-left:.75rem;margin-right:.75rem}.mx-4{margin-left:1rem;margin-right:1rem}.block{display:block}.flex{display:flex}.hidden{display:none}.h-10{height:2.5rem}.h-2{height:.5rem}.h-20{height:5rem}.h-5{height:1.25rem}.h-7{height:1.75rem}.h-8{height:2rem}.h-full{height:100%}.min-h-0{min-height:0}.w-10{width:2.5rem}.w-5{width:1.25rem}.w-7{width:1.75rem}.w-8{width:2rem}.w-full{width:100%}.items-center{align-items:center}.justify-between{justify-content:space-between}.rounded-full{border-radius:9999px}.rounded-md{border-radius:.375rem}.border-l{border-left-width:1px}.border-slate-700/10{border-color:rgba(51,65,85,.1)}.bg-secondary{background-color:var(--media-secondary-color,#fff)}.bg-slate-50{--tw-bg-opacity:1;background-color:rgb(248 250 252/var(--tw-bg-opacity,1))}.bg-slate-700{--tw-bg-opacity:1;background-color:rgb(51 65 85/var(--tw-bg-opacity,1))}.fill-none{fill:none}.fill-slate-500{fill:#64748b}.stroke-slate-500{stroke:#64748b}.p-0{padding:0}.p-2{padding:.5rem}.px-4{padding-left:1rem;padding-right:1rem}.text-sm{font-size:.875rem;line-height:1.25rem}.text-xs{font-size:.75rem;line-height:1rem}.text-slate-500{--tw-text-opacity:1;color:rgb(100 116 139/var(--tw-text-opacity,1))}.text-slate-600{--tw-text-opacity:1;color:rgb(71 85 105/var(--tw-text-opacity,1))}.shadow-xl{--tw-shadow:0 20px 25px -5px rgba(0,0,0,.1),0 8px 10px -6px rgba(0,0,0,.1);--tw-shadow-colored:0 20px 25px -5px var(--tw-shadow-color),0 8px 10px -6px var(--tw-shadow-color);box-shadow:var(--tw-ring-offset-shadow,0 0 #0000),var(--tw-ring-shadow,0 0 #0000),var(--tw-shadow)}.shadow-black/5{--tw-shadow-color:rgba(0,0,0,.05);--tw-shadow:var(--tw-shadow-colored)}.@container{container-type:inline-size}.hover:bg-slate-900:hover{--tw-bg-opacity:1;background-color:rgb(15 23 42/var(--tw-bg-opacity,1))}.focus:outline-none:focus{outline:2px solid transparent;outline-offset:2px}.focus:ring-2:focus{--tw-ring-offset-shadow:var(--tw-ring-inset) 0 0 0 var(--tw-ring-offset-width) var(--tw-ring-offset-color);--tw-ring-shadow:var(--tw-ring-inset) 0 0 0 calc(2px + var(--tw-ring-offset-width)) var(--tw-ring-color);box-shadow:var(--tw-ring-offset-shadow),var(--tw-ring-shadow),var(--tw-shadow,0 0 #0000)}.focus:ring-slate-700:focus{--tw-ring-opacity:1;--tw-ring-color:rgb(51 65 85/var(--tw-ring-opacity,1))}.focus:ring-offset-2:focus{--tw-ring-offset-width:2px}.focus-visible:ring-2:focus-visible{--tw-ring-offset-shadow:var(--tw-ring-inset) 0 0 0 var(--tw-ring-offset-width) var(--tw-ring-offset-color);--tw-ring-shadow:var(--tw-ring-inset) 0 0 0 calc(2px + var(--tw-ring-offset-width)) var(--tw-ring-color);box-shadow:var(--tw-ring-offset-shadow),var(--tw-ring-shadow),var(--tw-shadow,0 0 #0000)}.focus-visible:ring-slate-700:focus-visible{--tw-ring-opacity:1;--tw-ring-color:rgb(51 65 85/var(--tw-ring-opacity,1))}.group:hover .group-hover:fill-slate-700{fill:#334155}.group:hover .group-hover:stroke-slate-700{stroke:#334155}@container (min-width: 28rem){.@md:order-none{order:0}.@md:block{display:block}.@md:hidden{display:none}.@md:h-16{height:4rem}.@md:rounded-md{border-radius:.375rem}.@md:ring-1{--tw-ring-offset-shadow:var(--tw-ring-inset) 0 0 0 var(--tw-ring-offset-width) var(--tw-ring-offset-color);--tw-ring-shadow:var(--tw-ring-inset) 0 0 0 calc(1px + var(--tw-ring-offset-width)) var(--tw-ring-color);box-shadow:var(--tw-ring-offset-shadow),var(--tw-ring-shadow),var(--tw-shadow,0 0 #0000)}.@md:ring-slate-700/10{--tw-ring-color:rgba(51,65,85,.1)}}</style>

    <svg class="hidden">
      <symbol
        id="backward"
        viewBox="0 0 24 24"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path
          d="M8 5L5 8M5 8L8 11M5 8H13.5C16.5376 8 19 10.4624 19 13.5C19 15.4826 18.148 17.2202 17 18.188"
        ></path>
        <path d="M5 15V19"></path>
        <path
          d="M8 18V16C8 15.4477 8.44772 15 9 15H10C10.5523 15 11 15.4477 11 16V18C11 18.5523
            10.5523 19 10 19H9C8.44772 19 8 18.5523 8 18Z"
        ></path>
      </symbol>

      <symbol id="play" viewBox="0 0 24 24">
        <path
          fill-rule="evenodd"
          d="M4.5 5.653c0-1.426 1.529-2.33 2.779-1.643l11.54 6.348c1.295.712 1.295 2.573 0
            3.285L7.28 19.991c-1.25.687-2.779-.217-2.779-1.643V5.653z"
          clip-rule="evenodd"
        />
      </symbol>

      <symbol id="pause" viewBox="0 0 24 24">
        <path
          fill-rule="evenodd"
          d="M6.75 5.25a.75.75 0 01.75-.75H9a.75.75 0 01.75.75v13.5a.75.75 0
          01-.75.75H7.5a.75.75 0 01-.75-.75V5.25zm7.5 0A.75.75 0 0115 4.5h1.5a.75.75 0 01.75.75v13.5a.75.75 0
          01-.75.75H15a.75.75 0 01-.75-.75V5.25z"
          clip-rule="evenodd"
        />
      </symbol>

      <symbol id="forward" viewBox="0 0 24 24">
        <path
          d="M16 5L19 8M19 8L16 11M19 8H10.5C7.46243 8 5 10.4624 5 13.5C5 15.4826 5.85204 17.2202 7 18.188"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        ></path>
        <path d="M13 15V19" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"></path>
        <path
          d="M16 18V16C16 15.4477 16.4477 15 17 15H18C18.5523 15 19 15.4477 19 16V18C19 18.5523 18.5523 19 18
          19H17C16.4477 19 16 18.5523 16 18Z"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        ></path>
      </symbol>

      <symbol id="high" viewBox="0 0 24 24">
        <path
          d="M13.5 4.06c0-1.336-1.616-2.005-2.56-1.06l-4.5 4.5H4.508c-1.141 0-2.318.664-2.66 1.905A9.76 9.76 0
          001.5 12c0 .898.121 1.768.35 2.595.341 1.24 1.518 1.905 2.659 1.905h1.93l4.5 4.5c.945.945 2.561.276
          2.561-1.06V4.06zM18.584 5.106a.75.75 0 011.06 0c3.808 3.807 3.808 9.98 0 13.788a.75.75 0 11-1.06-1.06
          8.25 8.25 0 000-11.668.75.75 0 010-1.06z"
        ></path>
        <path
          d="M15.932 7.757a.75.75 0 011.061 0 6 6 0 010 8.486.75.75 0 01-1.06-1.061 4.5 4.5 0 000-6.364.75.75 0
          010-1.06z"
        ></path>
      </symbol>

      <symbol id="off" viewBox="0 0 24 24">
        <path
          d="M13.5 4.06c0-1.336-1.616-2.005-2.56-1.06l-4.5 4.5H4.508c-1.141 0-2.318.664-2.66 1.905A9.76 9.76 0
          001.5 12c0 .898.121 1.768.35 2.595.341 1.24 1.518 1.905 2.659 1.905h1.93l4.5 4.5c.945.945 2.561.276
          2.561-1.06V4.06zM17.78 9.22a.75.75 0 10-1.06 1.06L18.44 12l-1.72 1.72a.75.75 0 001.06 1.06l1.72-1.72 1.72
          1.72a.75.75 0 101.06-1.06L20.56 12l1.72-1.72a.75.75 0 00-1.06-1.06l-1.72 1.72-1.72-1.72z"
        />
      </symbol>
    </svg>

    <media-controller
      audio
      defaultsubtitles="{{defaultsubtitles}}"
      defaultduration="{{defaultduration}}"
      gesturesdisabled="{{disabled}}"
      hotkeys="{{hotkeys}}"
      nohotkeys="{{nohotkeys}}"
      defaultstreamtype="on-demand"
      class="@container block w-full shadow-xl shadow-black/5"
      style="
        --media-background-color: transparent;
        --media-control-background: transparent;
        --media-control-hover-background: transparent;
        --media-tooltip-display: none;
      "
    >
      <slot name="media" slot="media"></slot>

      <media-time-range
        class="block @md:hidden w-full h-2 min-h-0 p-0 bg-slate-50 focus-visible:ring-slate-700 focus-visible:ring-2"
        style="
          --media-range-track-background: transparent;
          --media-time-range-buffered-color: rgb(0 0 0 / 0.02);
          --media-range-bar-color: var(--media-accent-color, rgb(79 70 229));
          --media-range-track-height: 0.5rem;
          --media-range-thumb-background: var(--media-accent-color, rgb(79 70 229));
          --media-range-thumb-box-shadow: 0 0 0 2px var(--media-secondary-color, rgb(255 255 255 / 0.9));
          --media-range-thumb-width: 0.25rem;
          --media-range-thumb-height: 1rem;
          --media-preview-time-text-shadow: transparent;
        "
      >
        <media-preview-time-display
          slot="preview"
          class="text-slate-600 text-xs"
        ></media-preview-time-display>
      </media-time-range>

      <media-control-bar
        class="h-20 @md:h-16 px-4 w-full flex items-center justify-between @md:rounded-md @md:ring-1 @md:ring-slate-700/10 bg-secondary"
      >
        <media-seek-backward-button
          seekoffset="10"
          class="w-8 h-8 p-0 group rounded-full focus:outline-none focus-visible:ring-slate-700 focus-visible:ring-2"
        >
          <svg
            slot="icon"
            aria-hidden="true"
            class="w-7 h-7 fill-none stroke-slate-500 group-hover:stroke-slate-700"
          >
            <use href="#backward" />
          </svg>
        </media-seek-backward-button>

        <media-play-button
          class="h-10 w-10 p-2 mx-3 rounded-full bg-slate-700 hover:bg-slate-900 focus:outline-none focus:ring-slate-700 focus:ring-2 focus:ring-offset-2"
          style="--media-primary-color: #fff"
        >
          <svg slot="play" aria-hidden="true" class="relative left-px">
            <use href="#play" />
          </svg>
          <svg slot="pause" aria-hidden="true">
            <use href="#pause" />
          </svg>
        </media-play-button>

        <media-seek-forward-button
          seekoffset="10"
          class="w-8 h-8 p-0 group relative rounded-full focus:outline-none focus-visible:ring-slate-700 focus-visible:ring-2"
        >
          <svg
            slot="icon"
            aria-hidden="true"
            class="w-7 h-7 fill-none stroke-slate-500 group-hover:stroke-slate-700"
          >
            <use href="#forward" />
          </svg>
        </media-seek-forward-button>

        <div class="hidden @md:block h-full border-l border-slate-700/10 mx-4"></div>

        <media-time-display
          class="hidden @md:block text-slate-500 text-sm rounded-md focus:outline-none focus:ring-slate-700 focus:ring-2"
        ></media-time-display>

        <media-time-range
          class="hidden @md:block h-2 min-h-0 p-0 m-2 rounded-md bg-slate-50 focus-visible:ring-slate-700 focus-visible:ring-2"
          style="
            --media-range-track-background: transparent;
            --media-time-buffered-color: rgb(0 0 0 / 0.02);
            --media-range-bar-color: var(--media-accent-color, rgb(79 70 229));
            --media-range-track-border-radius: 4px;
            --media-range-track-height: 0.5rem;
            --media-range-thumb-background: var(--media-accent-color, rgb(79 70 229));
            --media-range-thumb-box-shadow: 0 0 0 2px var(--media-secondary-color, rgb(255 255 255 / 0.9));
            --media-range-thumb-width: 0.25rem;
            --media-range-thumb-height: 1rem;
            --media-preview-time-text-shadow: transparent;
          "
        >
          <media-preview-time-display
            slot="preview"
            class="text-slate-600 text-xs"
          ></media-preview-time-display>
        </media-time-range>

        <media-duration-display
          class="hidden @md:block text-slate-500 text-sm"
        ></media-duration-display>

        <media-playback-rate-button
          class="text-slate-500 rounded-md focus:outline-none focus-visible:ring-slate-700 focus-visible:ring-2"
        ></media-playback-rate-button>

        <media-mute-button
          class="group relative order-first @md:order-none rounded-md focus:outline-none focus-visible:ring-slate-700 focus-visible:ring-2"
        >
          <svg
            slot="high"
            aria-hidden="true"
            class="h-5 w-5 fill-slate-500 stroke-slate-500 group-hover:fill-slate-700 group-hover:stroke-slate-700"
          >
            <use href="#high" />
          </svg>
          <svg
            slot="medium"
            aria-hidden="true"
            class="h-5 w-5 fill-slate-500 stroke-slate-500 group-hover:fill-slate-700 group-hover:stroke-slate-700"
          >
            <use href="#high" />
          </svg>
          <svg
            slot="low"
            aria-hidden="true"
            class="h-5 w-5 fill-slate-500 stroke-slate-500 group-hover:fill-slate-700 group-hover:stroke-slate-700"
          >
            <use href="#high" />
          </svg>
          <svg
            slot="off"
            aria-hidden="true"
            class="h-5 w-5 fill-slate-500 stroke-slate-500 group-hover:fill-slate-700 group-hover:stroke-slate-700"
          >
            <use href="#off" />
          </svg>
        </media-mute-button>
      </media-control-bar>
    </media-controller>

  `;
}
var MediaThemeTailwindAudioElement = class extends import_media_theme_element.MediaThemeElement {
  static template = template;
};
if (import_server_safe_globals.globalThis.customElements && !import_server_safe_globals.globalThis.customElements.get("media-theme-tailwind-audio")) {
  import_server_safe_globals.globalThis.customElements.define("media-theme-tailwind-audio", MediaThemeTailwindAudioElement);
}
var media_theme_default = MediaThemeTailwindAudioElement;
