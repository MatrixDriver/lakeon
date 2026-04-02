const pptxgen = require('pptxgenjs');
const pptx = new pptxgen();
pptx.layout = 'LAYOUT_WIDE';
pptx.author = 'DBay Team';
pptx.title = '记忆语义结构化';

const slide = pptx.addSlide();
slide.background = { color: 'FFFFFF' };
const W = 13.33, H = 7.5;

const C = {
  title: '1A3A5C', text: '2C3E50', textSec: '5D6D7E',
  blue: '1565C0', blueBg: 'E3F0FF',
  green: '0D7C66', greenBg: 'E8F6F3',
  orange: 'B45309', orangeBg: 'FEF3E8',
  purple: '6B3FA0', purpleBg: 'F3EDF9',
  red: 'C0392B', redBg: 'FDEDEC',
  cyan: '0078B8', cyanBg: 'E1F5FE',
  gold: '7D6608', goldBg: 'FDF9E8',
  teal: '00796B', tealBg: 'E0F2F1',
};

function rect(x, y, w, h, fill, line, lw, r) {
  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x, y, w, h, rectRadius: r || 0.06,
    fill: fill ? { color: fill } : undefined,
    line: line ? { color: line, width: lw || 1 } : undefined,
  });
}
function solidRect(x, y, w, h, fill) {
  slide.addShape(pptx.shapes.RECTANGLE, { x, y, w, h, fill: { color: fill }, line: { width: 0 } });
}
function oval(x, y, w, h, fill, line, lw) {
  slide.addShape(pptx.shapes.OVAL, {
    x, y, w, h,
    fill: fill ? { color: fill } : undefined,
    line: line ? { color: line, width: lw || 1.5 } : undefined,
  });
}
function diamond(x, y, w, h, fill, line) {
  // use rotated rectangle
  slide.addShape(pptx.shapes.RECTANGLE, {
    x, y, w, h, rotate: 45,
    fill: fill ? { color: fill } : undefined,
    line: line ? { color: line, width: 1.5 } : undefined,
  });
}
function txt(t, o) {
  slide.addText(t, {
    x: o.x, y: o.y, w: o.w, h: o.h || 0.25,
    fontSize: o.fs || 10, fontFace: 'Arial',
    color: o.c || C.text, bold: o.b || false, italic: o.i || false,
    align: o.a || 'left', valign: o.va || 'middle', lineSpacing: o.ls || undefined,
  });
}
function richTxt(arr, o) {
  slide.addText(arr, {
    x: o.x, y: o.y, w: o.w, h: o.h || 0.25, fontFace: 'Arial',
    valign: o.va || 'middle', align: o.a || 'left',
  });
}
function arw(x1, y1, x2, y2, color, w) {
  slide.addShape(pptx.shapes.LINE, {
    x: x1, y: y1, w: x2 - x1 || 0.001, h: y2 - y1 || 0.001,
    line: { color, width: w || 2, endArrowType: 'triangle' },
  });
}
function dashed(x1, y1, x2, y2, color) {
  slide.addShape(pptx.shapes.LINE, {
    x: x1, y: y1, w: x2 - x1 || 0.001, h: y2 - y1 || 0.001,
    line: { color, width: 1.5, dashType: 'dash' },
  });
}

// ══════════════════════════════════════
// Title
// ══════════════════════════════════════
solidRect(0, 0, W, 0.58, C.title);
txt('记忆语义结构化：从碎片文本到知识图谱', { x: 0.4, y: 0.05, w: 11, h: 0.28, fs: 19, b: true, c: 'FFFFFF' });
txt('通过注入时增强 + 定期反思整理，让记忆形成语义网络，支撑 Agent 高质量召回', { x: 0.4, y: 0.3, w: 12, h: 0.2, fs: 10, c: 'B0C4DE' });

// ══════════════════════════════════════
// 上半部：注入时结构化流水线（左→右流程图）
// ══════════════════════════════════════
const pipeY = 0.68;
rect(0.2, pipeY, W - 0.4, 2.85, 'FAFBFD', 'DEE5ED', 1, 0.1);
txt('注入时结构化流水线', { x: 0.35, y: pipeY + 0.04, w: 3, h: 0.26, fs: 13, b: true, c: C.title });
txt('每条对话进入记忆前，经过 4 步语义增强', { x: 3.5, y: pipeY + 0.06, w: 5, h: 0.22, fs: 9, i: true, c: C.textSec });

