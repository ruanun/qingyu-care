(function(){"use strict";function O(){const e=document.currentScript??document.querySelector('script[src*="widget.js"]');if(!e||!e.src)return console.warn("[Qingyu Widget] 未找到加载脚本，无法初始化"),null;const o=new URL(e.src),t=o.searchParams.get("appKey");return t?{appKey:t,apiBase:o.origin+"/api"}:(console.warn("[Qingyu Widget] 缺少 appKey，无法初始化"),null)}class A extends Error{constructor(){super("会话不存在或已过期"),this.name="SessionExpiredError"}}function H(e){const o=()=>({"Content-Type":"application/json","X-App-Id":e.appKey});return{async getConfig(){const t=await fetch(`${e.apiBase}/open/v1/widget/config`,{headers:o()});if(!t.ok)throw new Error(`widget config 请求失败: ${t.status}`);return await t.json()},async send(t,r){const p=r?{message:t,sessionId:r}:{message:t},n=await fetch(`${e.apiBase}/open/v1/chat/completions`,{method:"POST",headers:o(),body:JSON.stringify(p)});if(n.status===404)throw new A;if(!n.ok)throw new Error(`对话请求失败: ${n.status}`);return await n.json()},async handover(t){await fetch(`${e.apiBase}/open/v1/sessions/${t}/handover`,{method:"POST",headers:o()})},async feedback(t,r,p){try{await fetch(`${e.apiBase}/open/v1/chat/feedback`,{method:"POST",headers:o(),body:JSON.stringify({sessionId:t,messageId:r,feedback:p})})}catch{}},inboxUrl(t){return`${e.apiBase}/open/v1/sessions/${t}/inbox`},sendTyping(t){return fetch(`${e.apiBase}/open/v1/sessions/${t}/typing`,{method:"POST",headers:o()}).then(()=>{}).catch(()=>{})},async getSession(t){try{const r=await fetch(`${e.apiBase}/open/v1/sessions/${t}`,{headers:o()});return r.ok?await r.json():null}catch{return null}}}}const U=1440*60*1e3,L=e=>`qingyu_widget_session_${e}`;function j(e){try{const o=localStorage.getItem(L(e));if(!o)return null;const t=JSON.parse(o);return!t||typeof t.sessionId!="string"||typeof t.createdAt!="number"||Date.now()-t.createdAt>U?(N(e),null):t.sessionId}catch{return null}}function I(e,o){try{const t={sessionId:o,createdAt:Date.now()};localStorage.setItem(L(e),JSON.stringify(t))}catch{}}function N(e){try{localStorage.removeItem(L(e))}catch{}}function K(e){return`
    :host { all: initial; }
    * { box-sizing: border-box; font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
    .qy-bubble {
      position: fixed; right: 20px; bottom: 20px; width: 56px; height: 56px;
      border-radius: 50%; background: ${e}; color: #fff; border: none;
      cursor: pointer; box-shadow: 0 4px 12px rgba(0,0,0,.2); font-size: 24px; z-index: 2147483000;
    }
    .qy-panel {
      position: fixed; right: 20px; bottom: 88px; width: 360px; height: 520px;
      background: #fff; border-radius: 12px; box-shadow: 0 8px 30px rgba(0,0,0,.18);
      display: none; flex-direction: column; overflow: hidden; z-index: 2147483000;
    }
    .qy-panel.open { display: flex; }
    .qy-header {
      background: ${e}; color: #fff; padding: 12px 16px;
      display: flex; align-items: center; justify-content: space-between; font-weight: 600;
    }
    .qy-header .qy-handover {
      background: rgba(255,255,255,.2); color: #fff; border: none; border-radius: 6px;
      padding: 4px 10px; cursor: pointer; font-size: 12px;
    }
    .qy-msgs { flex: 1; overflow-y: auto; padding: 12px; background: #f7f7f8; }
    .qy-msg { margin-bottom: 10px; display: flex; }
    .qy-msg.user { justify-content: flex-end; }
    .qy-msg .qy-text {
      max-width: 78%; padding: 8px 12px; border-radius: 10px; font-size: 14px; line-height: 1.5;
      white-space: pre-wrap; word-break: break-word;
    }
    .qy-msg.bot .qy-text { background: #fff; border: 1px solid #eee; }
    .qy-msg.user .qy-text { background: ${e}; color: #fff; }
    .qy-input { display: flex; border-top: 1px solid #eee; }
    .qy-input textarea {
      flex: 1; border: none; resize: none; padding: 12px; font-size: 14px; outline: none; height: 48px;
    }
    .qy-input button {
      border: none; background: ${e}; color: #fff; padding: 0 16px; cursor: pointer;
    }
    .qy-input button:disabled { opacity: .5; cursor: not-allowed; }
    .qy-msg.has-feedback { flex-direction: column; align-items: flex-start; }
    .qy-msg .qy-feedback { display: flex; gap: 4px; margin-top: 4px; }
    .qy-msg.bot .qy-feedback { justify-content: flex-start; }
    .qy-feedback button { background: none; border: 1px solid #e5e7eb; border-radius: 10px;
      cursor: pointer; font-size: 12px; line-height: 1; padding: 2px 8px; opacity: .7; }
    .qy-feedback button:hover { opacity: 1; }
    .qy-feedback button:disabled { cursor: default; opacity: .4; }
    .qy-feedback button.active { opacity: 1; border-color: currentColor; }
    @media (max-width: 480px) {
      .qy-panel { right: 0; bottom: 0; width: 100vw; height: 100vh; border-radius: 0; }
    }
    .qy-card { border:1px solid #e5e7eb; border-radius:8px; padding:10px; margin:6px 0; background:#fff; }
    .qy-card-title { font-weight:600; margin-bottom:6px; }
    .qy-card-body { display:flex; flex-direction:column; gap:4px; }
    .qy-node-text { font-size:14px; }
    .qy-emph { font-weight:600; }
    .qy-kv { display:flex; justify-content:space-between; font-size:13px; gap:8px; }
    .qy-kv-label { color:#6b7280; }
    .qy-kv-value { color:#111827; font-weight:500; }
    .qy-badge { display:inline-block; padding:1px 8px; border-radius:9999px; background:#f3f4f6; color:#374151; font-size:12px; }
    .qy-divider { height:1px; background:#e5e7eb; margin:6px 0; }
    .qy-node-img { max-width:100%; border-radius:6px; }
    .qy-card-btn { margin:4px 6px 0 0; padding:4px 10px; border:1px solid ${e}; color:${e}; background:#fff; border-radius:6px; cursor:pointer; font-size:13px; }
    .qy-card-btn:hover { background:${e}; color:#fff; }
    .qy-actions { display:flex; flex-wrap:wrap; }
    .qy-list { margin:4px 0; padding-left:18px; }
    .qy-row { display:flex; gap:8px; }
    .qy-col { display:flex; flex-direction:column; gap:4px; }
  `}function W(e){if(typeof e!="string"||e.length>2048)return"";try{const o=new URL(e);return o.protocol==="http:"||o.protocol==="https:"?e:""}catch{return""}}function w(e){return e==null?"":String(e)}function y(e,o){const t=document.createElement(e);return o&&(t.className=o),t}function M(e,o){if(!e||typeof e!="object")return null;const t=e.props??{},r=Array.isArray(e.children)?e.children:[],p=n=>{for(const s of r){const i=M(s,o);i&&n.appendChild(i)}};switch(e.type){case"card":{const n=y("div","qy-card");if(t.title!=null&&w(t.title)!==""){const i=y("div","qy-card-title");i.textContent=w(t.title),n.appendChild(i)}const s=y("div","qy-card-body");return p(s),n.appendChild(s),n}case"text":{const n=y("div",t.emphasis?"qy-node-text qy-emph":"qy-node-text");return n.textContent=w(t.value??t.text),n}case"kv":{const n=y("div","qy-kv"),s=y("span","qy-kv-label");s.textContent=w(t.label);const i=y("span","qy-kv-value");return i.textContent=w(t.value),n.appendChild(s),n.appendChild(i),n}case"badge":{const n=y("span","qy-badge");return n.textContent=w(t.text??t.value),n}case"divider":return y("div","qy-divider");case"image":{const n=W(t.src??t.url);if(!n)return null;const s=y("img","qy-node-img");return s.src=n,s.alt=w(t.alt),s}case"button":{const n=y("button","qy-card-btn");return n.type="button",n.textContent=w(t.label),n.addEventListener("click",()=>o(w(t.value??t.label))),n}case"actions":{const n=y("div","qy-actions");return p(n),n}case"list":{const n=y("ul","qy-list");for(const s of r){const i=M(s,o);if(i){const x=document.createElement("li");x.appendChild(i),n.appendChild(x)}}return n}case"row":{const n=y("div","qy-row");return p(n),n}case"column":{const n=y("div","qy-col");return p(n),n}default:return null}}function _(e,o){const t=document.createElement("div");document.body.appendChild(t);const r=t.attachShadow({mode:"open"}),p=document.createElement("style");p.textContent=K(e.themeColor||"#6366f1"),r.appendChild(p);const n=document.createElement("button");n.className="qy-bubble",n.textContent="💬",n.setAttribute("aria-label","打开客服");const s=document.createElement("div");s.className="qy-panel",s.innerHTML=`
    <div class="qy-header">
      <span>${R(e.appName||"在线客服")}</span>
      <button class="qy-handover" type="button">转人工</button>
    </div>
    <div class="qy-msgs"></div>
    <div class="qy-input">
      <textarea placeholder="输入消息…"></textarea>
      <button type="button">发送</button>
    </div>
  `,r.appendChild(n),r.appendChild(s);const i=s.querySelector(".qy-msgs"),x=s.querySelector("textarea"),E=s.querySelector(".qy-input button"),c=s.querySelector(".qy-handover");let k=!0;n.addEventListener("click",()=>{s.classList.toggle("open"),k&&s.classList.contains("open")&&(k=!1,e.welcomeMessage&&b(e.welcomeMessage))});const C=()=>{const d=x.value.trim();d&&(x.value="",o.onSend(d))};E.addEventListener("click",C),x.addEventListener("keydown",d=>{d.key==="Enter"&&!d.shiftKey&&(d.preventDefault(),C())}),x.addEventListener("input",()=>o.onTyping?.()),c.addEventListener("click",()=>o.onHandover());function m(d,f){const u=document.createElement("div");u.className=`qy-msg ${f}`;const T=document.createElement("div");return T.className="qy-text",T.textContent=d,u.appendChild(T),i.appendChild(u),i.scrollTop=i.scrollHeight,u}function a(d){const f=document.createElement("div");f.className="qy-msg bot";const u=document.createElement("div");u.className="qy-text";const T=o.onAction??o.onSend;for(const q of d)if(q.kind==="text"){const g=q.text??"";if(g){const h=document.createElement("div");h.className="qy-node-text",h.textContent=g,u.appendChild(h)}}else if(q.kind==="ui"&&q.node){const g=M(q.node,T);g&&u.appendChild(g)}return f.appendChild(u),i.appendChild(f),i.scrollTop=i.scrollHeight,f}function l(d){m(d,"user")}function b(d,f,u){const q=Array.isArray(u)&&u.some(g=>g.kind==="ui"&&g.node)?a(u):m(d,"bot");if(f){q.classList.add("has-feedback");const g=document.createElement("div");g.className="qy-feedback";const h=document.createElement("button");h.type="button",h.textContent="👍",h.setAttribute("aria-label","有帮助");const S=document.createElement("button");S.type="button",S.textContent="👎",S.setAttribute("aria-label","没帮助");const z=(Y,G)=>{f.onFeedback(Y),h.disabled=!0,S.disabled=!0,G.classList.add("active")};h.addEventListener("click",()=>z(1,h)),S.addEventListener("click",()=>z(-1,S)),g.appendChild(h),g.appendChild(S),q.appendChild(g),i.scrollTop=i.scrollHeight}}function v(d){const f=m(d,"bot"),u=document.createElement("div");u.textContent="客服",u.style.cssText="font-size:11px;color:#999;margin-bottom:2px;",f.insertBefore(u,f.firstChild)}function Q(d){const f=document.createElement("div");f.style.cssText="text-align:center;color:#999;font-size:12px;margin:8px 0;",f.textContent=d,i.appendChild(f),i.scrollTop=i.scrollHeight}let $=null;function F(d){d&&!$?$=m("输入中…","bot"):!d&&$&&($.remove(),$=null)}function X(d){x.disabled=!d,E.disabled=!d}return{addUserMessage:l,addBotMessage:b,addAgentMessage:v,showSessionState:Q,setTyping:F,setInputEnabled:X}}function R(e){const o=document.createElement("div");return o.textContent=e,o.innerHTML}function D(e){let o="message";const t=[];for(const r of e.split(`
`))r===""||r.startsWith(":")||(r.startsWith("event:")?o=r.slice(6).trim():r.startsWith("data:")&&t.push(r.slice(5).replace(/^ /,"")));return{event:o,data:t.length?t.join(`
`):null}}function P(e,o,t){let r=!1,p=null,n=0;return(async()=>{for(;!r;){p=new AbortController;try{const i=await fetch(e,{headers:{"X-App-Id":o,Accept:"text/event-stream"},signal:p.signal});if(!i.ok||!i.body)throw new Error("SSE "+i.status);n=0;const x=i.body.getReader(),E=new TextDecoder;let c="";for(;;){const{done:k,value:C}=await x.read();if(k||r)break;c+=E.decode(C,{stream:!0});let m;for(;(m=c.indexOf(`

`))>=0;){const a=c.slice(0,m);c=c.slice(m+2);const{event:l,data:b}=D(a);b!==null&&t(l,b)}}}catch{if(r)return}if(r)return;n+=1,await new Promise(i=>setTimeout(i,Math.min(15e3,500*2**Math.min(n,5))))}})(),{close:()=>{r=!0,p?.abort()}}}function J(e){switch(e){case"accepted":return"客服已接入";case"returned_to_ai":return"已切回智能助手";case"ended":return"会话已结束";case"waiting":return"正在为您转接人工…";default:return""}}async function B(){const e=O();if(!e)return;const o=H(e);let t;try{t=await o.getConfig()}catch(a){console.warn("[Qingyu Widget] 拉取配置失败，停止渲染",a);return}let r=j(e.appKey),p=null,n=null;function s(a){n===a&&p||(p?.close(),n=a,p=P(o.inboxUrl(a),e.appKey,(l,b)=>{let v;try{v=JSON.parse(b)}catch{return}l==="message"&&v.role==="ASSISTANT"?c.addAgentMessage(v.content):l==="session"?c.showSessionState(v.text||J(v.kind)):l==="typing"&&v.from==="agent"&&c.setTyping(!0)}))}window.addEventListener("pagehide",()=>p?.close());let i=0;const x=3e3;function E(){if(!r)return;const a=Date.now();a-i<x||(i=a,o.sendTyping(r))}const c=_(t,{onSend:a=>{k(a)},onHandover:()=>{m()},onTyping:()=>E(),onAction:a=>{k(a)}});r&&s(r);async function k(a){c.addUserMessage(a),c.setInputEnabled(!1),c.setTyping(!0);try{const l=await C(a);s(l.sessionId),c.setTyping(!1);const b=l.messageId;c.addBotMessage(l.reply,b?{onFeedback:v=>{o.feedback(l.sessionId,b,v)}}:void 0,l.uiParts)}catch(l){c.setTyping(!1),c.addBotMessage("抱歉，服务暂时不可用，请稍后再试。"),console.warn("[Qingyu Widget] 发送失败",l)}finally{c.setInputEnabled(!0)}}async function C(a){try{const l=await o.send(a,r);return r=l.sessionId,I(e.appKey,r),l}catch(l){if(l instanceof A){N(e.appKey),r=null;const b=await o.send(a,null);return r=b.sessionId,I(e.appKey,r),b}throw l}}async function m(){if(!r){c.addBotMessage("请先发送消息开始会话，再申请转人工。");return}try{await o.handover(r),c.addBotMessage("已为你转接人工，请稍候。")}catch(a){console.warn("[Qingyu Widget] 转人工失败",a)}}}document.readyState==="loading"?document.addEventListener("DOMContentLoaded",()=>{B()}):B()})();
