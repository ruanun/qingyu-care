(function(){"use strict";function O(){const n=document.currentScript??document.querySelector('script[src*="widget.js"]');if(!n||!n.src)return console.warn("[Qingyu Widget] 未找到加载脚本，无法初始化"),null;const o=new URL(n.src),e=o.searchParams.get("appKey");return e?{appKey:e,apiBase:o.origin+"/api"}:(console.warn("[Qingyu Widget] 缺少 appKey，无法初始化"),null)}class N extends Error{constructor(){super("会话不存在或已过期"),this.name="SessionExpiredError"}}function U(n){const o=()=>({"Content-Type":"application/json","X-App-Id":n.appKey});return{async getConfig(){const e=await fetch(`${n.apiBase}/open/v1/widget/config`,{headers:o()});if(!e.ok)throw new Error(`widget config 请求失败: ${e.status}`);return await e.json()},async send(e,r){const c=r?{message:e,sessionId:r}:{message:e},t=await fetch(`${n.apiBase}/open/v1/chat/completions`,{method:"POST",headers:o(),body:JSON.stringify(c)});if(t.status===404)throw new N;if(!t.ok)throw new Error(`对话请求失败: ${t.status}`);return await t.json()},async handover(e){await fetch(`${n.apiBase}/open/v1/sessions/${e}/handover`,{method:"POST",headers:o()})},async feedback(e,r,c){try{await fetch(`${n.apiBase}/open/v1/chat/feedback`,{method:"POST",headers:o(),body:JSON.stringify({sessionId:e,messageId:r,feedback:c})})}catch{}},inboxUrl(e){return`${n.apiBase}/open/v1/sessions/${e}/inbox`},sendTyping(e){return fetch(`${n.apiBase}/open/v1/sessions/${e}/typing`,{method:"POST",headers:o()}).then(()=>{}).catch(()=>{})},async getSession(e){try{const r=await fetch(`${n.apiBase}/open/v1/sessions/${e}`,{headers:o()});return r.ok?await r.json():null}catch{return null}}}}const j=1440*60*1e3,L=n=>`qingyu_widget_session_${n}`;function K(n){try{const o=localStorage.getItem(L(n));if(!o)return null;const e=JSON.parse(o);return!e||typeof e.sessionId!="string"||typeof e.createdAt!="number"||Date.now()-e.createdAt>j?(z(n),null):e.sessionId}catch{return null}}function I(n,o){try{const e={sessionId:o,createdAt:Date.now()};localStorage.setItem(L(n),JSON.stringify(e))}catch{}}function z(n){try{localStorage.removeItem(L(n))}catch{}}function W(n){return`
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
    .qy-field-invalid { border-color:#ef4444; }
  `}function _(n){if(typeof n!="string"||n.length>2048)return"";try{const o=new URL(n);return o.protocol==="http:"||o.protocol==="https:"?n:""}catch{return""}}function p(n){return n==null?"":String(n)}function y(n,o){const e=document.createElement(n);return o&&(e.className=o),e}function M(n,o,e){if(!n)return o;const r=document.createElement("div");r.className="qy-field-row";const c=document.createElement("div");return c.className="qy-field-label",c.textContent=e?`${n} *`:n,r.appendChild(c),r.appendChild(o),r}function $(n,o){if(!n||typeof n!="object")return null;const e=n.props??{},r=Array.isArray(n.children)?n.children:[],c=t=>{for(const s of r){const a=$(s,o);a&&t.appendChild(a)}};switch(n.type){case"card":{const t=y("div","qy-card");if(e.title!=null&&p(e.title)!==""){const a=y("div","qy-card-title");a.textContent=p(e.title),t.appendChild(a)}const s=y("div","qy-card-body");return c(s),t.appendChild(s),t}case"text":{const t=y("div",e.emphasis?"qy-node-text qy-emph":"qy-node-text");return t.textContent=p(e.value??e.text),t}case"kv":{const t=y("div","qy-kv"),s=y("span","qy-kv-label");s.textContent=p(e.label);const a=y("span","qy-kv-value");return a.textContent=p(e.value),t.appendChild(s),t.appendChild(a),t}case"badge":{const t=y("span","qy-badge");return t.textContent=p(e.text??e.value),t}case"divider":return y("div","qy-divider");case"image":{const t=_(e.src??e.url);if(!t)return null;const s=y("img","qy-node-img");return s.src=t,s.alt=p(e.alt),s}case"button":{const t=y("button","qy-card-btn");return t.type="button",t.textContent=p(e.label),t.addEventListener("click",()=>o(p(e.value??e.label))),t}case"actions":{const t=y("div","qy-actions");return c(t),t}case"list":{const t=y("ul","qy-list");for(const s of r){const a=$(s,o);if(a){const f=document.createElement("li");f.appendChild(a),t.appendChild(f)}}return t}case"row":{const t=y("div","qy-row");return c(t),t}case"column":{const t=y("div","qy-col");return c(t),t}case"input":{const t=document.createElement("input");t.className="qy-field qy-form-input",t.type="text",e.placeholder!=null&&(t.placeholder=p(e.placeholder)),t.setAttribute("data-qy-field",p(e.name));const s=e.required===!0;return s&&t.setAttribute("data-qy-required","true"),M(p(e.label),t,s)}case"datetime":{const t=document.createElement("input");t.className="qy-field qy-form-input",t.type="date",t.setAttribute("data-qy-field",p(e.name));const s=e.required===!0;return s&&t.setAttribute("data-qy-required","true"),M(p(e.label),t,s)}case"checkbox":{const t=document.createElement("input");t.className="qy-field",t.type="checkbox",t.setAttribute("data-qy-field",p(e.name));const s=e.required===!0;s&&t.setAttribute("data-qy-required","true");const a=y("label","qy-checkbox");a.appendChild(t);const f=document.createElement("span");return f.textContent=s?`${p(e.label??e.name)} *`:p(e.label??e.name),a.appendChild(f),a}case"select":{const t=document.createElement("select");t.className="qy-field qy-select",t.setAttribute("data-qy-field",p(e.name));const s=e.required===!0;s&&t.setAttribute("data-qy-required","true");const a=document.createElement("option");a.value="",t.appendChild(a);const f=Array.isArray(e.options)?e.options:[];for(const g of f){const i=document.createElement("option");i.value=p(g),i.textContent=p(g),t.appendChild(i)}return M(p(e.label),t,s)}case"form":{const t=y("div","qy-form");return c(t),t}case"submit":{const t=y("button","qy-form-submit");return t.type="button",t.textContent=p(e.label??"提交"),t.addEventListener("click",()=>{const s=t.closest(".qy-form");if(!s)return;const a=Array.from(s.querySelectorAll("[data-qy-field]"));let f=!1;for(const i of a){if(i.classList.remove("qy-field-invalid"),i.getAttribute("data-qy-required")!=="true")continue;let x=!1;i instanceof HTMLInputElement&&i.type==="checkbox"?x=!i.checked:(i instanceof HTMLInputElement||i instanceof HTMLSelectElement)&&(x=i.value.trim()===""),x&&(i.classList.add("qy-field-invalid"),f=!0)}if(f)return;const g=[];for(const i of a){const x=i.getAttribute("data-qy-field")??"";x&&(i instanceof HTMLInputElement&&i.type==="checkbox"?g.push(`${x}: ${i.checked?"是":"否"}`):(i instanceof HTMLInputElement||i instanceof HTMLSelectElement)&&g.push(`${x}: ${i.value}`))}o(g.join(`
`))}),t}default:return null}}function R(n,o){const e=document.createElement("div");document.body.appendChild(e);const r=e.attachShadow({mode:"open"}),c=document.createElement("style");c.textContent=W(n.themeColor||"#6366f1"),r.appendChild(c);const t=document.createElement("button");t.className="qy-bubble",t.textContent="💬",t.setAttribute("aria-label","打开客服");const s=document.createElement("div");s.className="qy-panel",s.innerHTML=`
    <div class="qy-header">
      <span>${P(n.appName||"在线客服")}</span>
      <button class="qy-handover" type="button">转人工</button>
    </div>
    <div class="qy-msgs"></div>
    <div class="qy-input">
      <textarea placeholder="输入消息…"></textarea>
      <button type="button">发送</button>
    </div>
  `,r.appendChild(t),r.appendChild(s);const a=s.querySelector(".qy-msgs"),f=s.querySelector("textarea"),g=s.querySelector(".qy-input button"),i=s.querySelector(".qy-handover");let x=!0;t.addEventListener("click",()=>{s.classList.toggle("open"),x&&s.classList.contains("open")&&(x=!1,n.welcomeMessage&&h(n.welcomeMessage))});const C=()=>{const l=f.value.trim();l&&(f.value="",o.onSend(l))};g.addEventListener("click",C),f.addEventListener("keydown",l=>{l.key==="Enter"&&!l.shiftKey&&(l.preventDefault(),C())}),f.addEventListener("input",()=>o.onTyping?.()),i.addEventListener("click",()=>o.onHandover());function k(l,m){const b=document.createElement("div");b.className=`qy-msg ${m}`;const T=document.createElement("div");return T.className="qy-text",T.textContent=l,b.appendChild(T),a.appendChild(b),a.scrollTop=a.scrollHeight,b}function d(l){const m=document.createElement("div");m.className="qy-msg bot";const b=document.createElement("div");b.className="qy-text";const T=o.onAction??o.onSend;for(const E of l)if(E.kind==="text"){const q=E.text??"";if(q){const w=document.createElement("div");w.className="qy-node-text",w.textContent=q,b.appendChild(w)}}else if(E.kind==="ui"&&E.node){const q=$(E.node,T);q&&b.appendChild(q)}return m.appendChild(b),a.appendChild(m),a.scrollTop=a.scrollHeight,m}function u(l){k(l,"user")}function h(l,m,b){const E=Array.isArray(b)&&b.some(q=>q.kind==="ui"&&q.node)?d(b):k(l,"bot");if(m){E.classList.add("has-feedback");const q=document.createElement("div");q.className="qy-feedback";const w=document.createElement("button");w.type="button",w.textContent="👍",w.setAttribute("aria-label","有帮助");const S=document.createElement("button");S.type="button",S.textContent="👎",S.setAttribute("aria-label","没帮助");const B=(Z,ee)=>{m.onFeedback(Z),w.disabled=!0,S.disabled=!0,ee.classList.add("active")};w.addEventListener("click",()=>B(1,w)),S.addEventListener("click",()=>B(-1,S)),q.appendChild(w),q.appendChild(S),E.appendChild(q),a.scrollTop=a.scrollHeight}}function v(l){const m=k(l,"bot"),b=document.createElement("div");b.textContent="客服",b.style.cssText="font-size:11px;color:#999;margin-bottom:2px;",m.insertBefore(b,m.firstChild)}function G(l){const m=document.createElement("div");m.style.cssText="text-align:center;color:#999;font-size:12px;margin:8px 0;",m.textContent=l,a.appendChild(m),a.scrollTop=a.scrollHeight}let A=null;function Y(l){l&&!A?A=k("输入中…","bot"):!l&&A&&(A.remove(),A=null)}function X(l){f.disabled=!l,g.disabled=!l}function V(){x=!1}return{addUserMessage:u,addBotMessage:h,addAgentMessage:v,showSessionState:G,setTyping:Y,setInputEnabled:X,markOpened:V}}function P(n){const o=document.createElement("div");return o.textContent=n,o.innerHTML}function D(n){let o="message";const e=[];for(const r of n.split(`
`))r===""||r.startsWith(":")||(r.startsWith("event:")?o=r.slice(6).trim():r.startsWith("data:")&&e.push(r.slice(5).replace(/^ /,"")));return{event:o,data:e.length?e.join(`
`):null}}function J(n,o,e){let r=!1,c=null,t=0;return(async()=>{for(;!r;){c=new AbortController;try{const a=await fetch(n,{headers:{"X-App-Id":o,Accept:"text/event-stream"},signal:c.signal});if(a.status===401||a.status===403){r=!0;return}if(!a.ok||!a.body)throw new Error("SSE "+a.status);t=0;const f=a.body.getReader(),g=new TextDecoder;let i="";for(;;){const{done:x,value:C}=await f.read();if(x||r)break;i+=g.decode(C,{stream:!0});let k;for(;(k=i.indexOf(`

`))>=0;){const d=i.slice(0,k);i=i.slice(k+2);const{event:u,data:h}=D(d);h!==null&&e(u,h)}}}catch{if(r)return}if(r)return;t+=1,await new Promise(a=>setTimeout(a,Math.min(15e3,500*2**Math.min(t,5))))}})(),{close:()=>{r=!0,c?.abort()}}}function Q(n,o){for(const e of o)switch(e.role){case"USER":n.addUserMessage(e.content);break;case"ASSISTANT":n.addBotMessage(e.content,void 0,e.uiParts);break;case"AGENT":n.addAgentMessage(e.content);break;case"SYSTEM":n.showSessionState(e.content);break}}function F(n){switch(n){case"accepted":return"客服已接入";case"returned_to_ai":return"已切回智能助手";case"ended":return"会话已结束";case"waiting":return"正在为您转接人工…";default:return""}}async function H(){const n=O();if(!n)return;const o=U(n);let e;try{e=await o.getConfig()}catch(d){console.warn("[Qingyu Widget] 拉取配置失败，停止渲染",d);return}let r=K(n.appKey),c=null,t=null;function s(d){t===d&&c||(c?.close(),t=d,c=J(o.inboxUrl(d),n.appKey,(u,h)=>{let v;try{v=JSON.parse(h)}catch{return}u==="message"&&(v.role==="ASSISTANT"||v.role==="AGENT")?i.addAgentMessage(v.content):u==="session"?i.showSessionState(v.text||F(v.kind)):u==="typing"&&v.from==="agent"&&i.setTyping(!0)}))}window.addEventListener("pagehide",()=>c?.close());let a=0;const f=3e3;function g(){if(!r)return;const d=Date.now();d-a<f||(a=d,o.sendTyping(r))}const i=R(e,{onSend:d=>{x(d)},onHandover:()=>{k()},onTyping:()=>g(),onAction:d=>{x(d)}});if(r){i.markOpened();const d=await o.getSession(r);d&&d.messages.length&&Q(i,d.messages),s(r)}async function x(d){i.addUserMessage(d),i.setInputEnabled(!1),i.setTyping(!0);try{const u=await C(d);s(u.sessionId),i.setTyping(!1);const h=u.messageId;i.addBotMessage(u.reply,h?{onFeedback:v=>{o.feedback(u.sessionId,h,v)}}:void 0,u.uiParts)}catch(u){i.setTyping(!1),i.addBotMessage("抱歉，服务暂时不可用，请稍后再试。"),console.warn("[Qingyu Widget] 发送失败",u)}finally{i.setInputEnabled(!0)}}async function C(d){try{const u=await o.send(d,r);return r=u.sessionId,I(n.appKey,r),u}catch(u){if(u instanceof N){z(n.appKey),r=null;const h=await o.send(d,null);return r=h.sessionId,I(n.appKey,r),h}throw u}}async function k(){if(!r){i.addBotMessage("请先发送消息开始会话，再申请转人工。");return}try{await o.handover(r),i.addBotMessage("已为你转接人工，请稍候。")}catch(d){console.warn("[Qingyu Widget] 转人工失败",d)}}}document.readyState==="loading"?document.addEventListener("DOMContentLoaded",()=>{H()}):H()})();