const sY = pipeY + 0.38, sH = 2.22;
const sW = 2.85, sGap = 0.22; // wider cards, proper gaps
const s1X = 0.4;

// ── Step 1: Sliding Window ──
rect(s1X, sY, sW, sH, C.blueBg, C.blue, 1.5, 0.08);
oval(s1X + 0.08, sY + 0.06, 0.28, 0.28, C.blue, null, 0);
txt('1', { x: s1X + 0.08, y: sY + 0.06, w: 0.28, h: 0.28, fs: 12, b: true, c: 'FFFFFF', a: 'center' });
txt('滑动窗口 + 代词消解', { x: s1X + 0.42, y: sY + 0.06, w: 2.3, h: 0.28, fs: 10.5, b: true, c: C.blue });

// Before/after example box
rect(s1X + 0.1, sY + 0.42, 2.65, 0.72, 'FFFFFF', 'D0D0D0', 0.5, 0.04);
txt('原文：', { x: s1X + 0.15, y: sY + 0.44, w: 0.5, h: 0.18, fs: 8, b: true, c: C.red });
txt('"她讨厌那个框架"', { x: s1X + 0.6, y: sY + 0.44, w: 2.0, h: 0.18, fs: 8.5, c: C.textSec, i: true });
txt('▼', { x: s1X + 0.1, y: sY + 0.64, w: 2.65, h: 0.16, fs: 10, c: C.blue, a: 'center' });
txt('增强：', { x: s1X + 0.15, y: sY + 0.82, w: 0.5, h: 0.18, fs: 8, b: true, c: C.green });
txt('"Alice 讨厌 React 框架"', { x: s1X + 0.6, y: sY + 0.82, w: 2.0, h: 0.18, fs: 8.5, c: C.green, b: true });

txt('• 上下文窗口回溯 5 轮', { x: s1X + 0.1, y: sY + 1.24, w: 2.65, h: 0.18, fs: 8.5, c: C.text });
txt('• 消解代词、补全省略实体', { x: s1X + 0.1, y: sY + 1.44, w: 2.65, h: 0.18, fs: 8.5, c: C.text });
txt('• 注入用户属性（职业等）', { x: s1X + 0.1, y: sY + 1.64, w: 2.65, h: 0.18, fs: 8.5, c: C.text });

rect(s1X + 0.1, sY + 1.9, 1.5, 0.22, C.blue, null, 0, 0.03);
txt('参考 HydraDB f_θ', { x: s1X + 0.1, y: sY + 1.9, w: 1.5, h: 0.22, fs: 7.5, b: true, c: 'FFFFFF', a: 'center' });

// Arrow 1→2
arw(s1X + sW + 0.04, sY + sH / 2, s1X + sW + sGap - 0.04, sY + sH / 2, C.blue, 2.5);

// ── Step 2: Entity & Graph ──
const s2X = s1X + sW + sGap;
rect(s2X, sY, sW, sH, C.greenBg, C.green, 1.5, 0.08);
oval(s2X + 0.08, sY + 0.06, 0.28, 0.28, C.green, null, 0);
txt('2', { x: s2X + 0.08, y: sY + 0.06, w: 0.28, h: 0.28, fs: 12, b: true, c: 'FFFFFF', a: 'center' });
txt('实体抽取 + 关系图谱', { x: s2X + 0.42, y: sY + 0.06, w: 2.3, h: 0.28, fs: 10.5, b: true, c: C.green });

// Text-based graph representation
rect(s2X + 0.1, sY + 0.42, 2.65, 0.72, 'FFFFFF', 'D0D0D0', 0.5, 0.04);
txt('Alice ——WORKS_AT——▶ Meta', { x: s2X + 0.2, y: sY + 0.46, w: 2.4, h: 0.2, fs: 8.5, c: C.green });
txt('Alice ——DISLIKES——▶ React', { x: s2X + 0.2, y: sY + 0.66, w: 2.4, h: 0.2, fs: 8.5, c: C.red });
txt('Meta  ——USES———▶ React', { x: s2X + 0.2, y: sY + 0.86, w: 2.4, h: 0.2, fs: 8.5, c: C.textSec });

