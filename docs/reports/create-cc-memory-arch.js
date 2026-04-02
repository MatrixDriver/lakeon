const pptxgen = require('pptxgenjs');
const pptx = new pptxgen();
pptx.layout = 'LAYOUT_WIDE';
pptx.author = 'DBay Team';
pptx.title = 'Claude Code 记忆架构';

const slide = pptx.addSlide();
slide.background = { color: 'FFFFFF' };
const W = 13.33, H = 7.5;

const C = {
  title: '1A3A5C', text: '2C3E50', sec: '5D6D7E',
  blue: '1565C0', blueBg: 'E3F0FF',
  green: '0D7C66', greenBg: 'E8F6F3',
  orange: 'B45309', orangeBg: 'FEF3E8',
  purple: '6B3FA0', purpleBg: 'F3EDF9',
  red: 'C0392B', redBg: 'FDEDEC',
  teal: '00796B', tealBg: 'E0F2F1',
  cyan: '0078B8', cyanBg: 'E1F5FE',
  gold: '7D6608', goldBg: 'FDF9E8',
};

function rect(x,y,w,h,fill,line,lw,r){slide.addShape(pptx.shapes.ROUNDED_RECTANGLE,{x,y,w,h,rectRadius:r||0.06,fill:fill?{color:fill}:undefined,line:line?{color:line,width:lw||1}:undefined})}
function bar(x,y,w,h,c){slide.addShape(pptx.shapes.RECTANGLE,{x,y,w,h,fill:{color:c},line:{width:0}})}
function oval(x,y,w,h,fill,line,lw){slide.addShape(pptx.shapes.OVAL,{x,y,w,h,fill:fill?{color:fill}:undefined,line:line?{color:line,width:lw||1.5}:undefined})}
function txt(t,o){slide.addText(t,{x:o.x,y:o.y,w:o.w,h:o.h||0.25,fontSize:o.fs||11,fontFace:'Arial',color:o.c||C.text,bold:o.b||false,italic:o.i||false,align:o.a||'left',valign:o.va||'middle'})}
function richTxt(a,o){slide.addText(a,{x:o.x,y:o.y,w:o.w,h:o.h||0.25,fontFace:'Arial',valign:o.va||'middle',align:o.a||'left'})}
function arw(x1,y1,x2,y2,c,w){slide.addShape(pptx.shapes.LINE,{x:x1,y:y1,w:x2-x1||0.001,h:y2-y1||0.001,line:{color:c,width:w||2,endArrowType:'triangle'}})}
function dashed(x1,y1,x2,y2,c,w){slide.addShape(pptx.shapes.LINE,{x:x1,y:y1,w:x2-x1||0.001,h:y2-y1||0.001,line:{color:c,width:w||1.5,dashType:'dash'}})}

// ══════════════════════════════════════
// Title
// ══════════════════════════════════════
bar(0, 0, W, 0.58, C.title);
txt('Claude Code 记忆架构全景', { x: 0.4, y: 0.05, w: 10, h: 0.28, fs: 19, b: true, c: 'FFFFFF' });
txt('Markdown 文件 + Auto-Memory + Auto-Dream + MCP 扩展 + PostCompact 恢复', { x: 0.4, y: 0.3, w: 12, h: 0.2, fs: 10, c: 'B0C4DE' });

// ══════════════════════════════════════
// 左上：文件存储层
// ══════════════════════════════════════
const fsX = 0.25, fsY = 0.68, fsW = 4.3, fsH = 3.2;
rect(fsX, fsY, fsW, fsH, 'FAFBFD', C.blue, 1.5, 0.08);
bar(fsX, fsY, 0.06, fsH, C.blue);
txt('📁 文件存储层（Markdown-First）', { x: fsX + 0.15, y: fsY + 0.04, w: 4, h: 0.26, fs: 13, b: true, c: C.blue });

