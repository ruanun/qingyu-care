/*! qingyu-widget src-hash: b84b5611f1d603e356c0b836bcb93c4619c49aa40992b1119363d64277feb6f1 */
(function(){"use strict";function F(){const t=document.currentScript??document.querySelector('script[src*="widget.js"]');if(!t||!t.src)return console.warn("[Qingyu Widget] 未找到加载脚本，无法初始化"),null;const a=new URL(t.src),e=a.searchParams.get("appKey");return e?{appKey:e,apiBase:a.origin+"/api"}:(console.warn("[Qingyu Widget] 缺少 appKey，无法初始化"),null)}function z(t){let a="message";const e=[];for(const o of t.split(`
`))o===""||o.startsWith(":")||(o.startsWith("event:")?a=o.slice(6).trim():o.startsWith("data:")&&e.push(o.slice(5).replace(/^ /,"")));return{event:a,data:e.length?e.join(`
`):null}}function K(t,a,e){let o=!1,d=null,n=0;return(async()=>{for(;!o;){d=new AbortController;try{const r=await fetch(t,{headers:{"X-App-Id":a,Accept:"text/event-stream"},signal:d.signal});if(r.status===401||r.status===403){o=!0;return}if(!r.ok||!r.body)throw new Error("SSE "+r.status);n=0;const f=r.body.getReader(),k=new TextDecoder;let s="";for(;;){const{done:q,value:S}=await f.read();if(q||o)break;s+=k.decode(S,{stream:!0});let C;for(;(C=s.indexOf(`

`))>=0;){const l=s.slice(0,C);s=s.slice(C+2);const{event:m,data:v}=z(l);v!==null&&e(m,v)}}}catch{if(o)return}if(o)return;n+=1,await new Promise(r=>setTimeout(r,Math.min(15e3,500*2**Math.min(n,5))))}})(),{close:()=>{o=!0,d?.abort()}}}function P(t){return t===429?"请求过于频繁，请稍后再试。":"抱歉，服务暂时不可用，请稍后再试。"}async function W(t,a,e,o,d){const n=await fetch(t,{method:"POST",headers:{...a,Accept:"text/event-stream"},body:JSON.stringify(e),signal:d});if(n.status===404){const q=new Error("会话不存在或已过期");throw q.name="SessionExpiredError",q}if(!n.ok||!n.body){o.onError?.(P(n.status));return}let i=!1,r=!1;const f=n.body.getReader(),k=new TextDecoder;let s="";for(;;){const{done:q,value:S}=await f.read();if(q)break;s+=k.decode(S,{stream:!0});let C;for(;(C=s.indexOf(`

`))>=0;){const l=s.slice(0,C);s=s.slice(C+2);const{event:m,data:v}=z(l);if(v===null)continue;let h;try{h=JSON.parse(v)}catch{continue}if(m==="start")o.onStart?.(h);else if(m==="delta")o.onDelta?.(h.text??"");else if(m==="card"){const x=h.node;x&&o.onCard?.(x)}else m==="done"?(i=!0,o.onDone?.(h.messageId??null)):m==="error"&&(r=!0,o.onError?.(h.message||"回复生成失败。"))}}!i&&!r&&o.onError?.("回复中断，请重试。")}class B extends Error{constructor(){super("会话不存在或已过期"),this.name="SessionExpiredError"}}function V(t){const a=()=>({"Content-Type":"application/json","X-App-Id":t.appKey});return{async getConfig(){const e=await fetch(`${t.apiBase}/open/v1/widget/config`,{headers:a()});if(!e.ok)throw new Error(`widget config 请求失败: ${e.status}`);return await e.json()},async stream(e,o,d,n,i){const r={message:e};o&&(r.sessionId=o),n&&n.length&&(r.attachmentIds=n);try{await W(`${t.apiBase}/open/v1/chat/stream`,a(),r,d,i)}catch(f){throw f instanceof Error&&f.name==="SessionExpiredError"?new B:f}},async uploadAttachment(e,o){const d=new FormData;d.append("sessionId",e),d.append("file",o);const n=await fetch(`${t.apiBase}/open/v1/chat/files`,{method:"POST",headers:{"X-App-Id":t.appKey},body:d});if(!n.ok)throw new Error(`附件上传失败: ${n.status}`);return await n.json()},async downloadAttachment(e,o){const d=`${t.apiBase}/open/v1/chat/files/${e}?sessionId=${encodeURIComponent(o)}`,n=await fetch(d,{headers:{"X-App-Id":t.appKey}});if(!n.ok)throw new Error(`附件下载失败: ${n.status}`);return await n.blob()},async handover(e){await fetch(`${t.apiBase}/open/v1/sessions/${e}/handover`,{method:"POST",headers:a()})},async feedback(e,o,d){try{await fetch(`${t.apiBase}/open/v1/chat/feedback`,{method:"POST",headers:a(),body:JSON.stringify({sessionId:e,messageId:o,feedback:d})})}catch{}},inboxUrl(e){return`${t.apiBase}/open/v1/sessions/${e}/inbox`},sendTyping(e){return fetch(`${t.apiBase}/open/v1/sessions/${e}/typing`,{method:"POST",headers:a()}).then(()=>{}).catch(()=>{})},async getSession(e){try{const o=await fetch(`${t.apiBase}/open/v1/sessions/${e}`,{headers:a()});return o.ok?await o.json():null}catch{return null}}}}const Q=1440*60*1e3,$=t=>`qingyu_widget_session_${t}`;function G(t){try{const a=localStorage.getItem($(t));if(!a)return null;const e=JSON.parse(a);return!e||typeof e.sessionId!="string"||typeof e.createdAt!="number"||Date.now()-e.createdAt>Q?(H(t),null):e.sessionId}catch{return null}}function J(t,a){try{const e={sessionId:a,createdAt:Date.now()};localStorage.setItem($(t),JSON.stringify(e))}catch{}}function H(t){try{localStorage.removeItem($(t))}catch{}}function X(t){return`
    :host { all: initial; }
    * { box-sizing: border-box; font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
    /* ⚠️ color 不是装饰：球内 logo 用 fill="currentColor"，靠这行取到白色，
       而 logo 挖空的笔画处透出的正是 background 的 themeColor。改这两行前先看 logo.ts。 */
    .qy-bubble {
      position: fixed; right: 20px; bottom: 20px; width: 56px; height: 56px;
      border-radius: 50%; background: ${t}; color: #fff; border: none;
      cursor: pointer; box-shadow: 0 4px 12px rgba(0,0,0,.2); z-index: 2147483000;
      display: flex; align-items: center; justify-content: center; padding: 0;
    }
    .qy-panel {
      position: fixed; right: 20px; bottom: 88px; width: 360px; height: 520px;
      background: #fff; border-radius: 12px; box-shadow: 0 8px 30px rgba(0,0,0,.18);
      display: none; flex-direction: column; overflow: hidden; z-index: 2147483000;
    }
    .qy-panel.open { display: flex; }
    .qy-header {
      background: ${t}; color: #fff; padding: 12px 16px;
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
    .qy-msg.user .qy-text { background: ${t}; color: #fff; }
    .qy-input { display: flex; border-top: 1px solid #eee; }
    .qy-input textarea {
      flex: 1; border: none; resize: none; padding: 12px; font-size: 14px; outline: none; height: 48px;
    }
    .qy-input button {
      border: none; background: ${t}; color: #fff; padding: 0 16px; cursor: pointer;
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
    .qy-card-btn { margin:4px 6px 0 0; padding:4px 10px; border:1px solid ${t}; color:${t}; background:#fff; border-radius:6px; cursor:pointer; font-size:13px; }
    .qy-card-btn:hover { background:${t}; color:#fff; }
    .qy-card-btn-primary { background:${t}; color:#fff; }
    .qy-card-btn-danger { border-color:#dc2626; color:#dc2626; }
    .qy-card-btn-danger:hover { background:#dc2626; color:#fff; }
    .qy-actions { display:flex; flex-wrap:wrap; }
    .qy-list { margin:4px 0; padding-left:18px; }
    .qy-row { display:flex; gap:8px; }
    .qy-col { display:flex; flex-direction:column; gap:4px; }
    .qy-form { display:flex; flex-direction:column; gap:8px; padding:8px 0; }
    .qy-field-row { display:flex; flex-direction:column; gap:2px; }
    .qy-field-label { font-size:12px; color:#6b7280; }
    .qy-form-input, .qy-select { padding:6px 8px; border:1px solid #d1d5db; border-radius:6px; font-size:13px; }
    .qy-checkbox { display:flex; align-items:center; gap:6px; font-size:13px; }
    .qy-form-submit { align-self:flex-start; padding:6px 14px; border:none; border-radius:6px; background:${t}; color:#fff; cursor:pointer; font-size:13px; }
    .qy-field-invalid { border-color:#ef4444; }
    .qy-attach-btn { border:none; background:transparent; padding:0 8px; cursor:pointer; font-size:18px; line-height:48px; }
    .qy-attach-btn:disabled { opacity:.35; cursor:not-allowed; }
    .qy-attach-preview { display:flex; flex-wrap:wrap; gap:6px; padding:0 12px; }
    .qy-attach-preview:not(:empty) { padding-top:8px; }
    .qy-attach-chip { display:flex; align-items:center; gap:4px; max-width:160px; padding:3px 6px; border:1px solid #e5e7eb; border-radius:6px; background:#f7f7f8; font-size:12px; }
    .qy-attach-chip-name { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .qy-attach-chip-remove { border:none; background:none; cursor:pointer; color:#999; font-size:14px; line-height:1; padding:0; }
    .qy-attach-chip-remove:hover { color:#111827; }
    .qy-attachments { display:flex; flex-wrap:wrap; gap:6px; margin-top:6px; }
    .qy-attach-img { display:block; max-width:200px; max-height:200px; border-radius:8px; object-fit:cover; background:#f3f4f6; }
    .qy-attach-file { display:flex; align-items:center; gap:6px; max-width:220px; padding:6px 10px; border:1px solid #e5e7eb; border-radius:8px; background:#fff; text-decoration:none; color:inherit; font-size:13px; }
    .qy-attach-name { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .qy-attach-size { color:#9ca3af; font-size:11px; white-space:nowrap; }
  `}const Y=`<svg viewBox="0 0 24 24" width="28" height="28" aria-hidden="true" focusable="false">
  <defs>
    <mask id="qy-bubble-logo-mask">
      <path d="M5 3h14a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H9.5L5 20.8V17a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3z" fill="#fff"/>
      <g stroke="#000" stroke-width="1.4" stroke-linecap="round">
        <path d="M8 7.4V13.4"/>
        <path d="M6.3 9.4H9.7"/>
        <path d="M6.3 11.9H9.7"/>
        <path d="M12.4 8.6H17.6"/>
        <path d="M15 8.6V12.6"/>
        <path d="M12.4 12.6H17.6"/>
      </g>
    </mask>
  </defs>
  <rect width="24" height="24" fill="currentColor" mask="url(#qy-bubble-logo-mask)"/>
</svg>`;function O(t){if(typeof t!="string"||t.length>2048)return"";try{const a=new URL(t);return a.protocol==="http:"||a.protocol==="https:"?t:""}catch{return""}}const Z=["url","msg","copy"],ee=/^([a-z]+):\/\/(.*)$/i;function te(t){if(typeof t!="string"||!t)return{protocol:"",content:"",isValid:!1};const a=t.match(ee);if(!a)return{protocol:"",content:"",isValid:!1};const e=a[1].toLowerCase();let o=a[2]??"";try{o=decodeURIComponent(o)}catch{}return{protocol:e,content:o,isValid:Z.includes(e)}}function ne(t,a){const e=te(t);if(!e.isValid)return!1;if(!e.content)return!0;switch(e.protocol){case"url":{const o=O(e.content);return o&&window.open(o,"_blank","noopener,noreferrer"),!0}case"msg":return a.onMessage(e.content),!0;case"copy":return navigator.clipboard?.writeText?(navigator.clipboard.writeText(e.content).then(()=>a.onCopied?.()).catch(o=>{console.warn("[Qingyu Widget] 复制失败",o),a.onCopyFailed?.()}),!0):(a.onCopyFailed?.(),!0);default:return!1}}function g(t){return t==null?"":String(t)}function w(t,a){const e=document.createElement(t);return a&&(e.className=a),e}function oe(t){return t==="primary"?" qy-card-btn-primary":t==="danger"?" qy-card-btn-danger":""}function U(t,a,e){t.textContent=a,setTimeout(()=>{t.textContent=e},2e3)}function I(t,a,e){if(!t)return a;const o=document.createElement("div");o.className="qy-field-row";const d=document.createElement("div");return d.className="qy-field-label",d.textContent=e?`${t} *`:t,o.appendChild(d),o.appendChild(a),o}function M(t,a){if(!t||typeof t!="object")return null;const e=t.props??{},o=Array.isArray(t.children)?t.children:[],d=n=>{for(const i of o){const r=M(i,a);r&&n.appendChild(r)}};switch(t.type){case"card":{const n=w("div","qy-card");if(e.title!=null&&g(e.title)!==""){const r=w("div","qy-card-title");r.textContent=g(e.title),n.appendChild(r)}const i=w("div","qy-card-body");return d(i),n.appendChild(i),n}case"text":{const n=w("div",e.emphasis?"qy-node-text qy-emph":"qy-node-text");return n.textContent=g(e.value??e.text),n}case"kv":{const n=w("div","qy-kv"),i=w("span","qy-kv-label");i.textContent=g(e.label);const r=w("span","qy-kv-value");return r.textContent=g(e.value),n.appendChild(i),n.appendChild(r),n}case"badge":{const n=w("span","qy-badge");return n.textContent=g(e.text??e.value),n}case"divider":return w("div","qy-divider");case"image":{const n=O(e.src??e.url);if(!n)return null;const i=w("img","qy-node-img");return i.src=n,i.alt=g(e.alt),i}case"button":{const n=w("button","qy-card-btn"+oe(e.style));n.type="button";const i=g(e.label);return n.textContent=i,n.addEventListener("click",()=>{if(!ne(e.action,{onMessage:a,onCopied:()=>U(n,"已复制",i),onCopyFailed:()=>U(n,"复制失败",i)})){const f=g(e.value??e.label);f&&a(f)}}),n}case"actions":{const n=w("div","qy-actions");return d(n),n}case"list":{const n=w("ul","qy-list");for(const i of o){const r=M(i,a);if(r){const f=document.createElement("li");f.appendChild(r),n.appendChild(f)}}return n}case"row":{const n=w("div","qy-row");return d(n),n}case"column":{const n=w("div","qy-col");return d(n),n}case"input":{const n=document.createElement("input");n.className="qy-field qy-form-input",n.type="text",e.placeholder!=null&&(n.placeholder=g(e.placeholder)),n.setAttribute("data-qy-field",g(e.name));const i=e.required===!0;return i&&n.setAttribute("data-qy-required","true"),I(g(e.label),n,i)}case"datetime":{const n=document.createElement("input");n.className="qy-field qy-form-input",n.type="date",n.setAttribute("data-qy-field",g(e.name));const i=e.required===!0;return i&&n.setAttribute("data-qy-required","true"),I(g(e.label),n,i)}case"checkbox":{const n=document.createElement("input");n.className="qy-field",n.type="checkbox",n.setAttribute("data-qy-field",g(e.name));const i=e.required===!0;i&&n.setAttribute("data-qy-required","true");const r=w("label","qy-checkbox");r.appendChild(n);const f=document.createElement("span");return f.textContent=i?`${g(e.label??e.name)} *`:g(e.label??e.name),r.appendChild(f),r}case"select":{const n=document.createElement("select");n.className="qy-field qy-select",n.setAttribute("data-qy-field",g(e.name));const i=e.required===!0;i&&n.setAttribute("data-qy-required","true");const r=document.createElement("option");r.value="",n.appendChild(r);const f=Array.isArray(e.options)?e.options:[];for(const k of f){const s=document.createElement("option");s.value=g(k),s.textContent=g(k),n.appendChild(s)}return I(g(e.label),n,i)}case"form":{const n=w("div","qy-form");return d(n),n}case"submit":{const n=w("button","qy-form-submit");return n.type="button",n.textContent=g(e.label??"提交"),n.addEventListener("click",()=>{const i=n.closest(".qy-form");if(!i)return;const r=Array.from(i.querySelectorAll("[data-qy-field]"));let f=!1;for(const s of r){if(s.classList.remove("qy-field-invalid"),s.getAttribute("data-qy-required")!=="true")continue;let q=!1;s instanceof HTMLInputElement&&s.type==="checkbox"?q=!s.checked:(s instanceof HTMLInputElement||s instanceof HTMLSelectElement)&&(q=s.value.trim()===""),q&&(s.classList.add("qy-field-invalid"),f=!0)}if(f)return;const k=[];for(const s of r){const q=s.getAttribute("data-qy-field")??"";q&&(s instanceof HTMLInputElement&&s.type==="checkbox"?k.push(`${q}: ${s.checked?"是":"否"}`):(s instanceof HTMLInputElement||s instanceof HTMLSelectElement)&&k.push(`${q}: ${s.value}`))}a(k.join(`
`))}),n}default:return null}}function ae(t){return t<1024?`${t} B`:t<1024*1024?`${(t/1024).toFixed(1)} KB`:`${(t/1024/1024).toFixed(1)} MB`}function re(t,a){if(!t||t.length===0)return null;const e=document.createElement("div");e.className="qy-attachments";for(const o of t)e.appendChild(o.contentType.startsWith("image/")?ie(o,a):se(o,a));return e}function ie(t,a){const e=document.createElement("a");e.className="qy-attach-img-link",e.target="_blank",e.rel="noopener noreferrer",e.href="#";const o=document.createElement("img");if(o.className="qy-attach-img",o.alt=t.fileName,t.width&&o.setAttribute("width",String(t.width)),t.height&&o.setAttribute("height",String(t.height)),e.appendChild(o),a){let d=!1;e.addEventListener("click",n=>{d||n.preventDefault()}),a(t.id).then(n=>{d=!0,o.src=n,e.href=n}).catch(()=>{o.alt=`${t.fileName}（加载失败）`})}return e}function se(t,a){const e=document.createElement("a");e.className="qy-attach-file",e.href="#";const o=document.createElement("span");o.className="qy-attach-name",o.textContent=t.fileName;const d=document.createElement("span");return d.className="qy-attach-size",d.textContent=ae(t.fileSize),e.appendChild(o),e.appendChild(d),a&&e.addEventListener("click",n=>{n.preventDefault(),a(t.id).then(i=>{const r=document.createElement("a");r.href=i,r.download=t.fileName,r.click(),URL.revokeObjectURL(i)}).catch(()=>{})}),e}function ce(t,a){const e=document.createElement("div");document.body.appendChild(e);const o=e.attachShadow({mode:"open"}),d=document.createElement("style");d.textContent=X(t.themeColor||"#6366f1"),o.appendChild(d);const n=document.createElement("button");n.className="qy-bubble",n.innerHTML=Y,n.setAttribute("aria-label","打开客服");const i=document.createElement("div");i.className="qy-panel",i.innerHTML=`
    <div class="qy-header">
      <span>${de(t.appName||"在线客服")}</span>
      <button class="qy-handover" type="button">转人工</button>
    </div>
    <div class="qy-msgs"></div>
    <div class="qy-attach-preview"></div>
    <div class="qy-input">
      <button type="button" class="qy-attach-btn" aria-label="添加附件" disabled>📎</button>
      <input type="file" class="qy-attach-file-input" hidden />
      <textarea placeholder="输入消息…"></textarea>
      <button type="button">发送</button>
    </div>
  `,o.appendChild(n),o.appendChild(i);const r=i.querySelector(".qy-msgs"),f=i.querySelector("textarea"),k=i.querySelector(".qy-input button:not(.qy-attach-btn)"),s=i.querySelector(".qy-handover"),q=i.querySelector(".qy-attach-btn"),S=i.querySelector(".qy-attach-file-input"),C=i.querySelector(".qy-attach-preview");let l=[],m=!1;function v(){C.replaceChildren();for(const c of l){const u=document.createElement("div");u.className="qy-attach-chip";const p=document.createElement("span");p.className="qy-attach-chip-name",p.textContent=c.fileName;const y=document.createElement("button");y.type="button",y.className="qy-attach-chip-remove",y.textContent="×",y.setAttribute("aria-label",`移除附件 ${c.fileName}`),y.addEventListener("click",()=>{l=l.filter(b=>b.id!==c.id),v()}),u.appendChild(p),u.appendChild(y),C.appendChild(u)}}q.addEventListener("click",()=>S.click()),S.addEventListener("change",()=>{const c=S.files?.[0];S.value="",!(!c||!a.onUploadFile)&&(q.disabled=!0,a.onUploadFile(c).then(u=>{l.push(u),v()}).catch(u=>{console.warn("[Qingyu Widget] 附件上传失败",u),D("附件上传失败，请重试")}).finally(()=>{q.disabled=!m}))});let h=!0;n.addEventListener("click",()=>{i.classList.toggle("open"),h&&i.classList.contains("open")&&(h=!1,t.welcomeMessage&&_(t.welcomeMessage))});const x=()=>{const c=f.value.trim();if(!c)return;f.value="";const u=l;l=[],v(),a.onSend(c,u.length?u:void 0)};k.addEventListener("click",x),f.addEventListener("keydown",c=>{c.key==="Enter"&&!c.shiftKey&&(c.preventDefault(),x())}),f.addEventListener("input",()=>a.onTyping?.()),s.addEventListener("click",()=>a.onHandover());function T(c,u){const p=document.createElement("div");p.className=`qy-msg ${u}`;const y=document.createElement("div");return y.className="qy-text",y.textContent=c,p.appendChild(y),r.appendChild(p),r.scrollTop=r.scrollHeight,p}function L(c,u){const p=re(u,a.resolveAttachmentUrl);p&&(c.appendChild(p),r.scrollTop=r.scrollHeight)}function ue(c){const u=document.createElement("div");u.className="qy-msg bot";const p=document.createElement("div");p.className="qy-text";const y=a.onAction??a.onSend;for(const b of c)if(b.kind==="text"){const E=b.text??"";if(E){const A=document.createElement("div");A.className="qy-node-text",A.textContent=E,p.appendChild(A)}}else if(b.kind==="ui"&&b.node){const E=M(b.node,y);E&&p.appendChild(E)}return u.appendChild(p),r.appendChild(u),r.scrollTop=r.scrollHeight,u}function R(c,u){c.classList.add("has-feedback");const p=document.createElement("div");p.className="qy-feedback";const y=document.createElement("button");y.type="button",y.textContent="👍",y.setAttribute("aria-label","有帮助");const b=document.createElement("button");b.type="button",b.textContent="👎",b.setAttribute("aria-label","没帮助");const E=(A,qe)=>{u.onFeedback(A),y.disabled=!0,b.disabled=!0,qe.classList.add("active")};y.addEventListener("click",()=>E(1,y)),b.addEventListener("click",()=>E(-1,b)),p.appendChild(y),p.appendChild(b),c.appendChild(p),r.scrollTop=r.scrollHeight}function fe(){const c=document.createElement("div");c.className="qy-msg bot";const u=document.createElement("div");u.className="qy-text",c.appendChild(u),r.appendChild(c),r.scrollTop=r.scrollHeight;let p=null;const y=a.onAction??a.onSend;return{appendText(b){b&&(p||(p=document.createElement("div"),p.className="qy-node-text",u.appendChild(p)),p.textContent=(p.textContent??"")+b,r.scrollTop=r.scrollHeight)},appendCard(b){const E=M(b,y);E&&u.appendChild(E),p=null,r.scrollTop=r.scrollHeight},finish(b){b&&R(c,b)}}}function ye(c,u){const p=T(c,"user");L(p,u)}function _(c,u,p,y){const E=Array.isArray(p)&&p.some(A=>A.kind==="ui"&&A.node)?ue(p):T(c,"bot");L(E,y),u&&R(E,u)}function he(c,u){const p=T(c,"bot");L(p,u);const y=document.createElement("div");y.textContent="客服",y.style.cssText="font-size:11px;color:#999;margin-bottom:2px;",p.insertBefore(y,p.firstChild)}function D(c){const u=document.createElement("div");u.style.cssText="text-align:center;color:#999;font-size:12px;margin:8px 0;",u.textContent=c,r.appendChild(u),r.scrollTop=r.scrollHeight}let N=null;function me(c){c&&!N?N=T("输入中…","bot"):!c&&N&&(N.remove(),N=null)}function be(c){f.disabled=!c,k.disabled=!c}function xe(c){m=c,q.disabled=!c}function ge(){h=!1}return{addUserMessage:ye,addBotMessage:_,addAgentMessage:he,showSessionState:D,setTyping:me,setInputEnabled:be,setAttachEnabled:xe,markOpened:ge,beginBotMessage:fe}}function de(t){const a=document.createElement("div");return a.textContent=t,a.innerHTML}function le(t,a){for(const e of a)switch(e.role){case"USER":t.addUserMessage(e.content,e.attachments);break;case"ASSISTANT":t.addBotMessage(e.content,void 0,e.uiParts,e.attachments);break;case"AGENT":t.addAgentMessage(e.content,e.attachments);break;case"SYSTEM":t.showSessionState(e.content);break}}function pe(t){switch(t){case"accepted":return"客服已接入";case"returned_to_ai":return"已切回智能助手";case"ended":return"会话已结束";case"waiting":return"正在为您转接人工…";default:return""}}async function j(){const t=F();if(!t)return;const a=V(t);let e;try{e=await a.getConfig()}catch(l){console.warn("[Qingyu Widget] 拉取配置失败，停止渲染",l);return}let o=G(t.appKey),d=null,n=null;function i(l){n===l&&d||(d?.close(),n=l,d=K(a.inboxUrl(l),t.appKey,(m,v)=>{let h;try{h=JSON.parse(v)}catch{return}m==="message"&&(h.role==="ASSISTANT"||h.role==="AGENT")?s.addAgentMessage(h.content,h.attachments):m==="session"?s.showSessionState(h.text||pe(h.kind)):m==="typing"&&h.from==="agent"&&s.setTyping(!0)}))}window.addEventListener("pagehide",()=>d?.close());let r=0;const f=3e3;function k(){if(!o)return;const l=Date.now();l-r<f||(r=l,a.sendTyping(o))}const s=ce(e,{onSend:(l,m)=>{q(l,m)},onHandover:()=>{C()},onTyping:()=>k(),onAction:l=>{q(l)},onUploadFile:l=>o?a.uploadAttachment(o,l):Promise.reject(new Error("会话尚未建立，无法上传附件")),resolveAttachmentUrl:l=>o?a.downloadAttachment(l,o).then(m=>URL.createObjectURL(m)):Promise.reject(new Error("会话尚未建立，无法下载附件"))});if(o){s.markOpened(),s.setAttachEnabled(!0);const l=await a.getSession(o);l&&l.messages.length&&le(s,l.messages),i(o)}async function q(l,m){s.addUserMessage(l,m),s.setInputEnabled(!1),s.setTyping(!0);let v=null;const h=()=>(v||(s.setTyping(!1),v=s.beginBotMessage()),v);try{await S(l,{onStart:x=>{o=x.sessionId,J(t.appKey,x.sessionId),i(x.sessionId),s.setAttachEnabled(!0)},onDelta:x=>h().appendText(x),onCard:x=>h().appendCard(x),onDone:x=>{const T=o;h().finish(x!=null&&x>0&&T?{onFeedback:L=>{a.feedback(T,x,L)}}:void 0)},onError:x=>h().appendText(`
${x}`)},m?.map(x=>x.id))}catch(x){v?v.appendText(`
回复中断，请重试。`):s.addBotMessage("抱歉，服务暂时不可用，请稍后再试。"),console.warn("[Qingyu Widget] 发送失败",x)}finally{s.setTyping(!1),s.setInputEnabled(!0)}}async function S(l,m,v){try{await a.stream(l,o,m,v)}catch(h){if(h instanceof B){H(t.appKey),o=null,s.setAttachEnabled(!1),await a.stream(l,null,m);return}throw h}}async function C(){if(!o){s.addBotMessage("请先发送消息开始会话，再申请转人工。");return}try{await a.handover(o),s.addBotMessage("已为你转接人工，请稍候。")}catch(l){console.warn("[Qingyu Widget] 转人工失败",l)}}}document.readyState==="loading"?document.addEventListener("DOMContentLoaded",()=>{j()}):j()})();