txt('• 自动提取实体和关系三元组', { x: s2X + 0.1, y: sY + 1.24, w: 2.65, h: 0.18, fs: 8.5, c: C.text });
txt('• 20+ 关系类型分类标注', { x: s2X + 0.1, y: sY + 1.44, w: 2.65, h: 0.18, fs: 8.5, c: C.text });
txt('• 双向实体查询索引', { x: s2X + 0.1, y: sY + 1.64, w: 2.65, h: 0.18, fs: 8.5, c: C.text });

rect(s2X + 0.1, sY + 1.9, 1.8, 0.22, C.green, null, 0, 0.03);
txt('参考 Supermemory Graph', { x: s2X + 0.1, y: sY + 1.9, w: 1.8, h: 0.22, fs: 7.5, b: true, c: 'FFFFFF', a: 'center' });

// Arrow 2→3
arw(s2X + sW + 0.04, sY + sH / 2, s2X + sW + sGap - 0.04, sY + sH / 2, C.green, 2.5);

// ── Step 3: Multi-vector ──
const s3X = s2X + sW + sGap;
rect(s3X, sY, sW, sH, C.orangeBg, C.orange, 1.5, 0.08);
oval(s3X + 0.08, sY + 0.06, 0.28, 0.28, C.orange, null, 0);
txt('3', { x: s3X + 0.08, y: sY + 0.06, w: 0.28, h: 0.28, fs: 12, b: true, c: 'FFFFFF', a: 'center' });
txt('多向量存储', { x: s3X + 0.42, y: sY + 0.06, w: 2.3, h: 0.28, fs: 10.5, b: true, c: C.orange });

// Three vector rows (text only, no nested rects)
txt('v_content  原文向量', { x: s3X + 0.1, y: sY + 0.44, w: 2.65, h: 0.2, fs: 9, b: true, c: C.blue });
txt('直接语义匹配', { x: s3X + 0.1, y: sY + 0.62, w: 2.65, h: 0.16, fs: 8, c: C.textSec });

txt('v_inferred  增强向量', { x: s3X + 0.1, y: sY + 0.84, w: 2.65, h: 0.2, fs: 9, b: true, c: C.green });
txt('捕获代词消解后的隐含语义', { x: s3X + 0.1, y: sY + 1.02, w: 2.65, h: 0.16, fs: 8, c: C.textSec });

txt('v_sparse  BM25 特征', { x: s3X + 0.1, y: sY + 1.24, w: 2.65, h: 0.2, fs: 9, b: true, c: C.purple });
txt('关键词精确匹配（罕见词不丢失）', { x: s3X + 0.1, y: sY + 1.42, w: 2.65, h: 0.16, fs: 8, c: C.textSec });

txt('三路融合：S = α·content + β·inferred + γ·BM25', { x: s3X + 0.1, y: sY + 1.68, w: 2.65, h: 0.18, fs: 8, b: true, c: C.orange });

rect(s3X + 0.1, sY + 1.9, 1.6, 0.22, C.orange, null, 0, 0.03);
txt('参考 HydraDB 三路检索', { x: s3X + 0.1, y: sY + 1.9, w: 1.6, h: 0.22, fs: 7.5, b: true, c: 'FFFFFF', a: 'center' });

// Arrow 3→4
arw(s3X + sW + 0.04, sY + sH / 2, s3X + sW + sGap - 0.04, sY + sH / 2, C.orange, 2.5);

// ── Step 4: Append-only ──
const s4X = s3X + sW + sGap;
rect(s4X, sY, sW, sH, C.purpleBg, C.purple, 1.5, 0.08);
oval(s4X + 0.08, sY + 0.06, 0.28, 0.28, C.purple, null, 0);
txt('4', { x: s4X + 0.08, y: sY + 0.06, w: 0.28, h: 0.28, fs: 12, b: true, c: 'FFFFFF', a: 'center' });
txt('Append-only 版本化', { x: s4X + 0.42, y: sY + 0.06, w: 2.3, h: 0.28, fs: 10.5, b: true, c: C.purple });