// Project CLAUDE.md
const f1Y = fsY + 0.36;
rect(fsX + 0.15, f1Y, 4.0, 0.52, C.blueBg, C.blue, 1);
txt('CLAUDE.md（项目根目录）', { x: fsX + 0.25, y: f1Y + 0.02, w: 3.5, h: 0.22, fs: 11, b: true, c: C.blue });
txt('Git 追踪 · 项目指令 · 最高优先级 · 人工维护', { x: fsX + 0.25, y: f1Y + 0.26, w: 3.8, h: 0.2, fs: 9, c: C.text });

// Memory directory
const f2Y = f1Y + 0.58;
rect(fsX + 0.15, f2Y, 4.0, 0.88, C.greenBg, C.green, 1);
txt('~/.claude/projects/[project]/memory/', { x: fsX + 0.25, y: f2Y + 0.02, w: 3.8, h: 0.22, fs: 11, b: true, c: C.green });
txt('MEMORY.md — 索引文件（上限 200 行，超出静默截断）', { x: fsX + 0.25, y: f2Y + 0.26, w: 3.8, h: 0.2, fs: 9, c: C.text });
txt('*.md — 独立记忆文件（YAML frontmatter + Markdown）', { x: fsX + 0.25, y: f2Y + 0.46, w: 3.8, h: 0.2, fs: 9, c: C.text });
txt('4 种类型：user / feedback / project / reference', { x: fsX + 0.25, y: f2Y + 0.66, w: 3.8, h: 0.2, fs: 9, c: C.sec, i: true });

// Session JSONL
const f3Y = f2Y + 0.94;
rect(fsX + 0.15, f3Y, 4.0, 0.52, C.orangeBg, C.orange, 1);
txt('Session JSONL', { x: fsX + 0.25, y: f3Y + 0.02, w: 3.5, h: 0.22, fs: 11, b: true, c: C.orange });
txt('每次交互自动追加 · 恢复中断会话 · 受 /compact 压缩', { x: fsX + 0.25, y: f3Y + 0.26, w: 3.8, h: 0.2, fs: 9, c: C.text });

// ══════════════════════════════════════
// 中上：会话生命周期（核心流程）
// ══════════════════════════════════════
const lcX = 4.8, lcY = 0.68, lcW = 4.6, lcH = 3.2;
rect(lcX, lcY, lcW, lcH, 'FAFBFD', C.purple, 1.5, 0.08);
bar(lcX, lcY, 0.06, lcH, C.purple);
txt('🔄 会话生命周期', { x: lcX + 0.15, y: lcY + 0.04, w: 4, h: 0.26, fs: 13, b: true, c: C.purple });

// Phase: Session Start
const lc1Y = lcY + 0.36;
rect(lcX + 0.15, lc1Y, 4.3, 0.72, C.purpleBg, C.purple, 1);
oval(lcX + 0.22, lc1Y + 0.04, 0.2, 0.2, C.purple);
txt('1', { x: lcX + 0.22, y: lc1Y + 0.04, w: 0.2, h: 0.2, fs: 9, b: true, c: 'FFFFFF', a: 'center' });
txt('会话启动 — 全量注入', { x: lcX + 0.48, y: lc1Y + 0.04, w: 3.5, h: 0.2, fs: 11, b: true, c: C.purple });
txt('加载 CLAUDE.md + MEMORY.md(前200行) + memory/*.md', { x: lcX + 0.25, y: lc1Y + 0.28, w: 4.1, h: 0.2, fs: 9, c: C.text });
txt('全部注入 System Prompt · 无语义检索 · 无过滤', { x: lcX + 0.25, y: lc1Y + 0.48, w: 4.1, h: 0.2, fs: 9, c: C.sec, i: true });

arw(lcX + 2.3, lc1Y + 0.72, lcX + 2.3, lc1Y + 0.84, C.purple, 1.5);

