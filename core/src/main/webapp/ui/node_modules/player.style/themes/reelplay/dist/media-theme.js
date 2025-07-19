/*
<media-theme-reelplay>
  <video
    slot="media"
    src="https://stream.mux.com/fXNzVtmtWuyz00xnSrJg4OJH6PyNo6D02UzmgeKGkP5YQ/high.mp4"
  ></video>
</media-theme-reelplay>
*/

import 'media-chrome';
import { globalThis } from 'media-chrome/dist/utils/server-safe-globals.js';
import { MediaThemeElement } from 'media-chrome/dist/media-theme-element.js';
import 'media-chrome/dist/menu/index.js';

const template = globalThis.document?.createElement?.('template');
if (template) {
  template.innerHTML = /*html*/`
    <style>
      :host {
        --_primary-color: var(--media-primary-color, #fff);
        --_secondary-color: var(--media-secondary-color, rgb(38 38 38 / 0.75));
        --_accent-color: var(--media-accent-color, #fff);
        --media-icon-color: var(--_primary-color);
        --media-control-background: #ccc;
        --media-control-hover-background: transparent;
        --media-control-height: 1.2em;
        --media-tooltip-display: none;
        image-rendering: pixelated;
        font-size: 16px;
        color: var(--_primary-color);
        container: media-theme-instaplay / inline-size;
        --_play-active-image: url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAANCAYAAACZ3F9/AAAACXBIWXMAAAsTAAALEwEAmpwYAAAAr0lEQVQokZXSMQ7CMAyF4d+Ik/UYYYTrdOvK2lvkGl09JluyJZsZSipEqxIsZfzecyTj7s4AMzP+eRcA7z0iYiJidM4VYBgGvPcANGxm8hM23Np7AjYYY6SU0h2wwRACKSVUlRgjzrnTgA2mlKi1knOmlMI4jqgq0zQdBuxWzTlTa103qInb4wbA/Jw5bAwhAKCq6wY1HYLdHz9bzsAOLrp0gTbyPrlusEGg61K+5wUQC7Be2eoHzQAAAABJRU5ErkJggg==');
        --_play-inactive-image: url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAANCAYAAACZ3F9/AAAACXBIWXMAAAsTAAALEwEAmpwYAAAAjUlEQVQokZWSMQ7FIAxDTcWSM2d1zuwxfykobelXQUJCIc+xEY1kSgLJho11jENEZETkNggAkj4LNHdPM4MkmNmjwd2XERrJqS7pclmF7gJHBcxs7lpfRejDYp1W4ermrCUA9NFQG6vYEHH3GQ8A+t1qfaQVMK1WexV6A5YZ/014gAMi+Qm4WD2hrb/6A91NW9lXLWdRAAAAAElFTkSuQmCC');
        --_play-green-image: url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA0AAAANCAYAAABy6+R8AAAACXBIWXMAAAsTAAALEwEAmpwYAAAASUlEQVQokZ3SyQ0AIAwDwTWi/5ZNAeSCPCPNx7Yw5vE2gLGmQMgrenbwQhMYog6mqIIlymCLomRLlFWRoqq7EHVlX2iyDv1s7wAoZRYXYSzPZAAAAABJRU5ErkJggg==');
        --_pause-active-image: url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAANCAYAAACZ3F9/AAAACXBIWXMAAAsTAAALEwEAmpwYAAABs0lEQVQokVWSO07sQBBFT7XLY2yJQWQQsS0CJEhICBAbYRVsigxIRtZ8JGTGY7mn2/cFaKzHTUunbn2u3d3d6fr6mo+PD66urthut7g7b29vBnB7eysz4/LykrZtubm54f39HZ6fnzWOo8Zx1DAMenx8lJlJEpJ4enpS3/fq+16SdH9/r8ViodB1He7OMAx0XYeZIYmT9vs9TdMwTROfn5+4OzFGPOeMJNq25XA4kHPmf8UYGceRr68vYoxM04SZ4WZGURR8f3+TUkISZjaDTdMAMI4jx+ORGCMhBDyEAEDXdYQQiDH+cUwpUVUVm82GoihwdyThADlndrsdwzCwWCwoy/IPeFolpYSZEUIgxBgpioLVasU0TcQY/7iebnA4HIgxIomUEt40DZI4Ho9st1vMjLquZ7CqKkII7HY76rom5/w78qlL27aUZTm/5qSfnx9yzmw2G9ydsizJOeMpJYB5FIDlcjmDy+WS/X5PzpnVajXXwsXFBS8vL6zXa0IInJ2d0XXdDK7Xa15fX+n7nqZpyDlzfn7+GzlAdV0LUFVVqqpqjtzDw4MAmZnKspS7y931D4B+IeRR9QqaAAAAAElFTkSuQmCC');
        --_pause-inactive-image: url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAANCAYAAACZ3F9/AAAACXBIWXMAAAsTAAALEwEAmpwYAAAAgElEQVQokY2Quw0FIQwE59AlLsk1UBK0RkpLhH4REj8/bhNLHvB6/aSUTERordFrjBFVfQByzkce1qaIoKp0eTwwqMMvmj72iafeWAGCB1aNbybHFazaHG+5TsfZMvbJt9wA72nap1Vvju6qHviXd3O8HWoaXEqxWqsdhJnhMPsBzFyriskMNtIAAAAASUVORK5CYII=');
        --_stop-active-image: url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAANCAYAAACZ3F9/AAAACXBIWXMAAAsTAAALEwEAmpwYAAABs0lEQVQokU2Qu00sQRRET093jzRoLAwsSIsMCIAMCQFSwGNHC832//+MFatX3lXp3CqVeH5+no+Pj5xOJ6y1bNuG1pp93zHGoLWm1srDwwPv7+98fHzw8/MjeHl5ma212Xuf/yvnPHPOM8Y455zzdDrN19fXCcw5J6q1Ru+dr68v9n0nxkjvnfP5DID3nuM4uFwuXC4XpJQAKIB1XRljUGvFWsucE+89YwyMMaSUkFJSSqH3fgVjjAC01nDOMcYg50wIgdYaMUasteSc2bYNpdQVvLu7Y4xBKYVlWQghYIyhlIK1lpQS1lrGGLcAAKWUQghB750QAs45YoyklEgpAdwa/N0ASghBa40QAiEErLW3gb6/v1mWhZQStVbmnKzregX/vvyZ3nuMMQCUUmit4b2n1kprjVLKFZRSorXGe49zDu89pRS01hzHwbqu/P7+0ntn27Zb1QXAGEPOGeccpRTGGKSU6L2zrisxRs7nM0IIhBDXxForb29vHMeBc46U0m2kz89P7u/vAdj3Ha01T09PAAgp5ey9I6VkzolSCq01vXd679RaEUKgtUYpRQiBOaf4BxvCbUGBdlc6AAAAAElFTkSuQmCC');
        --_stop-inactive-image: url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAANCAYAAACZ3F9/AAAACXBIWXMAAAsTAAALEwEAmpwYAAAAbUlEQVQokZ1RMRLAIAiLbDyJN/At3+aXHO0kZy0F2ywMSSSGUmsdvXcwM06mqkJECn0xMTNEBABAHrli5yfIE614e5QikZfANe7wEpgxKiOCbcyiza+Y8aSQOW/lZHdLN0bi9BxRKQ+utTZ+ABcG/775EH3i7gAAAABJRU5ErkJggg==');
        --_speaker-active-image: url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAsAAAAQCAYAAADAvYV+AAAACXBIWXMAAAsTAAALEwEAmpwYAAABK0lEQVQokX2RoXPCMBSHv+4mmGtcIpHUVQ6JndtkJfwHTE4iG9c6IiuHY47K4laHnQyulXNvAihcgb27XO7lvve7L0kgItwro5QwGOC9DwAe7pK1k4/FAqUU1lq5D9dO9uaFPM/Jsqw7Dq40jmBRFADEccxkMsFa20s+gtqvOzCKIqgtZ43aSR90znUZe5MQhiEPdhZ1HicQoGkakiRBa432a9q2PWvY3F7d82u1wihFO3wD4BFg+73lcgcwbUtZVShjCH/chfON+h0Occ5RliXE8wM8X+6CaltdwY33ZFmG1hrqi+T5chf0h5QxPB1eCuIpcONT7CyS8fMYgNf3T3zTQO2wZQsicrXS6Uiq5VS01rLZbKQoChGR23B/IE3T/2ERQWst8Wgkp/4PXbzQXE7ZmasAAAAASUVORK5CYII=');
        --_speaker-inactive-image: url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAsAAAAQCAYAAADAvYV+AAAACXBIWXMAAAsTAAALEwEAmpwYAAAApklEQVQokX2SOw7FIAwEx3lx4UvlYqbEZ6bkFQmSIZ9tLOFhWTDSe+dDoykA2xsVEb2UMm2SJ+eImBbdHUBuzgNsrTFqRAD0bQUHZGa3E3eAUko3M1prrDXrp6pdVR9BM0NVATiO43yNnC+DuQew58ZQBnOc6YLZJZuMutVaZX2BtxjTUNZh5E3uPsdwd8mXXXWbYK1VcqThCsjj38iRLvAbvjR90T95E3txy+GI1QAAAABJRU5ErkJggg==');
        --_stop-green-image: url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA0AAAANCAYAAABy6+R8AAAACXBIWXMAAAsTAAALEwEAmpwYAAAAPklEQVQokWNk+M/wn4FEwMLAwMDwn+E/I7EaGBkY/zORagvcJmRTcClEdg1ZNo1qggKUICc2ksmyiZGctAcAEpwMGtOI06QAAAAASUVORK5CYII=');
        --_range-thumb-image: url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA0AAAANCAYAAABy6+R8AAAACXBIWXMAAAsTAAALEwEAmpwYAAAAk0lEQVQokZWRPQ7FIAyDvzyxcC6O1ZkjsbJypIx5S4P4U6taQkhOiB0sZmZ8gIgQAFprqCoxRlSV1hrXdR15gB8wFfx+4gPQiZRSt5FzBsDMqLVOFsM48W6SZQ0b1bq9UfqEtbYpneC1405vSpM9V7ojs+WclZzwXNac/OH2EU85rTYDQCmlT/PGnPMWrkPc9xf8Ad5BiyG0KQ3gAAAAAElFTkSuQmCC');
        --_seek-image: url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAQAAAAHCAYAAAAvZezQAAAACXBIWXMAAAsTAAALEwEAmpwYAAAAT0lEQVQImUXIuwqAMBQE0dlLCsVHCBH//x9jI0bWQsTp5sg2X2UeHd8kyefdCYC6ZO9b4bo7sUyr29GICGyQbZJkgFzXFwAkeRjTDwAh+QFHpR7KTkYEPAAAAABJRU5ErkJggg==');
        --_handle-image: url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAcAAAAWCAYAAAAM2IbtAAAACXBIWXMAAAsTAAALEwEAmpwYAAAAgUlEQVQokZ2PMQ4EIQwDR2ibvJmat+VJLrliwypB0Jw7Kxo7ZoZ673POSfYNwN0xM5bcHYAGIAlJ31ESZvYeM7W8pCCpqmSYXS8ZMcdYkyCRJVZmkEgKuXUt33LMdWd5KOadyfjhSpbO6xTbppROdjJ3ciD/62Qn88417RljlGP2P1lNesKvK+nmAAAAAElFTkSuQmCC');
        --_embossed: 0 -1px 0 0 #7b7b7b, /* Top part of top border */ 0 -2px 0 0 #000,
          /* Bottom part of top border */ -1px 0 0 0 #7b7b7b, /* Left border color */ 1px 0 0 0 white,
          /* Right border color */ 0 1px 0 0 white;
        /* Bottom border color */
      }

      .spacer {
        flex: 1;
      }

      media-controller {
        display: block;
        overflow: hidden;
      }

      div[slot='top-chrome'],
      media-control-bar {
        width: 100%;
      }

      media-control-bar {
        border-top: 1px solid #fff;
        box-shadow: 0 -1px 0 0 #7b7b7b;
      }

      media-control-bar {
        padding-left: 20px;
        background: #ccc 5px center var(--_handle-image) no-repeat;
      }

      media-time-range {
        width: 100%;
        --media-range-thumb-transform: translateX(-6px);
      }

      media-time-range[mediacurrenttime^='0'],
      media-time-range[mediacurrenttime^='1.'],
      media-time-range[mediacurrenttime^='2.'] {
        --media-range-thumb-transform: translateX(6px);
      }

      media-volume-range {
        --media-range-thumb-transform: translateX(-6px);
      }

      media-volume-range[mediavolume='0'],
      media-volume-range[mediavolume^='0.0'] {
        --media-range-thumb-transform: translateX(6px);
      }

      media-volume-range[mediavolume^='0.1'] {
        --media-range-thumb-transform: translateX(5px);
      }

      media-volume-range[mediavolume^='0.2'] {
        --media-range-thumb-transform: translateX(3px);
      }

      media-volume-range[mediavolume^='0.3'] {
        --media-range-thumb-transform: translateX(2px);
      }

      media-volume-range[mediavolume^='0.4'] {
        --media-range-thumb-transform: translateX(1px);
      }

      media-volume-range[mediavolume^='0.5'] {
        --media-range-thumb-transform: translateX(0px);
      }

      media-volume-range[mediavolume^='0.6'] {
        --media-range-thumb-transform: translateX(1px);
      }

      media-volume-range[mediavolume^='0.7'] {
        --media-range-thumb-transform: translateX(-2px);
      }

      media-volume-range[mediavolume^='0.8'] {
        --media-range-thumb-transform: translateX(-3px);
      }

      media-volume-range[mediavolume^='0.9'] {
        --media-range-thumb-transform: translateX(-4px);
      }

      media-volume-range,
      media-time-range {
        --media-range-track-background: linear-gradient(
              to right,
              #000 1px,
              transparent 1px,
              transparent 7px
            )
            7px 50% / 7px 3px repeat-x,
          #c6c6c6;

        --media-range-track-height: 13px;
        --media-range-track-border-radius: 0;
        --media-range-track-box-shadow: var(--_embossed);

        --media-range-thumb-transition: transform 0.1s;
        --media-range-thumb-background: 0 0 var(--_range-thumb-image);
        --media-range-thumb-width: 13px;
        --media-range-thumb-height: 13px;
        --media-range-bar-color: linear-gradient(to right, #000 1px, transparent 1px, transparent 7px)
            7px 50% / 7px 3px repeat-x,
          #008484;
        --media-range-thumb-border-radius: 0;
      }

      media-time-range::part(preview-box) {
        /* Add more space so thumb doesn't hide preview. */
        --media-preview-box-margin: 0 0 20px;
        display: grid;
      }

      media-time-range::part(appearance) {
        color: red;
      }

      media-preview-thumbnail,
      media-preview-time-display {
        grid-area: 1 / 1;
      }

      media-preview-time-display {
        place-self: end center;
        position: relative;
        line-height: 2;
      }

      media-play-button.play div[slot='play'] {
        width: 14px;
        height: 13px;
        background: 0 0 var(--_play-active-image);
      }

      media-play-button.play div[slot='pause'] {
        width: 14px;
        height: 13px;
        background: 0 0 var(--_play-inactive-image);
        cursor: not-allowed;
      }

      media-play-button.pause div[slot='play'] {
        width: 14px;
        height: 13px;
        background: 0 0 var(--_pause-inactive-image);
        cursor: not-allowed;
      }

      media-play-button.pause div[slot='pause'] {
        width: 14px;
        height: 13px;
        background: 0 0 var(--_pause-active-image);
      }

      media-play-button.stop div[slot='play'] {
        width: 14px;
        height: 13px;
        background: 0 0 var(--_stop-inactive-image);
        cursor: not-allowed;
      }

      media-play-button.stop div[slot='pause'] {
        width: 14px;
        height: 13px;
        background: 0 0 var(--_stop-active-image);
      }

      media-play-button.status {
        background-color: #000;
        margin-right: 3px;
        --media-button-padding: 1px;
      }

      media-play-button.status div[slot='play'] {
        width: 13px;
        height: 13px;
        background: 0 0 var(--_stop-green-image) no-repeat;
      }

      media-play-button.status div[slot='pause'] {
        width: 13px;
        height: 13px;
        background: 0 0 var(--_play-green-image);
      }

      media-seek-backward-button,
      media-seek-forward-button {
        --media-button-padding: 0;
      }

      media-seek-backward-button span[slot='icon'] {
        width: 4px;
        height: 7px;
        background: 0 0 var(--_seek-image);
      }

      media-seek-forward-button span[slot='icon'] {
        width: 4px;
        height: 7px;
        background: 0 0 var(--_seek-image);
        transform: rotate(180deg);
      }

      media-mute-button span[slot='off'] {
        width: 11px;
        height: 16px;
        background: 0 0 var(--_speaker-inactive-image);
      }

      media-mute-button span[slot='low'],
      media-mute-button span[slot='medium'],
      media-mute-button span[slot='high'] {
        width: 11px;
        height: 16px;
        background: 0 0 var(--_speaker-active-image);
      }

      media-time-display {
        background: black;
        color: #00ff00;
        --media-font-family: monaco;
        --media-font-weight: 300;
        --media-font-size: 13px;
        --media-control-padding: 0;
      }

      .bottom-bar {
        border-top: 1px solid #fff; /* Top pixel color */
        box-shadow: 0 -1px 0 0 #7b7b7b; /* Bottom pixel color */
        background: #ccc 5px center var(--_handle-image) no-repeat;
        width: 100%;
        padding: 4px 20px;
        display: flex;
        align-items: center;
        box-sizing: border-box;
        gap: 3px;
        font-family: monaco;
      }

      .bottom-bar > div {
        background: black;
        padding: 5px;
        align-self: stretch;
        box-shadow: var(--_embossed);
        color: #00ff00;
        font-size: 13px;
        display: flex;
        align-items: center;
      }

      .bottom-bar a {
        color: #00ff00;
      }

      .top-bar {
        font-family: monaco, sans-serif;
        height: 18px;
        width: 100%;
        background: linear-gradient(to right, #000084, #0884ce);
        color: #fff;
        line-height: 1;
        font-size: 11px;
        display: flex;
        align-items: center;
        padding-left: 10px;
        box-sizing: border-box;
      }
    </style>

    <media-controller
      defaultsubtitles="{{defaultsubtitles}}"
      defaultduration="{{defaultduration}}"
      gesturesdisabled="{{disabled}}"
      hotkeys="{{hotkeys}}"
      nohotkeys="{{nohotkeys}}"
      defaultstreamtype="on-demand"
    >
      <media-error-dialog slot="dialog"></media-error-dialog>

      <div slot="top-chrome">
        <div class="top-bar">
          <span>ReelPlay: Welcome!</span>
        </div>

        <media-control-bar>
          <media-play-button class="play" mediacontroller="controller">
            <div slot="play"></div>
            <div slot="pause"></div>
          </media-play-button>

          <media-play-button class="pause" mediacontroller="controller">
            <div slot="play"></div>
            <div slot="pause"></div>
          </media-play-button>

          <media-play-button class="stop" mediacontroller="controller">
            <div slot="play"></div>
            <div slot="pause"></div>
          </media-play-button>

          <media-seek-backward-button>
            <span slot="icon"></span>
          </media-seek-backward-button>

          <media-time-range noautohide>
            <media-preview-thumbnail slot="preview"></media-preview-thumbnail>
            <media-preview-time-display slot="preview"></media-preview-time-display>
          </media-time-range>

          <media-seek-forward-button>
            <span slot="icon"></span>
          </media-seek-forward-button>

          <div class="spacer"></div>
          <media-mute-button>
            <span slot="high"></span>
            <span slot="medium"></span>
            <span slot="low"></span>
            <span slot="off"></span>
          </media-mute-button>
          <media-volume-range></media-volume-range>
        </media-control-bar>
      </div>

      <div class="bottom-bar">
        <div>
          <media-play-button class="status" mediacontroller="controller">
            <div slot="play"></div>
            <div slot="pause"></div>
          </media-play-button>
          <span>32.1 Kbps</span>
        </div>

        <div>
          <media-time-display showduration></media-time-display>
        </div>

        <div>
          <span>Theme by @davekiss</span>
        </div>
        <div>
          <span>Powered by <a href="https://mux.com" title="Mux">Mux</a></span>
        </div>
      </div>
      <slot name="media" slot="media"></slot>
      <slot name="poster" slot="poster"></slot>

      <slot></slot>
    </media-controller>

  `;
}

class MediaThemeReelplayElement extends MediaThemeElement {
  static template = template;
}

if (globalThis.customElements && !globalThis.customElements.get('media-theme-reelplay')) {
  globalThis.customElements.define('media-theme-reelplay', MediaThemeReelplayElement);
}

export default MediaThemeReelplayElement;
