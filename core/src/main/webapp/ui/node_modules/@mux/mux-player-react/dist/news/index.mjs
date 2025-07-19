import v,{useEffect as b,useRef as E,useState as f}from"react";import S from"@mux/mux-player-react/ads";import T from"@mux/mux-player-react/themes/news";import e,{useEffect as N,useState as I}from"react";var y=`/* Main Playlist Container */
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
`;var z=({currentIndex:t=0,relatedVideos:n,visible:l,selectVideoCallback:i})=>{let[s,c]=I(3),r=n[t];return N(()=>{if(!l){c(3);return}if(s<0){let o=(t+1)%n.length;i(o);return}let m=setInterval(()=>{c(o=>Math.max(o-1,-1))},1e3);return()=>clearInterval(m)},[s,l]),e.createElement(e.Fragment,null,e.createElement("style",null,y),e.createElement("div",{className:"playlist",style:{display:l?"grid":"none"}},e.createElement("div",{className:"overlay",style:{display:l?"grid":"none"}}),e.createElement("div",{className:"post-video-section",style:{display:l?"grid":"none",zIndex:99}},e.createElement("div",{className:"video-section"},e.createElement("div",{className:"video-container"},e.createElement("h2",{className:"title"},"Video"),e.createElement("div",{className:"video-wrapper"},e.createElement("img",{className:"video-thumbnail",src:r.imageUrl,alt:r.title}),e.createElement("div",{className:"countdown-overlay"},e.createElement("svg",{className:"countdown-ring",width:"50",height:"50"},e.createElement("circle",{cx:"25",cy:"25",r:"22",className:"circle-background"}),e.createElement("circle",{cx:"25",cy:"25",r:"22",className:"circle-progress",style:{strokeDasharray:"138",strokeDashoffset:`${s/3*138}`}})),e.createElement("span",{className:"count-text"},s))),e.createElement("p",{className:"video-title"},r.title))),e.createElement("hr",null),e.createElement("div",{className:"related-videos-section"},e.createElement("h3",{className:"related-title"},"Related Videos"),e.createElement("ul",{className:"related-list"},n.map((m,o)=>e.createElement("li",{key:o},e.createElement("button",{className:"related-item",onClick:()=>i(o)},e.createElement("img",{className:"related-thumbnail",src:m.imageUrl,alt:m.title}),e.createElement("p",{className:"related-text"},m.title)))))))))},h=z;var U=({videoList:t,...n})=>{var g,u;let l=E(null),[i,s]=f(0),[c,r]=f(typeof((g=t[i])==null?void 0:g.adTagUrl)=="string"?(u=t[i])==null?void 0:u.adTagUrl:void 0);b(()=>{s(0)},[t]),b(()=>{var p;let a=(p=t[i])==null?void 0:p.adTagUrl;if(typeof a=="string")r(a);else if(typeof a=="function"){let d=a();typeof d=="string"?r(d):typeof(d==null?void 0:d.then)=="function"&&(r(void 0),d.then(r))}},[i]);let[m,o]=f(!1),[x,w]=f(0);function k(a){o(!1),r(void 0),s(a),setTimeout(()=>{try{l.current.play()}catch{}},200)}return v.createElement(S,{theme:T,style:{aspectRatio:"16/9"},preferPlayback:"mse",maxResolution:"2160p",minResolution:"540p",renditionOrder:"desc",metadata:{video_title:t[i].title},...n,ref:l,key:`player-${x}`,playbackId:c?t[i].playbackId:void 0,adTagUrl:c,onEnded:a=>{var p;i<t.length-1?o(!0):(s(0),w(d=>d+1)),(p=n.onEnded)==null||p.call(n,a)}},v.createElement(h,{currentIndex:i,relatedVideos:t,visible:m,selectVideoCallback:k}))},_=U;export{_ as default};
//# sourceMappingURL=index.mjs.map
