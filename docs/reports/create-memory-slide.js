const pptxgen = require('pptxgenjs');
const pptx = new pptxgen();
pptx.layout = 'LAYOUT_WIDE'; // 13.33 x 7.5
pptx.author = 'DBay Team';
pptx.title = '记忆库竞争力分析';

const slide = pptx.addSlide();
slide.background = { color: 'FFFFFF' };

const W = 13.33, H = 7.5;

// ── Colors ──
const C = {
  title: '1A3A5C',     // 深蓝
  accent: '0070C0',    // 主蓝
  accent2: 'D4740E',   // 橙色强调
  gold: 'B8860B',
  green: '1B7A40',
  purple: '6B3FA0',
  red: 'C0392B',
  text: '2C3E50',
  textSec: '5D6D7E',
  lightBg: 'F0F4F8',
  cardBg: 'FAFBFD',
  greenBg: 'EBF5EC',
  purpleBg: 'F3EDF9',
  orangeBg: 'FEF3E8',
  blueBg: 'E8F0FE',
};

function box(o) {
  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: o.x, y: o.y, w: o.w, h: o.h, rectRadius: o.r || 0.06,
    fill: o.fill ? { color: o.fill } : undefined,
    line: o.line ? { color: o.line, width: o.lw || 1 } : undefined,
  });
}
function bar(x, y, w, h, color) {
  slide.addShape(pptx.shapes.RECTANGLE, { x, y, w, h, fill: { color }, line: { width: 0 } });
}
function txt(t, o) {
  slide.addText(t, {
    x: o.x, y: o.y, w: o.w, h: o.h || 0.3,
    fontSize: o.fs || 11, fontFace: 'Arial',
    color: o.c || C.text, bold: o.b || false, italic: o.i || false,
    align: o.a || 'left', valign: o.va || 'middle', lineSpacing: o.ls || undefined,
  });
}
function richTxt(arr, o) {
  slide.addText(arr, {
    x: o.x, y: o.y, w: o.w, h: o.h || 0.3, fontFace: 'Arial',
    valign: o.va || 'middle', align: o.a || 'left', lineSpacing: o.ls || undefined,
  });
}

// ══════════════════════════════════════
// Title area
// ══════════════════════════════════════
bar(0, 0, W, 0.65, C.title);
txt('记忆库：如何构建有竞争力的 Agent 记忆系统', { x: 0.4, y: 0.08, w: 10, h: 0.3, fs: 20, b: true, c: 'FFFFFF' });
txt('基于 MemRL / Supermemory / MemOS / HydraDB 等研究的竞争力分析', { x: 0.4, y: 0.35, w: 10, h: 0.22, fs: 10, c: 'B0C4DE' });

// ══════════════════════════════════════
// 左列：核心差异化能力 (3 blocks)
// ══════════════════════════════════════
const LX = 0.3, LW2 = 4.0;

txt('核心差异化能力', { x: LX, y: 0.75, w: LW2, h: 0.28, fs: 14, b: true, c: C.title });

// Block 1: Trait Reflection
const b1Y = 1.08;
box({ x: LX, y: b1Y, w: LW2, h: 1.55, fill: C.purpleBg, line: C.purple, lw: 1.5 });
bar(LX, b1Y, 0.05, 1.55, C.purple);
txt('Trait 反思引擎', { x: LX + 0.15, y: b1Y + 0.04, w: 3.5, h: 0.24, fs: 12, b: true, c: C.purple });
txt('竞品均无 — 唯一从对话中自动发现用户行为模式的系统', { x: LX + 0.15, y: b1Y + 0.26, w: 3.7, h: 0.18, fs: 8, i: true, c: C.purple });
const traits = [
  '9 步流水线：提取→聚合→置信度→证据链→生命周期',
  'Trait 成熟度：trend → emerging → established → core',
  '自动纠偏：低置信度 Trait 持续验证或淘汰',
  '敏感信息过滤：自动识别并剥离 PII',
  '跨会话、跨项目积累，形成完整用户画像',
];
traits.forEach((t, i) => txt('• ' + t, { x: LX + 0.15, y: b1Y + 0.48 + i * 0.2, w: 3.7, h: 0.2, fs: 8.5, c: C.text }));

