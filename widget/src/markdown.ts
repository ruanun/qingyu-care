import { safeUrl } from './core/safeUrl';

/**
 * AI 回复的 Markdown 渲染：直接构建 DOM 节点，**不解析任何 HTML**。
 *
 * 为什么不用「转 HTML 再塞 innerHTML」：AI 回复不受控（提示词注入、知识库里被投毒的文档都可能让模型吐出
 * `<img onerror>`），widget 的 XSS 三防要求文本一律 textContent、URL 一律过 safeUrl。这里守同一套规矩：
 * 原文里的 `<script>` 只会作为文字出现；链接只放行 http/https，不合规时只显示链接文字（fail-closed，同卡片按钮）。
 *
 * 只支持客服回复里常见的子集：段落与换行、标题、加粗 / 斜体 / 删除线、行内代码与代码块、有序 / 无序列表（可缩进嵌套）、
 * 引用、分隔线、表格、链接与裸 URL。不引第三方库：widget 整包才 30KB 左右。
 *
 * 流式场景下调用方每收到一段增量就用累积全文整体重渲染（`**加粗**` 可能被拆在两次推送里）；
 * 代码块还没收到结尾的 ``` 时，把剩余部分都当代码显示，不会把后面的内容吞成乱码。
 *
 * @author ruan
 */

const FENCE = /^\s*```/;
const HEADING = /^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/;
const HR = /^\s{0,3}([-*_])(\s*\1){2,}\s*$/;
const QUOTE = /^\s{0,3}>\s?(.*)$/;
const LIST_ITEM = /^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$/;
const TABLE_SEP = /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$/;

/** 引用可以嵌套引用，给递归一个上限，防畸形输入把栈打爆 */
const MAX_DEPTH = 8;

export function renderMarkdown(src: string, depth = 0): DocumentFragment {
  const frag = document.createDocumentFragment();
  const lines = src.replace(/\r\n?/g, '\n').split('\n');
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.trim() === '') {
      i++;
    } else if (FENCE.test(line)) {
      i = renderFence(frag, lines, i);
    } else if (HEADING.test(line)) {
      const h = document.createElement('div');
      h.className = 'qy-md-h';
      appendInline(h, HEADING.exec(line)![2]);
      frag.appendChild(h);
      i++;
    } else if (HR.test(line)) {
      frag.appendChild(document.createElement('hr'));
      i++;
    } else if (QUOTE.test(line) && depth < MAX_DEPTH) {
      const inner: string[] = [];
      while (i < lines.length && QUOTE.test(lines[i])) inner.push(QUOTE.exec(lines[i++])![1]);
      const bq = document.createElement('blockquote');
      bq.appendChild(renderMarkdown(inner.join('\n'), depth + 1));
      frag.appendChild(bq);
    } else if (LIST_ITEM.test(line)) {
      i = renderList(frag, lines, i);
    } else if (line.includes('|') && i + 1 < lines.length && TABLE_SEP.test(lines[i + 1])) {
      i = renderTable(frag, lines, i);
    } else {
      i = renderParagraph(frag, lines, i);
    }
  }
  return frag;
}

function isBlockStart(line: string): boolean {
  return FENCE.test(line) || HEADING.test(line) || HR.test(line) || QUOTE.test(line) || LIST_ITEM.test(line);
}

function renderFence(parent: Node, lines: string[], start: number): number {
  const body: string[] = [];
  let i = start + 1;
  while (i < lines.length && !FENCE.test(lines[i])) body.push(lines[i++]);
  const pre = document.createElement('pre');
  const code = document.createElement('code');
  code.textContent = body.join('\n');
  pre.appendChild(code);
  parent.appendChild(pre);
  return i < lines.length ? i + 1 : i; // 没收到结尾的 ```（流式进行中）时吃到末尾
}

function renderParagraph(parent: Node, lines: string[], start: number): number {
  const p = document.createElement('p');
  let i = start;
  while (i < lines.length && lines[i].trim() !== '' && (i === start || !isBlockStart(lines[i]))) {
    if (i > start) p.appendChild(document.createElement('br'));
    appendInline(p, lines[i].trim());
    i++;
  }
  parent.appendChild(p);
  return i;
}

/**
 * 列表按缩进建树：缩进更深的条目挂到上一个条目下面成为子列表。
 * 条目之后缩进的非列表行算这个条目的续行；空行后面不再是列表条目时列表结束。
 */