// Phase: During Session
const lc2Y = lc1Y + 0.86;
rect(lcX + 0.15, lc2Y, 4.3, 0.52, C.blueBg, C.blue, 1);
oval(lcX + 0.22, lc2Y + 0.04, 0.2, 0.2, C.blue);
txt('2', { x: lcX + 0.22, y: lc2Y + 0.04, w: 0.2, h: 0.2, fs: 9, b: true, c: 'FFFFFF', a: 'center' });
txt('会话中 — Auto-Memory 异步捕获', { x: lcX + 0.48, y: lc2Y + 0.04, w: 3.5, h: 0.2, fs: 11, b: true, c: C.blue });
txt('检测到可记忆信息 → 异步写入 memory/*.md → 更新 MEMORY.md', { x: lcX + 0.25, y: lc2Y + 0.28, w: 4.1, h: 0.2, fs: 9, c: C.text });

arw(lcX + 2.3, lc2Y + 0.52, lcX + 2.3, lc2Y + 0.64, C.blue, 1.5);

// Phase: Compact
const lc3Y = lc2Y + 0.66;
rect(lcX + 0.15, lc3Y, 4.3, 0.52, C.redBg, C.red, 1);
oval(lcX + 0.22, lc3Y + 0.04, 0.2, 0.2, C.red);
txt('3', { x: lcX + 0.22, y: lc3Y + 0.04, w: 0.2, h: 0.2, fs: 9, b: true, c: 'FFFFFF', a: 'center' });
txt('/compact — 上下文压缩（⚠️ 信息丢失）', { x: lcX + 0.48, y: lc3Y + 0.04, w: 3.5, h: 0.2, fs: 11, b: true, c: C.red });
txt('早期对话不可逆丢弃 · PostCompact Hook 可恢复关键记忆', { x: lcX + 0.25, y: lc3Y + 0.28, w: 4.1, h: 0.2, fs: 9, c: C.text });

arw(lcX + 2.3, lc3Y + 0.52, lcX + 2.3, lc3Y + 0.64, C.red, 1.5);

// Phase: Session End / Dream
const lc4Y = lc3Y + 0.66;
rect(lcX + 0.15, lc4Y, 4.3, 0.52, C.tealBg, C.teal, 1);
oval(lcX + 0.22, lc4Y + 0.04, 0.2, 0.2, C.teal);
txt('4', { x: lcX + 0.22, y: lc4Y + 0.04, w: 0.2, h: 0.2, fs: 9, b: true, c: 'FFFFFF', a: 'center' });
txt('会话结束 — Auto-Dream 记忆整理', { x: lcX + 0.48, y: lc4Y + 0.04, w: 3.5, h: 0.2, fs: 11, b: true, c: C.teal });
txt('异步反思会话内容 · 提炼关键记忆 · 整理/合并/去重', { x: lcX + 0.25, y: lc4Y + 0.28, w: 4.1, h: 0.2, fs: 9, c: C.text });

// ══════════════════════════════════════
// 右上：MCP 扩展层
// ══════════════════════════════════════
const mcpX = 9.65, mcpY = 0.68, mcpW = 3.45, mcpH = 3.2;
rect(mcpX, mcpY, mcpW, mcpH, 'FAFBFD', C.teal, 1.5, 0.08);
bar(mcpX, mcpY, 0.06, mcpH, C.teal);
txt('🔌 MCP 扩展层', { x: mcpX + 0.15, y: mcpY + 0.04, w: 3, h: 0.26, fs: 13, b: true, c: C.teal });
txt('第三方记忆服务通过 MCP 协议接入', { x: mcpX + 0.15, y: mcpY + 0.28, w: 3.2, h: 0.2, fs: 9, c: C.sec, i: true });

// MCP servers list
const mcps = [
  { name: 'ZhiXing Memory', desc: 'Trait 反思 + Q-value + 知识图谱', color: C.blue },
  { name: 'Anthropic KG Memory', desc: '官方实体-关系存储', color: C.purple },
  { name: 'Mem0', desc: '向量 + KV + 可选图谱', color: C.orange },
  { name: 'MemCP', desc: 'SQLite 递归记忆研究', color: C.green },
  { name: 'memory-graph', desc: 'Neo4j 关系记忆', color: C.red },
];

