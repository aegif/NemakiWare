"use strict";var z=Object.create;var g=Object.defineProperty;var E=Object.getOwnPropertyDescriptor;var S=Object.getOwnPropertyNames;var T=Object.getPrototypeOf,U=Object.prototype.hasOwnProperty;var V=(t,i)=>{for(var r in i)g(t,r,{get:i[r],enumerable:!0})},v=(t,i,r,o)=>{if(i&&typeof i=="object"||typeof i=="function")for(let n of S(i))!U.call(t,n)&&n!==r&&g(t,n,{get:()=>i[n],enumerable:!(o=E(i,n))||o.enumerable});return t};var u=(t,i,r)=>(r=t!=null?z(T(t)):{},v(i||!t||!t.__esModule?g(r,"default",{value:t,enumerable:!0}):r,t)),C=t=>v(g({},"__esModule",{value:!0}),t);var K={};V(K,{default:()=>D});module.exports=C(K);var l=u(require("react")),w=u(require("@mux/mux-player-react/ads")),k=u(require("@mux/mux-player-react/themes/news"));var e=u(require("react"));var b=`/* Main Playlist Container */
.playlist {
  /* Ensure it wraps on smaller screens */
  display: inline;
  position: relative;
  background-color: #12121263;
  z-index: 2;
  top: 0;
  position: absolute;
  width: 100%;
  height: 100%;
}

@media (min-width: 1336px) {
  .playlist {
    align-items: center;
    justify-content: center;
  }
}

.overlay {
  position: absolute;
  width: 100%;
  height: 100%;
  background: black;
  opacity: 0.5;
  z-index: 1;
}

.post-video-section {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  padding: 1.5rem 2rem;
  position: relative;
  gap: 1rem;
  padding: 1.5rem 2rem;
  height: max-content;
  box-sizing: border-box;
  z-index: 2;
  max-width: 1200px;
}

.post-video-section hr {
  border: none;
  background: rgba(255, 255, 255, 0.5);
  height: 100%;
  width: 1px;
}

/* Video Section */
.video-section {
  flex: 2;
}

.video-container {
  position: relative;
}

.title {
  font-size: 2.5rem;
  font-weight: 500;
  line-height: 3rem;
  margin: 0;
  margin-bottom: 1rem;
}

.video-wrapper {
  position: relative;
  width: 100%;
  overflow: hidden;
}

.video-container > .video-title {
  font-size: 1.3rem;
  font-weight: 600;
}

.video-thumbnail {
  width: 100%;
  display: block;
}

.video-title {
  font-size: 1rem;
  margin-top: 0.5rem;
  cursor: pointer;
  color: #ffffff;
  text-decoration: none;
  line-height: 1.4;
  /* Adjusted for better readability */
  word-wrap: break-word;
  font-weight: 500;
  margin-bottom: 0;
}

.video-title:hover {
  text-decoration: underline;
}

/* Countdown Timer */
.countdown-overlay {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 3.75rem;
  height: 3.75rem;
  display: flex;
  justify-content: center;
  align-items: center;
}

.countdown-ring {
  position: absolute;
}

.circle-background {
  fill: none;
  stroke: rgba(255, 255, 255, 0.2);
  stroke-width: 0.25rem;
}

.circle-progress {
  fill: none;
  stroke: #00a3dd;
  stroke-width: 0.25rem;
  stroke-linecap: round;
  transition: stroke-dashoffset 1s linear;
}

.count-text {
  position: absolute;
  font-size: 1rem;
  font-weight: bold;
  color: #ffffff;
}

/* Related Videos */
.related-videos-section {
  flex: 1;
  width: 100%;
}

.related-title {
  font-size: 1.125rem;
  font-weight: bold;
  margin: 0;
  line-height: 2rem;
}

.related-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.related-item {
  display: flex;
  align-items: start;
  border-bottom: 1px solid rgba(255, 255, 255, 0.5);
  width: 100%;
  gap: 0.5rem;
  padding: 0.5rem 0;
  border-radius: 0;
  background: none;
}

.related-item:hover {
  background: none;
}
.related-thumbnail {
  width: 7rem;
  object-fit: cover;
  aspect-ratio: 16 / 9;
}

.related-text {
  font-size: 0.9rem;
  color: white;
  line-height: 1.4;
  word-wrap: break-word;
  max-width: 100%;
  margin-top: 0.25rem;
  display: block;
}

.related-text:hover {
  text-decoration: underline;
}

/* Responsive  */

@media (max-width: 768px) {
  .post-video-section {
    grid-template-columns: 1fr;
    margin: auto;
  }

  .post-video-section h2 {
    display: none;
  }

  hr {
    display: none;
  }

  .video-section {
    width: 60%;
    margin: auto;
  }

  .related-videos-section {
    display: none;
  }
}
`;var j=({currentIndex:t=0,relatedVideos:i,visible:r,selectVideoCallback:o})=>{let[n,p]=(0,e.useState)(3),s=i[t];return(0,e.useEffect)(()=>{if(!r){p(3);return}if(n<0){let a=(t+1)%i.length;o(a);return}let c=setInterval(()=>{p(a=>Math.max(a-1,-1))},1e3);return()=>clearInterval(c)},[n,r]),e.default.createElement(e.default.Fragment,null,e.default.createElement("style",null,b),e.default.createElement("div",{className:"playlist",style:{display:r?"grid":"none"}},e.default.createElement("div",{className:"overlay",style:{display:r?"grid":"none"}}),e.default.createElement("div",{className:"post-video-section",style:{display:r?"grid":"none",zIndex:99}},e.default.createElement("div",{className:"video-section"},e.default.createElement("div",{className:"video-container"},e.default.createElement("h2",{className:"title"},"Video"),e.default.createElement("div",{className:"video-wrapper"},e.default.createElement("img",{className:"video-thumbnail",src:s.imageUrl,alt:s.title}),e.default.createElement("div",{className:"countdown-overlay"},e.default.createElement("svg",{className:"countdown-ring",width:"50",height:"50"},e.default.createElement("circle",{cx:"25",cy:"25",r:"22",className:"circle-background"}),e.default.createElement("circle",{cx:"25",cy:"25",r:"22",className:"circle-progress",style:{strokeDasharray:"138",strokeDashoffset:`${n/3*138}`}})),e.default.createElement("span",{className:"count-text"},n))),e.default.createElement("p",{className:"video-title"},s.title))),e.default.createElement("hr",null),e.default.createElement("div",{className:"related-videos-section"},e.default.createElement("h3",{className:"related-title"},"Related Videos"),e.default.createElement("ul",{className:"related-list"},i.map((c,a)=>e.default.createElement("li",{key:a},e.default.createElement("button",{className:"related-item",onClick:()=>o(a)},e.default.createElement("img",{className:"related-thumbnail",src:c.imageUrl,alt:c.title}),e.default.createElement("p",{className:"related-text"},c.title)))))))))},x=j;var A=({videoList:t,...i})=>{var y,h;let r=(0,l.useRef)(null),[o,n]=(0,l.useState)(0),[p,s]=(0,l.useState)(typeof((y=t[o])==null?void 0:y.adTagUrl)=="string"?(h=t[o])==null?void 0:h.adTagUrl:void 0);(0,l.useEffect)(()=>{n(0)},[t]),(0,l.useEffect)(()=>{var f;let d=(f=t[o])==null?void 0:f.adTagUrl;if(typeof d=="string")s(d);else if(typeof d=="function"){let m=d();typeof m=="string"?s(m):typeof(m==null?void 0:m.then)=="function"&&(s(void 0),m.then(s))}},[o]);let[c,a]=(0,l.useState)(!1),[P,N]=(0,l.useState)(0);function I(d){a(!1),s(void 0),n(d),setTimeout(()=>{try{r.current.play()}catch{}},200)}return l.default.createElement(w.default,{theme:k.default,style:{aspectRatio:"16/9"},preferPlayback:"mse",maxResolution:"2160p",minResolution:"540p",renditionOrder:"desc",metadata:{video_title:t[o].title},...i,ref:r,key:`player-${P}`,playbackId:p?t[o].playbackId:void 0,adTagUrl:p,onEnded:d=>{var f;o<t.length-1?a(!0):(n(0),N(m=>m+1)),(f=i.onEnded)==null||f.call(i,d)}},l.default.createElement(x,{currentIndex:o,relatedVideos:t,visible:c,selectVideoCallback:I}))},D=A;
//# sourceMappingURL=index.cjs.js.map
