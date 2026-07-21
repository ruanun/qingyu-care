(function(){"use strict";function I(){const e=document.currentScript??document.querySelector('script[src*="widget.js"]');if(!e||!e.src)return console.warn("[Qingyu Widget] 未找到加载脚本，无法初始化"),null;const n=new URL(e.src),t=n.searchParams.get("appKey");return t?{appKey:t,apiBase:n.origin+"/api"}:(console.warn("[Qingyu Widget] 缺少 appKey，无法初始化"),null)}class v extends Error{constructor(){super("会话不存在或已过期"),this.name="SessionExpiredError"}}function M(e){const n=()=>({"Content-Type":"application/json","X-App-Id":e.appKey});return{async getConfig(){const t=await fetch(`${e.apiBase}/open/v1/widget/config`,{headers:n()});if(!t.ok)throw new Error(`widget config 请求失败: ${t.status}`);return await t.json()},async send(t,a){const s=a?{message:t,sessionId:a}:{message:t},d=await fetch(`${e.apiBase}/open/v1/chat/completions`,{method:"POST",headers:n(),body:JSON.stringify(s)});if(d.status===404)throw new v;if(!d.ok)throw new Error(`对话请求失败: ${d.status}`);return await d.json()},async handover(t){await fetch(`${e.apiBase}/open/v1/sessions/${t}/handover`,{method:"POST",headers:n()})},async feedback(t,a,s){await fetch(`${e.apiBase}/open/v1/chat/feedback`,{method:"POST",headers:n(),body:JSON.stringify({sessionId:t,messageId:a,feedback:s})})}}}const T=1440*60*1e3,m=e=>`qingyu_widget_session_${e}`;function $(e){try{const n=localStorage.getItem(m(e));if(!n)return null;const t=JSON.parse(n);return!t||typeof t.sessionId!="string"||typeof t.createdAt!="number"||Date.now()-t.createdAt>T?(k(e),null):t.sessionId}catch{return null}}function q(e,n){try{const t={sessionId:n,createdAt:Date.now()};localStorage.setItem(m(e),JSON.stringify(t))}catch{}}function k(e){try{localStorage.removeItem(m(e))}catch{}}function B(e){return`
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
  `}function N(e,n){const t=document.createElement("div");document.body.appendChild(t);const a=t.attachShadow({mode:"open"}),s=document.createElement("style");s.textContent=B(e.themeColor||"#6366f1"),a.appendChild(s);const d=document.createElement("button");d.className="qy-bubble",d.textContent="💬",d.setAttribute("aria-label","打开客服");const c=document.createElement("div");c.className="qy-panel",c.innerHTML=`
    <div class="qy-header">
      <span>${O(e.appName||"在线客服")}</span>
      <button class="qy-handover" type="button">转人工</button>
    </div>
    <div class="qy-msgs"></div>
    <div class="qy-input">
      <textarea placeholder="输入消息…"></textarea>
      <button type="button">发送</button>
    </div>
  `,a.appendChild(d),a.appendChild(c);const p=c.querySelector(".qy-msgs"),r=c.querySelector("textarea"),i=c.querySelector(".qy-input button"),u=c.querySelector(".qy-handover");let h=!0;d.addEventListener("click",()=>{c.classList.toggle("open"),h&&c.classList.contains("open")&&(h=!1,e.welcomeMessage&&C(e.welcomeMessage))});const E=()=>{const o=r.value.trim();o&&(r.value="",n.onSend(o))};i.addEventListener("click",E),r.addEventListener("keydown",o=>{o.key==="Enter"&&!o.shiftKey&&(o.preventDefault(),E())}),u.addEventListener("click",()=>n.onHandover());function w(o,x){const f=document.createElement("div");f.className=`qy-msg ${x}`;const l=document.createElement("div");return l.className="qy-text",l.textContent=o,f.appendChild(l),p.appendChild(f),p.scrollTop=p.scrollHeight,f}function z(o){w(o,"user")}function C(o,x){const f=w(o,"bot");if(x){f.classList.add("has-feedback");const l=document.createElement("div");l.className="qy-feedback";const y=document.createElement("button");y.type="button",y.textContent="👍",y.setAttribute("aria-label","有帮助");const b=document.createElement("button");b.type="button",b.textContent="👎",b.setAttribute("aria-label","没帮助");const L=(K,j)=>{x.onFeedback(K),y.disabled=!0,b.disabled=!0,j.classList.add("active")};y.addEventListener("click",()=>L(1,y)),b.addEventListener("click",()=>L(-1,b)),l.appendChild(y),l.appendChild(b),f.appendChild(l),p.scrollTop=p.scrollHeight}}let g=null;function H(o){o&&!g?g=w("输入中…","bot"):!o&&g&&(g.remove(),g=null)}function A(o){r.disabled=!o,i.disabled=!o}return{addUserMessage:z,addBotMessage:C,setTyping:H,setInputEnabled:A}}function O(e){const n=document.createElement("div");return n.textContent=e,n.innerHTML}async function S(){const e=I();if(!e)return;const n=M(e);let t;try{t=await n.getConfig()}catch(r){console.warn("[Qingyu Widget] 拉取配置失败，停止渲染",r);return}let a=$(e.appKey);const s=N(t,{onSend:r=>{d(r)},onHandover:()=>{p()}});async function d(r){s.addUserMessage(r),s.setInputEnabled(!1),s.setTyping(!0);try{const i=await c(r);s.setTyping(!1);const u=i.messageId;s.addBotMessage(i.reply,u?{onFeedback:h=>{n.feedback(i.sessionId,u,h)}}:void 0)}catch(i){s.setTyping(!1),s.addBotMessage("抱歉，服务暂时不可用，请稍后再试。"),console.warn("[Qingyu Widget] 发送失败",i)}finally{s.setInputEnabled(!0)}}async function c(r){try{const i=await n.send(r,a);return a=i.sessionId,q(e.appKey,a),i}catch(i){if(i instanceof v){k(e.appKey),a=null;const u=await n.send(r,null);return a=u.sessionId,q(e.appKey,a),u}throw i}}async function p(){if(!a){s.addBotMessage("请先发送消息开始会话，再申请转人工。");return}try{await n.handover(a),s.addBotMessage("已为你转接人工，请稍候。")}catch(r){console.warn("[Qingyu Widget] 转人工失败",r)}}}document.readyState==="loading"?document.addEventListener("DOMContentLoaded",()=>{S()}):S()})();