// Text-based timeline (no ovals/lines)
rect(s4X + 0.1, sY + 0.42, 2.65, 0.72, 'FFFFFF', 'D0D0D0', 0.5, 0.04);
txt('2024.3  LIVES_IN: NYC (startup)', { x: s4X + 0.2, y: sY + 0.46, w: 2.4, h: 0.2, fs: 8.5, c: C.textSec });
txt('2024.9  LIVES_IN: London (Meta)', { x: s4X + 0.2, y: sY + 0.66, w: 2.4, h: 0.2, fs: 8.5, c: C.purple });
txt('2025.1  PREFERS: Vue → React', { x: s4X + 0.2, y: sY + 0.86, w: 2.4, h: 0.2, fs: 8.5, c: C.purple, b: true });

txt('• 不覆盖旧值，只追加新版本', { x: s4X + 0.1, y: sY + 1.24, w: 2.65, h: 0.18, fs: 8.5, c: C.text });
txt('• 每条变更附 C_meta 变更原因', { x: s4X + 0.1, y: sY + 1.44, w: 2.65, h: 0.18, fs: 8.5, c: C.text });
txt('• 支持时间旅行查询', { x: s4X + 0.1, y: sY + 1.64, w: 2.65, h: 0.18, fs: 8.5, c: C.text });

rect(s4X + 0.1, sY + 1.9, 1.6, 0.22, C.purple, null, 0, 0.03);
txt('参考 HydraDB Ledger', { x: s4X + 0.1, y: sY + 1.9, w: 1.6, h: 0.22, fs: 7.5, b: true, c: 'FFFFFF', a: 'center' });

// ══════════════════════════════════════
// 下半部左：定期反思整理
// ══════════════════════════════════════
const botY = 3.7;
rect(0.2, botY, 5.8, 3.0, 'FAFBFD', 'DEE5ED', 1, 0.1);
txt('定期反思与记忆整理', { x: 0.35, y: botY + 0.04, w: 3, h: 0.26, fs: 13, b: true, c: C.title });
txt('后台异步执行，零运行时开销', { x: 3.5, y: botY + 0.06, w: 3, h: 0.22, fs: 9, i: true, c: C.textSec });

// Reflection cycle: 3 connected boxes
const rY = botY + 0.38, rH = 0.78, rW2 = 1.6, rGap = 0.15;
const r1X = 0.4;

// R1: Trait reflection
rect(r1X, rY, rW2, rH, C.purpleBg, C.purple, 1.5);
solidRect(r1X, rY, 0.04, rH, C.purple);
oval(r1X + 0.08, rY + 0.04, 0.24, 0.24, C.purple, null, 0);
txt('A', { x: r1X + 0.08, y: rY + 0.04, w: 0.24, h: 0.24, fs: 10, b: true, c: 'FFFFFF', a: 'center' });
txt('Trait 反思', { x: r1X + 0.36, y: rY + 0.04, w: 1.1, h: 0.24, fs: 10, b: true, c: C.purple });
txt('从 Episode 中发现', { x: r1X + 0.08, y: rY + 0.32, w: 1.4, h: 0.15, fs: 8, c: C.text });
txt('用户行为模式', { x: r1X + 0.08, y: rY + 0.46, w: 1.4, h: 0.15, fs: 8, c: C.text });
txt('9 步流水线', { x: r1X + 0.08, y: rY + 0.6, w: 1.4, h: 0.15, fs: 7.5, i: true, c: C.purple });

arw(r1X + rW2 + 0.05, rY + rH / 2, r1X + rW2 + rGap - 0.02, rY + rH / 2, C.purple, 2);