// Block 2: Q-value
const b2Y = 2.72;
box({ x: LX, y: b2Y, w: LW2, h: 1.3, fill: C.orangeBg, line: C.accent2, lw: 1.5 });
bar(LX, b2Y, 0.05, 1.3, C.accent2);
txt('Q-value 效用评分 (MemRL)', { x: LX + 0.15, y: b2Y + 0.04, w: 3.5, h: 0.24, fs: 12, b: true, c: C.accent2 });
txt('"个性化通过检索实现，而非微调" — Jeff Dean', { x: LX + 0.15, y: b2Y + 0.26, w: 3.7, h: 0.18, fs: 8, i: true, c: C.accent2 });
const qvals = [
  '每条记忆携带效用分 → 两阶段检索：相似度召回+效用排序',
  'Q_new = Q_old + α×(reward - Q_old)，无需重训模型',
  'MemRL 研究：复杂多步任务性能提升 56%',
  '隐式反馈：召回→使用→结果 自动闭环更新',
];
qvals.forEach((t, i) => txt('• ' + t, { x: LX + 0.15, y: b2Y + 0.48 + i * 0.2, w: 3.7, h: 0.2, fs: 8.5, c: C.text }));

// Block 3: Data Flywheel
const b3Y = 4.12;
box({ x: LX, y: b3Y, w: LW2, h: 1.5, fill: C.greenBg, line: C.green, lw: 1.5 });
bar(LX, b3Y, 0.05, 1.5, C.green);
txt('三层数据飞轮 — 不可复制的护城河', { x: LX + 0.15, y: b3Y + 0.04, w: 3.5, h: 0.24, fs: 12, b: true, c: C.green });

richTxt([
  { text: 'Layer 1 运行时', options: { bold: true, fontSize: 9, color: C.green } },
  { text: '  每次会话更新 Q-value，即时个性化，零 GPU 成本', options: { fontSize: 8.5, color: C.text } },
], { x: LX + 0.15, y: b3Y + 0.32, w: 3.7, h: 0.2 });
richTxt([
  { text: 'Layer 2 平台级', options: { bold: true, fontSize: 9, color: C.green } },
  { text: '  聚合用户数据，批量清洗低质记忆，训练排序模型', options: { fontSize: 8.5, color: C.text } },
], { x: LX + 0.15, y: b3Y + 0.55, w: 3.7, h: 0.2 });
richTxt([
  { text: 'Layer 3 专用模型', options: { bold: true, fontSize: 9, color: C.green } },
  { text: '  4B-8B 记忆专用模型 (SFT+DPO+RL)', options: { fontSize: 8.5, color: C.text } },
], { x: LX + 0.15, y: b3Y + 0.78, w: 3.7, h: 0.2 });
txt('Dria 研究证实：4B 专用模型 > 70B+ 通用模型', { x: LX + 0.15, y: b3Y + 1.0, w: 3.7, h: 0.18, fs: 8, i: true, c: C.green });
txt('飞轮训练数据（Q-value 轨迹+Trait 证据链）为独有资产', { x: LX + 0.15, y: b3Y + 1.18, w: 3.7, h: 0.18, fs: 8, i: true, c: C.green });

// ══════════════════════════════════════
// 中列：架构优势 + 技术指标
// ══════════════════════════════════════
const MX = 4.55, MW = 4.2;

txt('架构与技术优势', { x: MX, y: 0.75, w: MW, h: 0.28, fs: 14, b: true, c: C.title });