function renderList(parent: Node, lines: string[], start: number): number {
  type Level = { indent: number; list: HTMLElement; ordered: boolean };
  const stack: Level[] = [];
  let lastLi: HTMLLIElement | null = null;
  let i = start;
  while (i < lines.length) {
    const line = lines[i];
    const m = LIST_ITEM.exec(line);
    if (m) {
      const indent = m[1].replace(/\t/g, '    ').length;
      const ordered = /\d/.test(m[2]);
      while (stack.length && stack[stack.length - 1].indent > indent) stack.pop();
      const top = stack[stack.length - 1];
      if (!top || indent > top.indent || top.ordered !== ordered) {
        const list = document.createElement(ordered ? 'ol' : 'ul');
        if (ordered) {
          const n = parseInt(m[2], 10);
          if (n !== 1) (list as HTMLOListElement).start = n;
        }
        if (top && indent > top.indent && lastLi) lastLi.appendChild(list);
        else parent.appendChild(list);
        if (top && top.indent === indent) stack.pop(); // 同层换了列表类型：替换这一层
        stack.push({ indent, list, ordered });
      }
      lastLi = document.createElement('li');
      appendInline(lastLi, m[3]);
      stack[stack.length - 1].list.appendChild(lastLi);
      i++;
    } else if (line.trim() === '') {
      let j = i + 1;
      while (j < lines.length && lines[j].trim() === '') j++;
      if (j < lines.length && LIST_ITEM.test(lines[j])) i = j;
      else break;
    } else if (lastLi && /^\s+/.test(line) && !isBlockStart(line)) {
      lastLi.appendChild(document.createElement('br'));
      appendInline(lastLi, line.trim());
      i++;
    } else {
      break;
    }
  }
  return i;
}

function splitRow(line: string): string[] {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim());
}

function renderTable(parent: Node, lines: string[], start: number): number {
  const wrap = document.createElement('div');
  wrap.className = 'qy-md-table';
  const table = document.createElement('table');
  const thead = document.createElement('thead');
  const headRow = document.createElement('tr');
  for (const cell of splitRow(lines[start])) {
    const th = document.createElement('th');
    appendInline(th, cell);
    headRow.appendChild(th);
  }
  thead.appendChild(headRow);
  table.appendChild(thead);
  const tbody = document.createElement('tbody');
  let i = start + 2;
  while (i < lines.length && lines[i].includes('|') && lines[i].trim() !== '') {
    const tr = document.createElement('tr');
    for (const cell of splitRow(lines[i])) {
      const td = document.createElement('td');
      appendInline(td, cell);
      tr.appendChild(td);
    }
    tbody.appendChild(tr);
    i++;
  }
  table.appendChild(tbody);
  wrap.appendChild(table);
  parent.appendChild(wrap);
  return i;
}

/**
 * 行内语法。按出现位置从左到右找最早的一个标记处理，标记之间的文字一律 textContent。
 * 顺序有讲究：行内代码最先（里面的 `**` 不该再解析），链接先于加粗（链接文字里可以有加粗）。
 * 斜体只认 `*x*` 不认 `_x_`：客服回复里常见 snake_case 的订单字段名，用下划线会把它们切碎。
 */
const INLINE = new RegExp(
  [
    '(`+)([^`][\\s\\S]*?)\\1(?!`)', // 1,2 行内代码
    '\\[([^\\]\\n]+)\\]\\(([^)\\s]+)(?:\\s+"[^"]*")?\\)', // 3,4 链接
    '\\*\\*([^\\n]+?)\\*\\*', // 5 加粗
    '__([^\\n]+?)__', // 6 加粗
    '~~([^\\n]+?)~~', // 7 删除线
    '\\*([^*\\s](?:[^*\\n]*[^*\\s])?)\\*', // 8 斜体
    // 9 裸 URL：只收 URL 合法的 ASCII 字符——中文句子里网址后面往往紧跟「。谢谢」没有空格，按「非空白」取会把它们吞进链接。
    // 末尾的 . , ; : ! ? ' 不算进链接；括号不收，免得「（见 https://a.com）」把右括号带进去
    "(https?://[A-Za-z0-9\\-._~:/?#@!$&'*+,;=%]*[A-Za-z0-9\\-_~/#@$&*+=%])",
  ].join('|'),
);

function appendInline(parent: Node, text: string, depth = 0): void {
  let rest = text;
  while (rest) {
    const m = depth < MAX_DEPTH ? INLINE.exec(rest) : null;
    if (!m) {
      parent.appendChild(document.createTextNode(rest));
      return;
    }
    if (m.index > 0) parent.appendChild(document.createTextNode(rest.slice(0, m.index)));
    if (m[2] !== undefined) {
      const code = document.createElement('code');
      code.textContent = m[2];
      parent.appendChild(code);
    } else if (m[3] !== undefined) {
      appendLink(parent, m[3], m[4], depth);
    } else if (m[5] !== undefined || m[6] !== undefined) {
      const strong = document.createElement('strong');
      appendInline(strong, m[5] ?? m[6], depth + 1);
      parent.appendChild(strong);
    } else if (m[7] !== undefined) {
      const del = document.createElement('del');
      appendInline(del, m[7], depth + 1);
      parent.appendChild(del);
    } else if (m[8] !== undefined) {
      const em = document.createElement('em');
      appendInline(em, m[8], depth + 1);
      parent.appendChild(em);
    } else if (m[9] !== undefined) {
      appendLink(parent, null, m[9], depth);
    }
    rest = rest.slice(m.index + m[0].length);
  }
}

function appendLink(parent: Node, label: string | null, url: string, depth: number): void {
  const href = safeUrl(url);
  if (!href) {
    // fail-closed：javascript: / data: 之类不生成可点的链接，只留文字
    appendInline(parent, label ?? url, depth + 1);
    return;
  }
  const a = document.createElement('a');
  a.href = href;
  a.target = '_blank';
  a.rel = 'noopener noreferrer';
  if (label === null) a.textContent = url;
  else appendInline(a, label, depth + 1);
  parent.appendChild(a);
}