// R2: Evidence aggregation
const r2X = r1X + rW2 + rGap;
rect(r2X, rY, rW2, rH, C.orangeBg, C.orange, 1.5);
solidRect(r2X, rY, 0.04, rH, C.orange);
oval(r2X + 0.08, rY + 0.04, 0.24, 0.24, C.orange, null, 0);
txt('B', { x: r2X + 0.08, y: rY + 0.04, w: 0.24, h: 0.24, fs: 10, b: true, c: 'FFFFFF', a: 'center' });
txt('证据聚合', { x: r2X + 0.36, y: rY + 0.04, w: 1.1, h: 0.24, fs: 10, b: true, c: C.orange });
txt('支持/反驳证据链', { x: r2X + 0.08, y: rY + 0.32, w: 1.4, h: 0.15, fs: 8, c: C.text });
txt('置信度 A/B/C/D 评级', { x: r2X + 0.08, y: rY + 0.46, w: 1.4, h: 0.15, fs: 8, c: C.text });
txt('矛盾检测与消解', { x: r2X + 0.08, y: rY + 0.6, w: 1.4, h: 0.15, fs: 7.5, i: true, c: C.orange });

arw(r2X + rW2 + 0.05, rY + rH / 2, r2X + rW2 + rGap - 0.02, rY + rH / 2, C.orange, 2);

// R3: Graph consolidation
const r3X = r2X + rW2 + rGap;
rect(r3X, rY, rW2, rH, C.tealBg, C.teal, 1.5);
solidRect(r3X, rY, 0.04, rH, C.teal);
oval(r3X + 0.08, rY + 0.04, 0.24, 0.24, C.teal, null, 0);
txt('C', { x: r3X + 0.08, y: rY + 0.04, w: 0.24, h: 0.24, fs: 10, b: true, c: 'FFFFFF', a: 'center' });
txt('图谱整理', { x: r3X + 0.36, y: rY + 0.04, w: 1.1, h: 0.24, fs: 10, b: true, c: C.teal });
txt('合并冗余实体', { x: r3X + 0.08, y: rY + 0.32, w: 1.4, h: 0.15, fs: 8, c: C.text });
txt('层级摘要 L0/L1/L2', { x: r3X + 0.08, y: rY + 0.46, w: 1.4, h: 0.15, fs: 8, c: C.text });
txt('智能遗忘低Q记忆', { x: r3X + 0.08, y: rY + 0.6, w: 1.4, h: 0.15, fs: 7.5, i: true, c: C.teal });

// Bottom row: Lifecycle + outcomes
const lcY = rY + rH + 0.15;
rect(0.4, lcY, 5.4, 0.7, 'FFFFFF', C.title, 1);
txt('Trait 生命周期', { x: 0.55, y: lcY + 0.04, w: 2, h: 0.2, fs: 9, b: true, c: C.title });

// Lifecycle stages as flow
const stages = [
  { name: 'trend', c: C.textSec },
  { name: 'candidate', c: C.orange },
  { name: 'emerging', c: C.blue },
  { name: 'established', c: C.green },
  { name: 'core', c: C.purple },
];
const stX = 0.6, stY2 = lcY + 0.3, stW = 0.85;
stages.forEach((s, i) => {
  rect(stX + i * stW, stY2, stW - 0.12, 0.3, s.c, null, 0, 0.04);
  txt(s.name, { x: stX + i * stW, y: stY2, w: stW - 0.12, h: 0.3, fs: 8, b: true, c: 'FFFFFF', a: 'center' });
  if (i < stages.length - 1) {
    arw(stX + i * stW + stW - 0.14, stY2 + 0.15, stX + (i + 1) * stW + 0.02, stY2 + 0.15, s.c, 1.5);
  }
});

// Feedback loop arrow from C back to top pipeline
dashed(r3X + rW2 / 2, rY + rH, r3X + rW2 / 2, lcY + 0.7 + 0.15, C.teal);
txt('持续优化', { x: r3X, y: lcY + 0.72, w: rW2, h: 0.18, fs: 7.5, c: C.teal, a: 'center', i: true });

// ══════════════════════════════════════
// 下半部右：效果对比 + 检索能力
// ══════════════════════════════════════
const rx = 6.25, rw = 6.85;
rect(rx, botY, rw, 3.0, 'FAFBFD', 'DEE5ED', 1, 0.1);
txt('结构化带来的检索能力提升', { x: rx + 0.15, y: botY + 0.04, w: 4, h: 0.26, fs: 13, b: true, c: C.title });

