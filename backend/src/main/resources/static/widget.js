(function(){"use strict";function E(){const e=document.currentScript??document.querySelector('script[src*="widget.js"]');if(!e||!e.src)return console.warn("[Qingyu Widget] 未找到加载脚本，无法初始化"),null;const n=new URL(e.src),t=n.searchParams.get("appKey");return t?{appKey:t,apiBase:n.origin+"/api"}:(console.warn("[Qingyu Widget] 缺少 appKey，无法初始化"),null)}class h extends Error{constructor(){super("会话不存在或已过期"),this.name="SessionExpiredError"}}function k(e){const n=()=>({"Content-Type":"application/json","X-App-Id":e.appKey});return{async getConfig(){const t=await fetch(`${e.apiBase}/open/v1/widget/config`,{headers:n()});if(!t.ok)throw new Error(`widget config 请求失败: ${t.status}`);return await t.json()},async send(t,a){const s=a?{message:t,sessionId:a}:{message:t},i=await fetch(`${e.apiBase}/open/v1/chat/completions`,{method:"POST",headers:n(),body:JSON.stringify(s)});if(i.status===404)throw new h;if(!i.ok)throw new Error(`对话请求失败: ${i.status}`);return await i.json()},async handover(t){await fetch(`${e.apiBase}/open/v1/sessions/${t}/handover`,{method:"POST",headers:n()})}}}const M=1440*60*1e3,y=e=>`qingyu_widget_session_${e}`;function C(e){try{const n=localStorage.getItem(y(e));if(!n)return null;const t=JSON.parse(n);return!t||typeof t.sessionId!="string"||typeof t.createdAt!="number"||Date.now()-t.createdAt>M?(m(e),null):t.sessionId}catch{return null}}function x(e,n){try{const t={sessionId:n,createdAt:Date.now()};localStorage.setItem(y(e),JSON.stringify(t))}catch{}}function m(e){try{localStorage.removeItem(y(e))}catch{}}function I(e){return`
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
    @media (max-width: 480px) {
      .qy-panel { right: 0; bottom: 0; width: 100vw; height: 100vh; border-radius: 0; }
    }
  `}function $(e,n){const t=document.createElement("div");document.body.appendChild(t);const a=t.attachShadow({mode:"open"}),s=document.createElement("style");s.textContent=I(e.themeColor||"#6366f1"),a.appendChild(s);const i=document.createElement("button");i.className="qy-bubble",i.textContent="💬",i.setAttribute("aria-label","打开客服");const c=document.createElement("div");c.className="qy-panel",c.innerHTML=`
    <div class="qy-header">
      <span>${L(e.appName||"在线客服")}</span>
      <button class="qy-handover" type="button">转人工</button>
    </div>
    <div class="qy-msgs"></div>
    <div class="qy-input">
      <textarea placeholder="输入消息…"></textarea>
      <button type="button">发送</button>
    </div>
  `,a.appendChild(i),a.appendChild(c);const l=c.querySelector(".qy-msgs"),r=c.querySelector("textarea"),d=c.querySelector(".qy-input button"),u=c.querySelector(".qy-handover");let v=!0;i.addEventListener("click",()=>{c.classList.toggle("open"),v&&c.classList.contains("open")&&(v=!1,e.welcomeMessage&&S(e.welcomeMessage))});const q=()=>{const o=r.value.trim();o&&(r.value="",n.onSend(o))};d.addEventListener("click",q),r.addEventListener("keydown",o=>{o.key==="Enter"&&!o.shiftKey&&(o.preventDefault(),q())}),u.addEventListener("click",()=>n.onHandover());function g(o,O){const f=document.createElement("div");f.className=`qy-msg ${O}`;const b=document.createElement("div");return b.className="qy-text",b.textContent=o,f.appendChild(b),l.appendChild(f),l.scrollTop=l.scrollHeight,f}function T(o){g(o,"user")}function S(o){g(o,"bot")}let p=null;function B(o){o&&!p?p=g("输入中…","bot"):!o&&p&&(p.remove(),p=null)}function N(o){r.disabled=!o,d.disabled=!o}return{addUserMessage:T,addBotMessage:S,setTyping:B,setInputEnabled:N}}function L(e){const n=document.createElement("div");return n.textContent=e,n.innerHTML}async function w(){const e=E();if(!e)return;const n=k(e);let t;try{t=await n.getConfig()}catch(r){console.warn("[Qingyu Widget] 拉取配置失败，停止渲染",r);return}let a=C(e.appKey);const s=$(t,{onSend:r=>{i(r)},onHandover:()=>{l()}});async function i(r){s.addUserMessage(r),s.setInputEnabled(!1),s.setTyping(!0);try{const d=await c(r);s.setTyping(!1),s.addBotMessage(d.reply)}catch(d){s.setTyping(!1),s.addBotMessage("抱歉，服务暂时不可用，请稍后再试。"),console.warn("[Qingyu Widget] 发送失败",d)}finally{s.setInputEnabled(!0)}}async function c(r){try{const d=await n.send(r,a);return a=d.sessionId,x(e.appKey,a),d}catch(d){if(d instanceof h){m(e.appKey),a=null;const u=await n.send(r,null);return a=u.sessionId,x(e.appKey,a),u}throw d}}async function l(){if(!a){s.addBotMessage("请先发送消息开始会话，再申请转人工。");return}try{await n.handover(a),s.addBotMessage("已为你转接人工，请稍候。")}catch(r){console.warn("[Qingyu Widget] 转人工失败",r)}}}document.readyState==="loading"?document.addEventListener("DOMContentLoaded",()=>{w()}):w()})();