let mcpItemY = mcpY + 0.52;
mcps.forEach((m) => {
  rect(mcpX + 0.12, mcpItemY, 3.2, 0.44, 'FFFFFF', m.color, 1);
  bar(mcpX + 0.12, mcpItemY, 0.04, 0.44, m.color);
  txt(m.name, { x: mcpX + 0.22, y: mcpItemY + 0.02, w: 3, h: 0.2, fs: 10, b: true, c: m.color });
  txt(m.desc, { x: mcpX + 0.22, y: mcpItemY + 0.22, w: 3, h: 0.18, fs: 8.5, c: C.sec });
  mcpItemY += 0.48;
});

// ══════════════════════════════════════
// 下部左：Auto-Memory + Auto-Dream 详解
// ══════════════════════════════════════
const amX = 0.25, amY = 4.05, amW = 6.2, amH = 2.6;
rect(amX, amY, amW, amH, 'FAFBFD', 'DEE5ED', 1, 0.08);
txt('Auto-Memory 与 Auto-Dream 机制', { x: amX + 0.15, y: amY + 0.04, w: 5, h: 0.26, fs: 13, b: true, c: C.title });

// Auto-Memory
const am1X = amX + 0.12, am1Y = amY + 0.36, am1W = 2.9, am1H = 2.1;
rect(am1X, am1Y, am1W, am1H, C.blueBg, C.blue, 1.5);
bar(am1X, am1Y, 0.05, am1H, C.blue);
txt('Auto-Memory（v2.1.59+）', { x: am1X + 0.12, y: am1Y + 0.04, w: 2.6, h: 0.22, fs: 11, b: true, c: C.blue });
txt('触发：会话中实时', { x: am1X + 0.12, y: am1Y + 0.3, w: 2.6, h: 0.18, fs: 10, c: C.text });

const amItems = [
  '对话中检测可记忆信息',
  '异步写入 memory/*.md',
  'YAML frontmatter 分类标注',
  '更新 MEMORY.md 索引',
  '不阻塞用户交互',
];
amItems.forEach((t, i) => {
  txt('• ' + t, { x: am1X + 0.12, y: am1Y + 0.52 + i * 0.2, w: 2.6, h: 0.2, fs: 9, c: C.text });
});

richTxt([
  { text: '局限：', options: { bold: true, fontSize: 9, color: C.red } },
  { text: '启发式捕获，可能遗漏重要决策', options: { fontSize: 9, color: C.red } },
], { x: am1X + 0.12, y: am1Y + 1.58, w: 2.6, h: 0.2 });
richTxt([
  { text: '局限：', options: { bold: true, fontSize: 9, color: C.red } },
  { text: 'MEMORY.md 200 行上限', options: { fontSize: 9, color: C.red } },
], { x: am1X + 0.12, y: am1Y + 1.78, w: 2.6, h: 0.2 });

// Auto-Dream
const am2X = am1X + am1W + 0.15, am2Y = am1Y, am2W = 2.9, am2H = 2.1;
rect(am2X, am2Y, am2W, am2H, C.tealBg, C.teal, 1.5);
bar(am2X, am2Y, 0.05, am2H, C.teal);
txt('Auto-Dream（Sleep-time）', { x: am2X + 0.12, y: am2Y + 0.04, w: 2.6, h: 0.22, fs: 11, b: true, c: C.teal });
txt('触发：会话结束后异步', { x: am2X + 0.12, y: am2Y + 0.3, w: 2.6, h: 0.18, fs: 10, c: C.text });

const adItems = [
  '回顾整个会话内容',
  '提炼关键决策和模式',
  '合并冗余记忆条目',
  '整理 MEMORY.md 索引',
  '生成会话摘要存储',
];
adItems.forEach((t, i) => {
  txt('• ' + t, { x: am2X + 0.12, y: am2Y + 0.52 + i * 0.2, w: 2.6, h: 0.2, fs: 9, c: C.text });
});