// Before / After comparison
const compY = botY + 0.38;
// Before
rect(rx + 0.15, compY, 3.2, 1.2, C.redBg, C.red, 1);
txt('❌  传统方式：碎片化存储', { x: rx + 0.25, y: compY + 0.02, w: 3, h: 0.22, fs: 9.5, b: true, c: C.red });
txt('"她讨厌那个框架" → 丢失主语和指代', { x: rx + 0.25, y: compY + 0.26, w: 3, h: 0.16, fs: 8, c: C.text });
txt('"I moved to the office" → 丢失职业上下文', { x: rx + 0.25, y: compY + 0.42, w: 3, h: 0.16, fs: 8, c: C.text });
txt('更新覆盖旧值 → 历史信息永久丢失', { x: rx + 0.25, y: compY + 0.58, w: 3, h: 0.16, fs: 8, c: C.text });
txt('~40% 记忆碎片化不可检索', { x: rx + 0.25, y: compY + 0.8, w: 3, h: 0.22, fs: 9, b: true, c: C.red });

// After
rect(rx + 3.5, compY, 3.2, 1.2, C.greenBg, C.green, 1);
txt('✓  结构化方式：语义网络', { x: rx + 3.6, y: compY + 0.02, w: 3, h: 0.22, fs: 9.5, b: true, c: C.green });
txt('代词消解 → 实体明确、可检索', { x: rx + 3.6, y: compY + 0.26, w: 3, h: 0.16, fs: 8, c: C.text });
txt('图谱关联 → 多跳推理可达', { x: rx + 3.6, y: compY + 0.42, w: 3, h: 0.16, fs: 8, c: C.text });
txt('Append-only → 完整时间线可回溯', { x: rx + 3.6, y: compY + 0.58, w: 3, h: 0.16, fs: 8, c: C.text });
txt('三路融合检索 → 召回率大幅提升', { x: rx + 3.6, y: compY + 0.8, w: 3, h: 0.22, fs: 9, b: true, c: C.green });

// Retrieval capability cards
const capY = compY + 1.35;
const caps = [
  { icon: '🔗', name: '图谱多跳推理', desc: 'Alice→Meta→React 关联推理', color: C.green },
  { icon: '⏱️', name: '时间旅行查询', desc: '"去年 Q3 时的技术栈是什么"', color: C.purple },
  { icon: '🎯', name: '三路融合召回', desc: '语义+增强+关键词 交叉验证', color: C.orange },
  { icon: '📊', name: 'L0/L1/L2 渐进返回', desc: '先摘要后详情，节省 83% Token', color: C.blue },
];
const capW = (rw - 0.45) / 2, capH = 0.58, capGap = 0.1;
caps.forEach((cp, i) => {
  const col = i % 2, row = Math.floor(i / 2);
  const cx = rx + 0.15 + col * (capW + capGap);
  const cy = capY + row * (capH + 0.08);
  rect(cx, cy, capW, capH, cp.color, cp.color, 1.5, 0.06);
  txt(cp.icon + ' ' + cp.name, { x: cx + 0.08, y: cy + 0.04, w: capW - 0.16, h: 0.22, fs: 10, b: true, c: 'FFFFFF' });
  txt(cp.desc, { x: cx + 0.08, y: cy + 0.28, w: capW - 0.16, h: 0.22, fs: 8.5, c: 'FFFFFF' });
});

// ── Bottom bar ──
solidRect(0, H - 0.48, W, 0.48, C.title);
richTxt([
  { text: '竞争力本质：', options: { bold: true, fontSize: 12, color: 'FFFFFF' } },
  { text: '竞品存储文本碎片，我们构建语义网络。', options: { bold: true, fontSize: 12, color: 'FFD700' } },
  { text: '  注入时增强（代词消解+图谱+多向量）+ 定期反思（Trait+证据+整理）= Agent 的结构化长期记忆', options: { fontSize: 10, color: 'B0C4DE' } },
], { x: 0.4, y: H - 0.46, w: W - 0.8, h: 0.44, a: 'center' });

const outPath = __dirname + '/memory-structure.pptx';
pptx.writeFile({ fileName: outPath }).then(() => console.log('Created: ' + outPath)).catch(console.error);
