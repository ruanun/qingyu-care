(function(){"use strict";function H(){const n=document.currentScript??document.querySelector('script[src*="widget.js"]');if(!n||!n.src)return console.warn("[Qingyu Widget] 未找到加载脚本，无法初始化"),null;const o=new URL(n.src),e=o.searchParams.get("appKey");return e?{appKey:e,apiBase:o.origin+"/api"}:(console.warn("[Qingyu Widget] 缺少 appKey，无法初始化"),null)}class L extends Error{constructor(){super("会话不存在或已过期"),this.name="SessionExpiredError"}}function U(n){const o=()=>({"Content-Type":"application/json","X-App-Id":n.appKey});return{async getConfig(){const e=await fetch(`${n.apiBase}/open/v1/widget/config`,{headers:o()});if(!e.ok)throw new Error(`widget config 请求失败: ${e.status}`);return await e.json()},async send(e,a){const u=a?{message:e,sessionId:a}:{message:e},t=await fetch(`${n.apiBase}/open/v1/chat/completions`,{method:"POST",headers:o(),body:JSON.stringify(u)});if(t.status===404)throw new L;if(!t.ok)throw new Error(`对话请求失败: ${t.status}`);return await t.json()},async handover(e){await fetch(`${n.apiBase}/open/v1/sessions/${e}/handover`,{method:"POST",headers:o()})},async feedback(e,a,u){try{await fetch(`${n.apiBase}/open/v1/chat/feedback`,{method:"POST",headers:o(),body:JSON.stringify({sessionId:e,messageId:a,feedback:u})})}catch{}},inboxUrl(e){return`${n.apiBase}/open/v1/sessions/${e}/inbox`},sendTyping(e){return fetch(`${n.apiBase}/open/v1/sessions/${e}/typing`,{method:"POST",headers:o()}).then(()=>{}).catch(()=>{})},async getSession(e){try{const a=await fetch(`${n.apiBase}/open/v1/sessions/${e}`,{headers:o()});return a.ok?await a.json():null}catch{return null}}}}const j=1440*60*1e3,M=n=>`qingyu_widget_session_${n}`;function K(n){try{const o=localStorage.getItem(M(n));if(!o)return null;const e=JSON.parse(o);return!e||typeof e.sessionId!="string"||typeof e.createdAt!="number"||Date.now()-e.createdAt>j?(z(n),null):e.sessionId}catch{return null}}function I(n,o){try{const e={sessionId:o,createdAt:Date.now()};localStorage.setItem(M(n),JSON.stringify(e))}catch{}}function z(n){try{localStorage.removeItem(M(n))}catch{}}function W(n){return`
    :host { all: initial; }
    * { box-sizing: border-box; font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
    .qy-bubble {
      position: fixed; right: 20px; bottom: 20px; width: 56px; height: 56px;
      border-radius: 50%; background: ${n}; color: #fff; border: none;
      cursor: pointer; box-shadow: 0 4px 12px rgba(0,0,0,.2); font-size: 24px; z-index: 2147483000;
    }
    .qy-panel {
      position: fixed; right: 20px; bottom: 88px; width: 360px; height: 520px;
      background: #fff; border-radius: 12px; box-shadow: 0 8px 30px rgba(0,0,0,.18);
      display: none; flex-direction: column; overflow: hidden; z-index: 2147483000;
    }
    .qy-panel.open { display: flex; }
    .qy-header {
      background: ${n}; color: #fff; padding: 12px 16px;
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
    .qy-msg.user .qy-text { background: ${n}; color: #fff; }
    .qy-input { display: flex; border-top: 1px solid #eee; }
    .qy-input textarea {
      flex: 1; border: none; resize: none; padding: 12px; font-size: 14px; outline: none; height: 48px;
    }
    .qy-input button {
      border: none; background: ${n}; color: #fff; padding: 0 16px; cursor: pointer;
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
    .qy-card-btn { margin:4px 6px 0 0; padding:4px 10px; border:1px solid ${n}; color:${n}; background:#fff; border-radius:6px; cursor:pointer; font-size:13px; }
    .qy-card-btn:hover { background:${n}; color:#fff; }
    .qy-actions { display:flex; flex-wrap:wrap; }
    .qy-list { margin:4px 0; padding-left:18px; }
    .qy-row { display:flex; gap:8px; }
    .qy-col { display:flex; flex-direction:column; gap:4px; }
    .qy-form { display:flex; flex-direction:column; gap:8px; padding:8px 0; }
    .qy-field-row { display:flex; flex-direction:column; gap:2px; }
    .qy-field-label { font-size:12px; color:#6b7280; }
    .qy-form-input, .qy-select { padding:6px 8px; border:1px solid #d1d5db; border-radius:6px; font-size:13px; }
    .qy-checkbox { display:flex; align-items:center; gap:6px; font-size:13px; }
    .qy-form-submit { align-self:flex-start; padding:6px 14px; border:none; border-radius:6px; background:${n}; color:#fff; cursor:pointer; font-size:13px; }
  `}function _(n){if(typeof n!="string"||n.length>2048)return"";try{const o=new URL(n);return o.protocol==="http:"||o.protocol==="https:"?n:""}catch{return""}}function p(n){return n==null?"":String(n)}function y(n,o){const e=document.createElement(n);return o&&(e.className=o),e}function $(n,o){if(!n)return o;const e=document.createElement("div");e.className="qy-field-row";const a=document.createElement("div");return a.className="qy-field-label",a.textContent=n,e.appendChild(a),e.appendChild(o),e}function N(n,o){if(!n||typeof n!="object")return null;const e=n.props??{},a=Array.isArray(n.children)?n.children:[],u=t=>{for(const s of a){const r=N(s,o);r&&t.appendChild(r)}};switch(n.type){case"card":{const t=y("div","qy-card");if(e.title!=null&&p(e.title)!==""){const r=y("div","qy-card-title");r.textContent=p(e.title),t.appendChild(r)}const s=y("div","qy-card-body");return u(s),t.appendChild(s),t}case"text":{const t=y("div",e.emphasis?"qy-node-text qy-emph":"qy-node-text");return t.textContent=p(e.value??e.text),t}case"kv":{const t=y("div","qy-kv"),s=y("span","qy-kv-label");s.textContent=p(e.label);const r=y("span","qy-kv-value");return r.textContent=p(e.value),t.appendChild(s),t.appendChild(r),t}case"badge":{const t=y("span","qy-badge");return t.textContent=p(e.text??e.value),t}case"divider":return y("div","qy-divider");case"image":{const t=_(e.src??e.url);if(!t)return null;const s=y("img","qy-node-img");return s.src=t,s.alt=p(e.alt),s}case"button":{const t=y("button","qy-card-btn");return t.type="button",t.textContent=p(e.label),t.addEventListener("click",()=>o(p(e.value??e.label))),t}case"actions":{const t=y("div","qy-actions");return u(t),t}case"list":{const t=y("ul","qy-list");for(const s of a){const r=N(s,o);if(r){const c=document.createElement("li");c.appendChild(r),t.appendChild(c)}}return t}case"row":{const t=y("div","qy-row");return u(t),t}case"column":{const t=y("div","qy-col");return u(t),t}case"input":{const t=document.createElement("input");return t.className="qy-field qy-form-input",t.type="text",e.placeholder!=null&&(t.placeholder=p(e.placeholder)),t.setAttribute("data-qy-field",p(e.name)),$(p(e.label),t)}case"datetime":{const t=document.createElement("input");return t.className="qy-field qy-form-input",t.type="date",t.setAttribute("data-qy-field",p(e.name)),$(p(e.label),t)}case"checkbox":{const t=document.createElement("input");t.className="qy-field",t.type="checkbox",t.setAttribute("data-qy-field",p(e.name));const s=y("label","qy-checkbox");s.appendChild(t);const r=document.createElement("span");return r.textContent=p(e.label??e.name),s.appendChild(r),s}case"select":{const t=document.createElement("select");t.className="qy-field qy-select",t.setAttribute("data-qy-field",p(e.name));const s=Array.isArray(e.options)?e.options:[];for(const r of s){const c=document.createElement("option");c.value=p(r),c.textContent=p(r),t.appendChild(c)}return $(p(e.label),t)}case"form":{const t=y("div","qy-form");return u(t),t}case"submit":{const t=y("button","qy-form-submit");return t.type="button",t.textContent=p(e.label??"提交"),t.addEventListener("click",()=>{const s=t.closest(".qy-form");if(!s)return;const r=[];s.querySelectorAll("[data-qy-field]").forEach(c=>{const w=c.getAttribute("data-qy-field")??"";c instanceof HTMLInputElement&&c.type==="checkbox"?r.push(`${w}: ${c.checked?"是":"否"}`):(c instanceof HTMLInputElement||c instanceof HTMLSelectElement)&&r.push(`${w}: ${c.value}`)}),o(r.join(`
`))}),t}default:return null}}function R(n,o){const e=document.createElement("div");document.body.appendChild(e);const a=e.attachShadow({mode:"open"}),u=document.createElement("style");u.textContent=W(n.themeColor||"#6366f1"),a.appendChild(u);const t=document.createElement("button");t.className="qy-bubble",t.textContent="💬",t.setAttribute("aria-label","打开客服");const s=document.createElement("div");s.className="qy-panel",s.innerHTML=`
    <div class="qy-header">
      <span>${P(n.appName||"在线客服")}</span>
      <button class="qy-handover" type="button">转人工</button>
    </div>
    <div class="qy-msgs"></div>
    <div class="qy-input">
      <textarea placeholder="输入消息…"></textarea>
      <button type="button">发送</button>
    </div>
  `,a.appendChild(t),a.appendChild(s);const r=s.querySelector(".qy-msgs"),c=s.querySelector("textarea"),w=s.querySelector(".qy-input button"),l=s.querySelector(".qy-handover");let k=!0;t.addEventListener("click",()=>{s.classList.toggle("open"),k&&s.classList.contains("open")&&(k=!1,n.welcomeMessage&&g(n.welcomeMessage))});const C=()=>{const d=c.value.trim();d&&(c.value="",o.onSend(d))};w.addEventListener("click",C),c.addEventListener("keydown",d=>{d.key==="Enter"&&!d.shiftKey&&(d.preventDefault(),C())}),c.addEventListener("input",()=>o.onTyping?.()),l.addEventListener("click",()=>o.onHandover());function q(d,x){const b=document.createElement("div");b.className=`qy-msg ${x}`;const T=document.createElement("div");return T.className="qy-text",T.textContent=d,b.appendChild(T),r.appendChild(b),r.scrollTop=r.scrollHeight,b}function i(d){const x=document.createElement("div");x.className="qy-msg bot";const b=document.createElement("div");b.className="qy-text";const T=o.onAction??o.onSend;for(const S of d)if(S.kind==="text"){const m=S.text??"";if(m){const h=document.createElement("div");h.className="qy-node-text",h.textContent=m,b.appendChild(h)}}else if(S.kind==="ui"&&S.node){const m=N(S.node,T);m&&b.appendChild(m)}return x.appendChild(b),r.appendChild(x),r.scrollTop=r.scrollHeight,x}function f(d){q(d,"user")}function g(d,x,b){const S=Array.isArray(b)&&b.some(m=>m.kind==="ui"&&m.node)?i(b):q(d,"bot");if(x){S.classList.add("has-feedback");const m=document.createElement("div");m.className="qy-feedback";const h=document.createElement("button");h.type="button",h.textContent="👍",h.setAttribute("aria-label","有帮助");const E=document.createElement("button");E.type="button",E.textContent="👎",E.setAttribute("aria-label","没帮助");const O=(Z,ee)=>{x.onFeedback(Z),h.disabled=!0,E.disabled=!0,ee.classList.add("active")};h.addEventListener("click",()=>O(1,h)),E.addEventListener("click",()=>O(-1,E)),m.appendChild(h),m.appendChild(E),S.appendChild(m),r.scrollTop=r.scrollHeight}}function v(d){const x=q(d,"bot"),b=document.createElement("div");b.textContent="客服",b.style.cssText="font-size:11px;color:#999;margin-bottom:2px;",x.insertBefore(b,x.firstChild)}function Y(d){const x=document.createElement("div");x.style.cssText="text-align:center;color:#999;font-size:12px;margin:8px 0;",x.textContent=d,r.appendChild(x),r.scrollTop=r.scrollHeight}let A=null;function G(d){d&&!A?A=q("输入中…","bot"):!d&&A&&(A.remove(),A=null)}function X(d){c.disabled=!d,w.disabled=!d}function V(){k=!1}return{addUserMessage:f,addBotMessage:g,addAgentMessage:v,showSessionState:Y,setTyping:G,setInputEnabled:X,markOpened:V}}function P(n){const o=document.createElement("div");return o.textContent=n,o.innerHTML}function D(n){let o="message";const e=[];for(const a of n.split(`
`))a===""||a.startsWith(":")||(a.startsWith("event:")?o=a.slice(6).trim():a.startsWith("data:")&&e.push(a.slice(5).replace(/^ /,"")));return{event:o,data:e.length?e.join(`
`):null}}function J(n,o,e){let a=!1,u=null,t=0;return(async()=>{for(;!a;){u=new AbortController;try{const r=await fetch(n,{headers:{"X-App-Id":o,Accept:"text/event-stream"},signal:u.signal});if(!r.ok||!r.body)throw new Error("SSE "+r.status);t=0;const c=r.body.getReader(),w=new TextDecoder;let l="";for(;;){const{done:k,value:C}=await c.read();if(k||a)break;l+=w.decode(C,{stream:!0});let q;for(;(q=l.indexOf(`

`))>=0;){const i=l.slice(0,q);l=l.slice(q+2);const{event:f,data:g}=D(i);g!==null&&e(f,g)}}}catch{if(a)return}if(a)return;t+=1,await new Promise(r=>setTimeout(r,Math.min(15e3,500*2**Math.min(t,5))))}})(),{close:()=>{a=!0,u?.abort()}}}function Q(n,o){for(const e of o)switch(e.role){case"USER":n.addUserMessage(e.content);break;case"ASSISTANT":n.addBotMessage(e.content,void 0,e.uiParts);break;case"AGENT":n.addAgentMessage(e.content);break;case"SYSTEM":n.showSessionState(e.content);break}}function F(n){switch(n){case"accepted":return"客服已接入";case"returned_to_ai":return"已切回智能助手";case"ended":return"会话已结束";case"waiting":return"正在为您转接人工…";default:return""}}async function B(){const n=H();if(!n)return;const o=U(n);let e;try{e=await o.getConfig()}catch(i){console.warn("[Qingyu Widget] 拉取配置失败，停止渲染",i);return}let a=K(n.appKey),u=null,t=null;function s(i){t===i&&u||(u?.close(),t=i,u=J(o.inboxUrl(i),n.appKey,(f,g)=>{let v;try{v=JSON.parse(g)}catch{return}f==="message"&&v.role==="ASSISTANT"?l.addAgentMessage(v.content):f==="session"?l.showSessionState(v.text||F(v.kind)):f==="typing"&&v.from==="agent"&&l.setTyping(!0)}))}window.addEventListener("pagehide",()=>u?.close());let r=0;const c=3e3;function w(){if(!a)return;const i=Date.now();i-r<c||(r=i,o.sendTyping(a))}const l=R(e,{onSend:i=>{k(i)},onHandover:()=>{q()},onTyping:()=>w(),onAction:i=>{k(i)}});if(a){l.markOpened();const i=await o.getSession(a);i&&i.messages.length&&Q(l,i.messages),s(a)}async function k(i){l.addUserMessage(i),l.setInputEnabled(!1),l.setTyping(!0);try{const f=await C(i);s(f.sessionId),l.setTyping(!1);const g=f.messageId;l.addBotMessage(f.reply,g?{onFeedback:v=>{o.feedback(f.sessionId,g,v)}}:void 0,f.uiParts)}catch(f){l.setTyping(!1),l.addBotMessage("抱歉，服务暂时不可用，请稍后再试。"),console.warn("[Qingyu Widget] 发送失败",f)}finally{l.setInputEnabled(!0)}}async function C(i){try{const f=await o.send(i,a);return a=f.sessionId,I(n.appKey,a),f}catch(f){if(f instanceof L){z(n.appKey),a=null;const g=await o.send(i,null);return a=g.sessionId,I(n.appKey,a),g}throw f}}async function q(){if(!a){l.addBotMessage("请先发送消息开始会话，再申请转人工。");return}try{await o.handover(a),l.addBotMessage("已为你转接人工，请稍候。")}catch(i){console.warn("[Qingyu Widget] 转人工失败",i)}}}document.readyState==="loading"?document.addEventListener("DOMContentLoaded",()=>{B()}):B()})();