// Architecture advantages
const a1Y = 1.08;
box({ x: MX, y: a1Y, w: MW, h: 2.15, fill: C.blueBg, line: C.accent, lw: 1.5 });
bar(MX, a1Y, 0.05, 2.15, C.accent);
txt('单库融合 vs 竞品多库碎片化', { x: MX + 0.15, y: a1Y + 0.04, w: 3.9, h: 0.24, fs: 12, b: true, c: C.accent });

const archItems = [
  'pgvector + BM25 + Graph 单库融合，ACID 一致性',
  '竞品需 3-4 个独立数据库，跨库查询复杂度高',
  '5 种结构化记忆类型：Fact/Episode/Trait/Procedural/Doc',
  '双时间线：valid_from/valid_until 支持时间旅行查询',
  '三路混合检索：向量+全文+图谱 → RRF 融合排序',
  'L0/L1/L2 渐进式返回，按需下钻减少 83% Token',
  'Serverless 弹性：空闲挂起，仅存储成本 ~0.1元/月',
  'Copy-on-Write 分支：Agent 试错零成本快照和回滚',
];
archItems.forEach((t, i) => txt('• ' + t, { x: MX + 0.15, y: a1Y + 0.32 + i * 0.22, w: 3.9, h: 0.22, fs: 8.5, c: C.text }));

// Benchmarks
const bmY = 3.35;
box({ x: MX, y: bmY, w: MW, h: 1.45, fill: C.cardBg, line: 'D5D8DC', lw: 1 });
txt('关键量化指标', { x: MX + 0.15, y: bmY + 0.04, w: 3.9, h: 0.24, fs: 12, b: true, c: C.title });

// Benchmark table
const tData = [
  [
    { text: '指标', options: { fill: { color: C.title }, color: 'FFFFFF', bold: true, fontSize: 8.5, align: 'center' } },
    { text: '数值', options: { fill: { color: C.title }, color: 'FFFFFF', bold: true, fontSize: 8.5, align: 'center' } },
    { text: '对比', options: { fill: { color: C.title }, color: 'FFFFFF', bold: true, fontSize: 8.5, align: 'center' } },
  ],
  [
    { text: 'Token 节省', options: { fontSize: 8.5 } },
    { text: '72-83%', options: { fontSize: 8.5, bold: true, color: C.green } },
    { text: 'vs 全量注入', options: { fontSize: 8, color: C.textSec } },
  ],
  [
    { text: 'MemRL 提升', options: { fontSize: 8.5 } },
    { text: '+56%', options: { fontSize: 8.5, bold: true, color: C.accent2 } },
    { text: '复杂多步任务', options: { fontSize: 8, color: C.textSec } },
  ],
  [
    { text: '专用模型', options: { fontSize: 8.5 } },
    { text: '4B > 70B+', options: { fontSize: 8.5, bold: true, color: C.purple } },
    { text: 'Dria mem-agent', options: { fontSize: 8, color: C.textSec } },
  ],
  [
    { text: '冷启动', options: { fontSize: 8.5 } },
    { text: '<1s 创建', options: { fontSize: 8.5, bold: true, color: C.accent } },
    { text: 'vs RDS 5-10min', options: { fontSize: 8, color: C.textSec } },
  ],
];
slide.addTable(tData, {
  x: MX + 0.15, y: bmY + 0.3, w: 3.9, h: 1.0,
  colW: [1.2, 1.1, 1.6],
  border: { pt: 0.5, color: 'D5D8DC' },
  rowH: [0.2, 0.2, 0.2, 0.2, 0.2],
  valign: 'middle',
});

// Developer memory note
const devY = 4.9;
box({ x: MX, y: devY, w: MW, h: 0.72, fill: 'FFFDE7', line: C.gold, lw: 1 });
bar(MX, devY, 0.05, 0.72, C.gold);
txt('开发者记忆（首选切入场景）', { x: MX + 0.15, y: devY + 0.02, w: 3.9, h: 0.22, fs: 10, b: true, c: C.gold });
txt('65% 开发者报告上下文丢失痛点', { x: MX + 0.15, y: devY + 0.24, w: 3.9, h: 0.18, fs: 8.5, c: C.text });
txt('自动学习：技术偏好 / 架构模式 / 测试习惯 / 决策风格', { x: MX + 0.15, y: devY + 0.42, w: 3.9, h: 0.18, fs: 8.5, c: C.text });