richTxt([
  { text: '类比：', options: { bold: true, fontSize: 9, color: C.teal } },
  { text: '人类睡眠时的记忆巩固过程', options: { fontSize: 9, color: C.teal } },
], { x: am2X + 0.12, y: am2Y + 1.58, w: 2.6, h: 0.2 });
richTxt([
  { text: '价值：', options: { bold: true, fontSize: 9, color: C.teal } },
  { text: '弥补实时捕获的遗漏，做全局整理', options: { fontSize: 9, color: C.teal } },
], { x: am2X + 0.12, y: am2Y + 1.78, w: 2.6, h: 0.2 });

// Arrow: Auto-Memory → files
arw(am1X + am1W / 2, am1Y, am1X + am1W / 2, am1Y - 0.14, C.blue, 1.5);
// Arrow: Auto-Dream → files
arw(am2X + am2W / 2, am2Y, am2X + am2W / 2, am2Y - 0.14, C.teal, 1.5);

// ══════════════════════════════════════
// 下部右：痛点 + ZhiXing 机会
// ══════════════════════════════════════
const rpX = 6.7, rpY = 4.05, rpW = 6.4, rpH = 2.6;
rect(rpX, rpY, rpW, rpH, 'FAFBFD', 'DEE5ED', 1, 0.08);
txt('架构痛点与 ZhiXing 增强机会', { x: rpX + 0.15, y: rpY + 0.04, w: 5, h: 0.26, fs: 13, b: true, c: C.title });

// Pain points (left half)
const ppX = rpX + 0.12, ppY = rpY + 0.36, ppW = 3.0;
rect(ppX, ppY, ppW, 2.1, C.redBg, C.red, 1);
bar(ppX, ppY, 0.05, 2.1, C.red);
txt('❌ 现有痛点', { x: ppX + 0.12, y: ppY + 0.04, w: 2.7, h: 0.22, fs: 11, b: true, c: C.red });

const pains = [
  'MEMORY.md 200 行硬上限',
  '无语义检索，全量注入',
  '/compact 导致不可逆信息丢失',
  '无跨项目开发者画像',
  '无 Trait 反思（行为模式发现）',
  '无 Q-value（记忆效用评估）',
  '子 Agent 记忆不共享',
];
pains.forEach((t, i) => {
  txt('• ' + t, { x: ppX + 0.12, y: ppY + 0.3 + i * 0.22, w: 2.7, h: 0.22, fs: 9.5, c: C.text });
});

// ZhiXing enhancement (right half)
const zhX = ppX + ppW + 0.15, zhY = ppY, zhW = 3.0;
rect(zhX, zhY, zhW, 2.1, C.greenBg, C.green, 1);
bar(zhX, zhY, 0.05, 2.1, C.green);
txt('✅ ZhiXing MCP 增强', { x: zhX + 0.12, y: zhY + 0.04, w: 2.7, h: 0.22, fs: 11, b: true, c: C.green });

const enhancements = [
  'pgvector 无上限存储',
  '三路混合检索（向量+BM25+图谱）',
  'PostCompact Hook 自动恢复',
  '跨项目 developer_profile()',
  '9 步 Trait 反思引擎',
  'Q-value 效用评分 + 数据飞轮',
  'L0/L1/L2 渐进式返回',
];
enhancements.forEach((t, i) => {
  txt('• ' + t, { x: zhX + 0.12, y: zhY + 0.3 + i * 0.22, w: 2.7, h: 0.22, fs: 9.5, c: C.text });
});

// ── Bottom bar ──
bar(0, H - 0.48, W, 0.48, C.title);
richTxt([
  { text: 'Claude Code 的记忆 = Markdown 文件 + Auto-Memory + Auto-Dream', options: { bold: true, fontSize: 12, color: 'FFFFFF' } },
  { text: '    简洁透明但有上限 → ZhiXing 通过 MCP 协议补齐语义检索、Trait 反思、Q-value 飞轮', options: { fontSize: 11, color: 'B0C4DE' } },
], { x: 0.4, y: H - 0.46, w: W - 0.8, h: 0.44, a: 'center' });

const outPath = __dirname + '/cc-memory-arch.pptx';
pptx.writeFile({ fileName: outPath }).then(() => console.log('Created: ' + outPath)).catch(console.error);
