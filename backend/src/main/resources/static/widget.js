(function(){"use strict";function O(){const n=document.currentScript??document.querySelector('script[src*="widget.js"]');if(!n||!n.src)return console.warn("[Qingyu Widget] 未找到加载脚本，无法初始化"),null;const r=new URL(n.src),t=r.searchParams.get("appKey");return t?{appKey:t,apiBase:r.origin+"/api"}:(console.warn("[Qingyu Widget] 缺少 appKey，无法初始化"),null)}function H(n){let r="message";const t=[];for(const a of n.split(`
`))a===""||a.startsWith(":")||(a.startsWith("event:")?r=a.slice(6).trim():a.startsWith("data:")&&t.push(a.slice(5).replace(/^ /,"")));return{event:r,data:t.length?t.join(`
`):null}}function U(n,r,t){let a=!1,u=null,e=0;return(async()=>{for(;!a;){u=new AbortController;try{const o=await fetch(n,{headers:{"X-App-Id":r,Accept:"text/event-stream"},signal:u.signal});if(o.status===401||o.status===403){a=!0;return}if(!o.ok||!o.body)throw new Error("SSE "+o.status);e=0;const m=o.body.getReader(),w=new TextDecoder;let i="";for(;;){const{done:g,value:S}=await m.read();if(g||a)break;i+=w.decode(S,{stream:!0});let E;for(;(E=i.indexOf(`

`))>=0;){const l=i.slice(0,E);i=i.slice(E+2);const{event:f,data:q}=H(l);q!==null&&t(f,q)}}}catch{if(a)return}if(a)return;e+=1,await new Promise(o=>setTimeout(o,Math.min(15e3,500*2**Math.min(e,5))))}})(),{close:()=>{a=!0,u?.abort()}}}function j(n){return n===429?"请求过于频繁，请稍后再试。":"抱歉，服务暂时不可用，请稍后再试。"}async function D(n,r,t,a,u){const e=await fetch(n,{method:"POST",headers:{...r,Accept:"text/event-stream"},body:JSON.stringify(t),signal:u});if(e.status===404){const g=new Error("会话不存在或已过期");throw g.name="SessionExpiredError",g}if(!e.ok||!e.body){a.onError?.(j(e.status));return}let s=!1,o=!1;const m=e.body.getReader(),w=new TextDecoder;let i="";for(;;){const{done:g,value:S}=await m.read();if(g)break;i+=w.decode(S,{stream:!0});let E;for(;(E=i.indexOf(`

`))>=0;){const l=i.slice(0,E);i=i.slice(E+2);const{event:f,data:q}=H(l);if(q===null)continue;let c;try{c=JSON.parse(q)}catch{continue}if(f==="start")a.onStart?.(c);else if(f==="delta")a.onDelta?.(c.text??"");else if(f==="card"){const C=c.node;C&&a.onCard?.(C)}else f==="done"?(s=!0,a.onDone?.(c.messageId??null)):f==="error"&&(o=!0,a.onError?.(c.message||"回复生成失败。"))}}!s&&!o&&a.onError?.("回复中断，请重试。")}class I extends Error{constructor(){super("会话不存在或已过期"),this.name="SessionExpiredError"}}function K(n){const r=()=>({"Content-Type":"application/json","X-App-Id":n.appKey});return{async getConfig(){const t=await fetch(`${n.apiBase}/open/v1/widget/config`,{headers:r()});if(!t.ok)throw new Error(`widget config 请求失败: ${t.status}`);return await t.json()},async stream(t,a,u,e){const s=a?{message:t,sessionId:a}:{message:t};try{await D(`${n.apiBase}/open/v1/chat/stream`,r(),s,u,e)}catch(o){throw o instanceof Error&&o.name==="SessionExpiredError"?new I:o}},async handover(t){await fetch(`${n.apiBase}/open/v1/sessions/${t}/handover`,{method:"POST",headers:r()})},async feedback(t,a,u){try{await fetch(`${n.apiBase}/open/v1/chat/feedback`,{method:"POST",headers:r(),body:JSON.stringify({sessionId:t,messageId:a,feedback:u})})}catch{}},inboxUrl(t){return`${n.apiBase}/open/v1/sessions/${t}/inbox`},sendTyping(t){return fetch(`${n.apiBase}/open/v1/sessions/${t}/typing`,{method:"POST",headers:r()}).then(()=>{}).catch(()=>{})},async getSession(t){try{const a=await fetch(`${n.apiBase}/open/v1/sessions/${t}`,{headers:r()});return a.ok?await a.json():null}catch{return null}}}}const R=1440*60*1e3,N=n=>`qingyu_widget_session_${n}`;function W(n){try{const r=localStorage.getItem(N(n));if(!r)return null;const t=JSON.parse(r);return!t||typeof t.sessionId!="string"||typeof t.createdAt!="number"||Date.now()-t.createdAt>R?(z(n),null):t.sessionId}catch{return null}}function _(n,r){try{const t={sessionId:r,createdAt:Date.now()};localStorage.setItem(N(n),JSON.stringify(t))}catch{}}function z(n){try{localStorage.removeItem(N(n))}catch{}}function J(n){return`
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
  `}function P(n){if(typeof n!="string"||n.length>2048)return"";try{const r=new URL(n);return r.protocol==="http:"||r.protocol==="https:"?n:""}catch{return""}}function x(n){return n==null?"":String(n)}function h(n,r){const t=document.createElement(n);return r&&(t.className=r),t}function L(n,r,t){if(!n)return r;const a=document.createElement("div");a.className="qy-field-row";const u=document.createElement("div");return u.className="qy-field-label",u.textContent=t?`${n} *`:n,a.appendChild(u),a.appendChild(r),a}function M(n,r){if(!n||typeof n!="object")return null;const t=n.props??{},a=Array.isArray(n.children)?n.children:[],u=e=>{for(const s of a){const o=M(s,r);o&&e.appendChild(o)}};switch(n.type){case"card":{const e=h("div","qy-card");if(t.title!=null&&x(t.title)!==""){const o=h("div","qy-card-title");o.textContent=x(t.title),e.appendChild(o)}const s=h("div","qy-card-body");return u(s),e.appendChild(s),e}case"text":{const e=h("div",t.emphasis?"qy-node-text qy-emph":"qy-node-text");return e.textContent=x(t.value??t.text),e}case"kv":{const e=h("div","qy-kv"),s=h("span","qy-kv-label");s.textContent=x(t.label);const o=h("span","qy-kv-value");return o.textContent=x(t.value),e.appendChild(s),e.appendChild(o),e}case"badge":{const e=h("span","qy-badge");return e.textContent=x(t.text??t.value),e}case"divider":return h("div","qy-divider");case"image":{const e=P(t.src??t.url);if(!e)return null;const s=h("img","qy-node-img");return s.src=e,s.alt=x(t.alt),s}case"button":{const e=h("button","qy-card-btn");return e.type="button",e.textContent=x(t.label),e.addEventListener("click",()=>r(x(t.value??t.label))),e}case"actions":{const e=h("div","qy-actions");return u(e),e}case"list":{const e=h("ul","qy-list");for(const s of a){const o=M(s,r);if(o){const m=document.createElement("li");m.appendChild(o),e.appendChild(m)}}return e}case"row":{const e=h("div","qy-row");return u(e),e}case"column":{const e=h("div","qy-col");return u(e),e}case"input":{const e=document.createElement("input");e.className="qy-field qy-form-input",e.type="text",t.placeholder!=null&&(e.placeholder=x(t.placeholder)),e.setAttribute("data-qy-field",x(t.name));const s=t.required===!0;return s&&e.setAttribute("data-qy-required","true"),L(x(t.label),e,s)}case"datetime":{const e=document.createElement("input");e.className="qy-field qy-form-input",e.type="date",e.setAttribute("data-qy-field",x(t.name));const s=t.required===!0;return s&&e.setAttribute("data-qy-required","true"),L(x(t.label),e,s)}case"checkbox":{const e=document.createElement("input");e.className="qy-field",e.type="checkbox",e.setAttribute("data-qy-field",x(t.name));const s=t.required===!0;s&&e.setAttribute("data-qy-required","true");const o=h("label","qy-checkbox");o.appendChild(e);const m=document.createElement("span");return m.textContent=s?`${x(t.label??t.name)} *`:x(t.label??t.name),o.appendChild(m),o}case"select":{const e=document.createElement("select");e.className="qy-field qy-select",e.setAttribute("data-qy-field",x(t.name));const s=t.required===!0;s&&e.setAttribute("data-qy-required","true");const o=document.createElement("option");o.value="",e.appendChild(o);const m=Array.isArray(t.options)?t.options:[];for(const w of m){const i=document.createElement("option");i.value=x(w),i.textContent=x(w),e.appendChild(i)}return L(x(t.label),e,s)}case"form":{const e=h("div","qy-form");return u(e),e}case"submit":{const e=h("button","qy-form-submit");return e.type="button",e.textContent=x(t.label??"提交"),e.addEventListener("click",()=>{const s=e.closest(".qy-form");if(!s)return;const o=Array.from(s.querySelectorAll("[data-qy-field]"));let m=!1;for(const i of o){if(i.classList.remove("qy-field-invalid"),i.getAttribute("data-qy-required")!=="true")continue;let g=!1;i instanceof HTMLInputElement&&i.type==="checkbox"?g=!i.checked:(i instanceof HTMLInputElement||i instanceof HTMLSelectElement)&&(g=i.value.trim()===""),g&&(i.classList.add("qy-field-invalid"),m=!0)}if(m)return;const w=[];for(const i of o){const g=i.getAttribute("data-qy-field")??"";g&&(i instanceof HTMLInputElement&&i.type==="checkbox"?w.push(`${g}: ${i.checked?"是":"否"}`):(i instanceof HTMLInputElement||i instanceof HTMLSelectElement)&&w.push(`${g}: ${i.value}`))}r(w.join(`
`))}),e}default:return null}}function Q(n,r){const t=document.createElement("div");document.body.appendChild(t);const a=t.attachShadow({mode:"open"}),u=document.createElement("style");u.textContent=J(n.themeColor||"#6366f1"),a.appendChild(u);const e=document.createElement("button");e.className="qy-bubble",e.textContent="💬",e.setAttribute("aria-label","打开客服");const s=document.createElement("div");s.className="qy-panel",s.innerHTML=`
    <div class="qy-header">
      <span>${F(n.appName||"在线客服")}</span>
      <button class="qy-handover" type="button">转人工</button>
    </div>
    <div class="qy-msgs"></div>
    <div class="qy-input">
      <textarea placeholder="输入消息…"></textarea>
      <button type="button">发送</button>
    </div>
  `,a.appendChild(e),a.appendChild(s);const o=s.querySelector(".qy-msgs"),m=s.querySelector("textarea"),w=s.querySelector(".qy-input button"),i=s.querySelector(".qy-handover");let g=!0;e.addEventListener("click",()=>{s.classList.toggle("open"),g&&s.classList.contains("open")&&(g=!1,n.welcomeMessage&&C(n.welcomeMessage))});const S=()=>{const d=m.value.trim();d&&(m.value="",r.onSend(d))};w.addEventListener("click",S),m.addEventListener("keydown",d=>{d.key==="Enter"&&!d.shiftKey&&(d.preventDefault(),S())}),m.addEventListener("input",()=>r.onTyping?.()),i.addEventListener("click",()=>r.onHandover());function E(d,y){const p=document.createElement("div");p.className=`qy-msg ${y}`;const v=document.createElement("div");return v.className="qy-text",v.textContent=d,p.appendChild(v),o.appendChild(p),o.scrollTop=o.scrollHeight,p}function l(d){const y=document.createElement("div");y.className="qy-msg bot";const p=document.createElement("div");p.className="qy-text";const v=r.onAction??r.onSend;for(const b of d)if(b.kind==="text"){const k=b.text??"";if(k){const A=document.createElement("div");A.className="qy-node-text",A.textContent=k,p.appendChild(A)}}else if(b.kind==="ui"&&b.node){const k=M(b.node,v);k&&p.appendChild(k)}return y.appendChild(p),o.appendChild(y),o.scrollTop=o.scrollHeight,y}function f(d,y){d.classList.add("has-feedback");const p=document.createElement("div");p.className="qy-feedback";const v=document.createElement("button");v.type="button",v.textContent="👍",v.setAttribute("aria-label","有帮助");const b=document.createElement("button");b.type="button",b.textContent="👎",b.setAttribute("aria-label","没帮助");const k=(A,te)=>{y.onFeedback(A),v.disabled=!0,b.disabled=!0,te.classList.add("active")};v.addEventListener("click",()=>k(1,v)),b.addEventListener("click",()=>k(-1,b)),p.appendChild(v),p.appendChild(b),d.appendChild(p),o.scrollTop=o.scrollHeight}function q(){const d=document.createElement("div");d.className="qy-msg bot";const y=document.createElement("div");y.className="qy-text",d.appendChild(y),o.appendChild(d),o.scrollTop=o.scrollHeight;let p=null;const v=r.onAction??r.onSend;return{appendText(b){b&&(p||(p=document.createElement("div"),p.className="qy-node-text",y.appendChild(p)),p.textContent=(p.textContent??"")+b,o.scrollTop=o.scrollHeight)},appendCard(b){const k=M(b,v);k&&y.appendChild(k),p=null,o.scrollTop=o.scrollHeight},finish(b){b&&f(d,b)}}}function c(d){E(d,"user")}function C(d,y,p){const b=Array.isArray(p)&&p.some(k=>k.kind==="ui"&&k.node)?l(p):E(d,"bot");y&&f(b,y)}function $(d){const y=E(d,"bot"),p=document.createElement("div");p.textContent="客服",p.style.cssText="font-size:11px;color:#999;margin-bottom:2px;",y.insertBefore(p,y.firstChild)}function X(d){const y=document.createElement("div");y.style.cssText="text-align:center;color:#999;font-size:12px;margin:8px 0;",y.textContent=d,o.appendChild(y),o.scrollTop=o.scrollHeight}let T=null;function V(d){d&&!T?T=E("输入中…","bot"):!d&&T&&(T.remove(),T=null)}function Z(d){m.disabled=!d,w.disabled=!d}function ee(){g=!1}return{addUserMessage:c,addBotMessage:C,addAgentMessage:$,showSessionState:X,setTyping:V,setInputEnabled:Z,markOpened:ee,beginBotMessage:q}}function F(n){const r=document.createElement("div");return r.textContent=n,r.innerHTML}function G(n,r){for(const t of r)switch(t.role){case"USER":n.addUserMessage(t.content);break;case"ASSISTANT":n.addBotMessage(t.content,void 0,t.uiParts);break;case"AGENT":n.addAgentMessage(t.content);break;case"SYSTEM":n.showSessionState(t.content);break}}function Y(n){switch(n){case"accepted":return"客服已接入";case"returned_to_ai":return"已切回智能助手";case"ended":return"会话已结束";case"waiting":return"正在为您转接人工…";default:return""}}async function B(){const n=O();if(!n)return;const r=K(n);let t;try{t=await r.getConfig()}catch(l){console.warn("[Qingyu Widget] 拉取配置失败，停止渲染",l);return}let a=W(n.appKey),u=null,e=null;function s(l){e===l&&u||(u?.close(),e=l,u=U(r.inboxUrl(l),n.appKey,(f,q)=>{let c;try{c=JSON.parse(q)}catch{return}f==="message"&&(c.role==="ASSISTANT"||c.role==="AGENT")?i.addAgentMessage(c.content):f==="session"?i.showSessionState(c.text||Y(c.kind)):f==="typing"&&c.from==="agent"&&i.setTyping(!0)}))}window.addEventListener("pagehide",()=>u?.close());let o=0;const m=3e3;function w(){if(!a)return;const l=Date.now();l-o<m||(o=l,r.sendTyping(a))}const i=Q(t,{onSend:l=>{g(l)},onHandover:()=>{E()},onTyping:()=>w(),onAction:l=>{g(l)}});if(a){i.markOpened();const l=await r.getSession(a);l&&l.messages.length&&G(i,l.messages),s(a)}async function g(l){i.addUserMessage(l),i.setInputEnabled(!1),i.setTyping(!0);let f=null;const q=()=>(f||(i.setTyping(!1),f=i.beginBotMessage()),f);try{await S(l,{onStart:c=>{a=c.sessionId,_(n.appKey,c.sessionId),s(c.sessionId)},onDelta:c=>q().appendText(c),onCard:c=>q().appendCard(c),onDone:c=>{const C=a;q().finish(c!=null&&c>0&&C?{onFeedback:$=>{r.feedback(C,c,$)}}:void 0)},onError:c=>q().appendText(`
${c}`)})}catch(c){f?f.appendText(`
回复中断，请重试。`):i.addBotMessage("抱歉，服务暂时不可用，请稍后再试。"),console.warn("[Qingyu Widget] 发送失败",c)}finally{i.setTyping(!1),i.setInputEnabled(!0)}}async function S(l,f){try{await r.stream(l,a,f)}catch(q){if(q instanceof I){z(n.appKey),a=null,await r.stream(l,null,f);return}throw q}}async function E(){if(!a){i.addBotMessage("请先发送消息开始会话，再申请转人工。");return}try{await r.handover(a),i.addBotMessage("已为你转接人工，请稍候。")}catch(l){console.warn("[Qingyu Widget] 转人工失败",l)}}}document.readyState==="loading"?document.addEventListener("DOMContentLoaded",()=>{B()}):B()})();
