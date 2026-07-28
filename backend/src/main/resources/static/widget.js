(function(){"use strict";function $(){const e=document.currentScript??document.querySelector('script[src*="widget.js"]');if(!e||!e.src)return console.warn("[Qingyu Widget] 未找到加载脚本，无法初始化"),null;const o=new URL(e.src),t=o.searchParams.get("appKey");return t?{appKey:t,apiBase:o.origin+"/api"}:(console.warn("[Qingyu Widget] 缺少 appKey，无法初始化"),null)}class T extends Error{constructor(){super("会话不存在或已过期"),this.name="SessionExpiredError"}}function B(e){const o=()=>({"Content-Type":"application/json","X-App-Id":e.appKey});return{async getConfig(){const t=await fetch(`${e.apiBase}/open/v1/widget/config`,{headers:o()});if(!t.ok)throw new Error(`widget config 请求失败: ${t.status}`);return await t.json()},async send(t,n){const l=n?{message:t,sessionId:n}:{message:t},c=await fetch(`${e.apiBase}/open/v1/chat/completions`,{method:"POST",headers:o(),body:JSON.stringify(l)});if(c.status===404)throw new T;if(!c.ok)throw new Error(`对话请求失败: ${c.status}`);return await c.json()},async handover(t){await fetch(`${e.apiBase}/open/v1/sessions/${t}/handover`,{method:"POST",headers:o()})},async feedback(t,n,l){try{await fetch(`${e.apiBase}/open/v1/chat/feedback`,{method:"POST",headers:o(),body:JSON.stringify({sessionId:t,messageId:n,feedback:l})})}catch{}},inboxUrl(t){return`${e.apiBase}/open/v1/sessions/${t}/inbox`},sendTyping(t){return fetch(`${e.apiBase}/open/v1/sessions/${t}/typing`,{method:"POST",headers:o()}).then(()=>{}).catch(()=>{})},async getSession(t){try{const n=await fetch(`${e.apiBase}/open/v1/sessions/${t}`,{headers:o()});return n.ok?await n.json():null}catch{return null}}}}const A=1440*60*1e3,E=e=>`qingyu_widget_session_${e}`;function O(e){try{const o=localStorage.getItem(E(e));if(!o)return null;const t=JSON.parse(o);return!t||typeof t.sessionId!="string"||typeof t.createdAt!="number"||Date.now()-t.createdAt>A?(I(e),null):t.sessionId}catch{return null}}function C(e,o){try{const t={sessionId:o,createdAt:Date.now()};localStorage.setItem(E(e),JSON.stringify(t))}catch{}}function I(e){try{localStorage.removeItem(E(e))}catch{}}function N(e){return`
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
  `}function z(e,o){const t=document.createElement("div");document.body.appendChild(t);const n=t.attachShadow({mode:"open"}),l=document.createElement("style");l.textContent=N(e.themeColor||"#6366f1"),n.appendChild(l);const c=document.createElement("button");c.className="qy-bubble",c.textContent="💬",c.setAttribute("aria-label","打开客服");const p=document.createElement("div");p.className="qy-panel",p.innerHTML=`
    <div class="qy-header">
      <span>${H(e.appName||"在线客服")}</span>
      <button class="qy-handover" type="button">转人工</button>
    </div>
    <div class="qy-msgs"></div>
    <div class="qy-input">
      <textarea placeholder="输入消息…"></textarea>
      <button type="button">发送</button>
    </div>
  `,n.appendChild(c),n.appendChild(p);const d=p.querySelector(".qy-msgs"),h=p.querySelector("textarea"),v=p.querySelector(".qy-input button"),r=p.querySelector(".qy-handover");let S=!0;c.addEventListener("click",()=>{p.classList.toggle("open"),S&&p.classList.contains("open")&&(S=!1,e.welcomeMessage&&i(e.welcomeMessage))});const q=()=>{const a=h.value.trim();a&&(h.value="",o.onSend(a))};v.addEventListener("click",q),h.addEventListener("keydown",a=>{a.key==="Enter"&&!a.shiftKey&&(a.preventDefault(),q())}),h.addEventListener("input",()=>o.onTyping?.()),r.addEventListener("click",()=>o.onHandover());function b(a,f){const y=document.createElement("div");y.className=`qy-msg ${f}`;const x=document.createElement("div");return x.className="qy-text",x.textContent=a,y.appendChild(x),d.appendChild(y),d.scrollTop=d.scrollHeight,y}function s(a){b(a,"user")}function i(a,f){const y=b(a,"bot");if(f){y.classList.add("has-feedback");const x=document.createElement("div");x.className="qy-feedback";const m=document.createElement("button");m.type="button",m.textContent="👍",m.setAttribute("aria-label","有帮助");const w=document.createElement("button");w.type="button",w.textContent="👎",w.setAttribute("aria-label","没帮助");const M=(U,D)=>{f.onFeedback(U),m.disabled=!0,w.disabled=!0,D.classList.add("active")};m.addEventListener("click",()=>M(1,m)),w.addEventListener("click",()=>M(-1,w)),x.appendChild(m),x.appendChild(w),y.appendChild(x),d.scrollTop=d.scrollHeight}}function u(a){const f=b(a,"bot"),y=document.createElement("div");y.textContent="客服",y.style.cssText="font-size:11px;color:#999;margin-bottom:2px;",f.insertBefore(y,f.firstChild)}function g(a){const f=document.createElement("div");f.style.cssText="text-align:center;color:#999;font-size:12px;margin:8px 0;",f.textContent=a,d.appendChild(f),d.scrollTop=d.scrollHeight}let k=null;function _(a){a&&!k?k=b("输入中…","bot"):!a&&k&&(k.remove(),k=null)}function P(a){h.disabled=!a,v.disabled=!a}return{addUserMessage:s,addBotMessage:i,addAgentMessage:u,showSessionState:g,setTyping:_,setInputEnabled:P}}function H(e){const o=document.createElement("div");return o.textContent=e,o.innerHTML}function j(e){let o="message";const t=[];for(const n of e.split(`
`))n===""||n.startsWith(":")||(n.startsWith("event:")?o=n.slice(6).trim():n.startsWith("data:")&&t.push(n.slice(5).replace(/^ /,"")));return{event:o,data:t.length?t.join(`
`):null}}function K(e,o,t){let n=!1,l=null,c=0;return(async()=>{for(;!n;){l=new AbortController;try{const d=await fetch(e,{headers:{"X-App-Id":o,Accept:"text/event-stream"},signal:l.signal});if(!d.ok||!d.body)throw new Error("SSE "+d.status);c=0;const h=d.body.getReader(),v=new TextDecoder;let r="";for(;;){const{done:S,value:q}=await h.read();if(S||n)break;r+=v.decode(q,{stream:!0});let b;for(;(b=r.indexOf(`

`))>=0;){const s=r.slice(0,b);r=r.slice(b+2);const{event:i,data:u}=j(s);u!==null&&t(i,u)}}}catch{if(n)return}if(n)return;c+=1,await new Promise(d=>setTimeout(d,Math.min(15e3,500*2**Math.min(c,5))))}})(),{close:()=>{n=!0,l?.abort()}}}function W(e){switch(e){case"accepted":return"客服已接入";case"returned_to_ai":return"已切回智能助手";case"ended":return"会话已结束";case"waiting":return"正在为您转接人工…";default:return""}}async function L(){const e=$();if(!e)return;const o=B(e);let t;try{t=await o.getConfig()}catch(s){console.warn("[Qingyu Widget] 拉取配置失败，停止渲染",s);return}let n=O(e.appKey),l=null,c=null;function p(s){c===s&&l||(l?.close(),c=s,l=K(o.inboxUrl(s),e.appKey,(i,u)=>{let g;try{g=JSON.parse(u)}catch{return}i==="message"&&g.role==="ASSISTANT"?r.addAgentMessage(g.content):i==="session"?r.showSessionState(g.text||W(g.kind)):i==="typing"&&g.from==="agent"&&r.setTyping(!0)}))}window.addEventListener("pagehide",()=>l?.close());let d=0;const h=3e3;function v(){if(!n)return;const s=Date.now();s-d<h||(d=s,o.sendTyping(n))}const r=z(t,{onSend:s=>{S(s)},onHandover:()=>{b()},onTyping:()=>v()});n&&p(n);async function S(s){r.addUserMessage(s),r.setInputEnabled(!1),r.setTyping(!0);try{const i=await q(s);p(i.sessionId),r.setTyping(!1);const u=i.messageId;r.addBotMessage(i.reply,u?{onFeedback:g=>{o.feedback(i.sessionId,u,g)}}:void 0)}catch(i){r.setTyping(!1),r.addBotMessage("抱歉，服务暂时不可用，请稍后再试。"),console.warn("[Qingyu Widget] 发送失败",i)}finally{r.setInputEnabled(!0)}}async function q(s){try{const i=await o.send(s,n);return n=i.sessionId,C(e.appKey,n),i}catch(i){if(i instanceof T){I(e.appKey),n=null;const u=await o.send(s,null);return n=u.sessionId,C(e.appKey,n),u}throw i}}async function b(){if(!n){r.addBotMessage("请先发送消息开始会话，再申请转人工。");return}try{await o.handover(n),r.addBotMessage("已为你转接人工，请稍候。")}catch(s){console.warn("[Qingyu Widget] 转人工失败",s)}}}document.readyState==="loading"?document.addEventListener("DOMContentLoaded",()=>{L()}):L()})();