// ══════════════════════════════════════
// 右列：竞品对比
// ══════════════════════════════════════
const RX2 = 9.0, RW2 = 4.05;

txt('竞品差距分析', { x: RX2, y: 0.75, w: RW2, h: 0.28, fs: 14, b: true, c: C.title });

const competitors = [
  { name: 'Mem0', color: C.textSec, has: '基础事实提取', miss: 'Trait 反思 / Q-value / Graph / 层级返回' },
  { name: 'MemOS', color: C.textSec, has: '精准召回，Token 节省 72%', miss: 'Trait 反思 / Graph / 跨项目画像' },
  { name: 'Supermemory', color: C.textSec, has: '知识图谱 / 6 数据源连接器', miss: 'Trait 反思 / Q-value / 开发者场景' },
  { name: 'HydraDB', color: C.textSec, has: 'LoCoMo 90.79% / 时序图', miss: 'Q-value / 学习信号 / 闭源锁定' },
  { name: 'OpenViking', color: C.textSec, has: 'L0/L1/L2 层级 / 15k stars', miss: 'Trait 反思 / per-Agent 隔离' },
];

let compY = 1.08;
competitors.forEach((comp) => {
  const ch = 0.82;
  box({ x: RX2, y: compY, w: RW2, h: ch, fill: C.cardBg, line: 'D5D8DC', lw: 1 });
  txt(comp.name, { x: RX2 + 0.1, y: compY + 0.03, w: 1.5, h: 0.2, fs: 10.5, b: true, c: C.title });
  richTxt([
    { text: '✓ ', options: { color: C.green, fontSize: 8.5, bold: true } },
    { text: comp.has, options: { color: C.text, fontSize: 8.5 } },
  ], { x: RX2 + 0.1, y: compY + 0.24, w: 3.8, h: 0.2 });
  richTxt([
    { text: '✗ 缺 ', options: { color: C.red, fontSize: 8.5, bold: true } },
    { text: comp.miss, options: { color: C.red, fontSize: 8.5 } },
  ], { x: RX2 + 0.1, y: compY + 0.46, w: 3.8, h: 0.3, va: 'top' });
  compY += ch + 0.08;
});

// ══════════════════════════════════════
// Bottom bar: conclusion
// ══════════════════════════════════════
const btY = 5.82;
box({ x: 0.3, y: btY, w: W - 0.6, h: 0.55, fill: C.title, line: C.title, lw: 0 });
txt('竞争力公式：Trait 反思（发现用户模式）+ Q-value（检索即个性化）+ 数据飞轮（越用越聪明）+ 单库融合（架构简洁）', {
  x: 0.5, y: btY + 0.02, w: W - 1, h: 0.25, fs: 12, b: true, c: 'FFFFFF', a: 'center',
});
txt('竞品可以复制单个能力，但同时复制 Trait + Q-value + 飞轮训练数据 的组合壁垒需要 2-3 年', {
  x: 0.5, y: btY + 0.28, w: W - 1, h: 0.22, fs: 10, c: 'B0C4DE', a: 'center',
});

// footnote
txt('基于 MemRL (NeurIPS 2024) / Supermemory (Jeff Dean) / MemOS / HydraDB / OpenViking / Dria mem-agent 研究', {
  x: 0.3, y: 6.5, w: W - 0.6, h: 0.2, fs: 7.5, c: 'A0A0A0', a: 'center',
});

// ── Save ──
const outPath = __dirname + '/memory-competitiveness.pptx';
pptx.writeFile({ fileName: outPath }).then(() => console.log('Created: ' + outPath)).catch(console.error);
