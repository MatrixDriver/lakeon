const pptxgen = require('pptxgenjs');
const pptx = new pptxgen();
pptx.layout = 'LAYOUT_WIDE';
pptx.author = 'DBay Team';
pptx.title = '自演进记忆策略';

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
  gold: '7D6608', goldBg: 'FDF9E8',
  cyan: '0078B8',
};

function rect(x,y,w,h,fill,line,lw,r){slide.addShape(pptx.shapes.ROUNDED_RECTANGLE,{x,y,w,h,rectRadius:r||0.06,fill:fill?{color:fill}:undefined,line:line?{color:line,width:lw||1}:undefined})}
function bar(x,y,w,h,c){slide.addShape(pptx.shapes.RECTANGLE,{x,y,w,h,fill:{color:c},line:{width:0}})}
function oval(x,y,w,h,fill){slide.addShape(pptx.shapes.OVAL,{x,y,w,h,fill:fill?{color:fill}:undefined,line:{width:0}})}
function txt(t,o){slide.addText(t,{x:o.x,y:o.y,w:o.w,h:o.h||0.25,fontSize:o.fs||12,fontFace:'Arial',color:o.c||C.text,bold:o.b||false,italic:o.i||false,align:o.a||'left',valign:o.va||'middle'})}
function richTxt(a,o){slide.addText(a,{x:o.x,y:o.y,w:o.w,h:o.h||0.25,fontFace:'Arial',valign:o.va||'middle',align:o.a||'left'})}
function arw(x1,y1,x2,y2,c,w){slide.addShape(pptx.shapes.LINE,{x:x1,y:y1,w:x2-x1||0.001,h:y2-y1||0.001,line:{color:c,width:w||2,endArrowType:'triangle'}})}
function dashed(x1,y1,x2,y2,c){slide.addShape(pptx.shapes.LINE,{x:x1,y:y1,w:x2-x1||0.001,h:y2-y1||0.001,line:{color:c,width:1.5,dashType:'dash'}})}

// ══════════════════════════════════════
// Title
// ══════════════════════════════════════
bar(0, 0, W, 0.58, C.title);
txt('自演进记忆策略：让系统自己学会如何记忆', { x: 0.4, y: 0.05, w: 11, h: 0.28, fs: 19, b: true, c: 'FFFFFF' });
txt('不预设记忆策略，而是通过数据飞轮自动发现每个场景的最优记忆方式', { x: 0.4, y: 0.3, w: 12, h: 0.2, fs: 10, c: 'B0C4DE' });

// ══════════════════════════════════════
// 核心命题 (full width)
// ══════════════════════════════════════
const ideaY = 0.66;
rect(0.25, ideaY, W - 0.5, 0.48, C.blueBg, C.blue, 1.5);
bar(0.25, ideaY, 0.06, 0.48, C.blue);
richTxt([
  { text: '核心命题：', options: { bold: true, fontSize: 13, color: C.blue } },
  { text: '记忆策略 = 一组可学习的参数', options: { bold: true, fontSize: 13, color: C.text } },
  { text: '（提取什么 / 怎么衰减 / 怎么检索 / 反思什么 / 怎么压缩）', options: { fontSize: 11, color: C.sec } },
  { text: '  →  通过 Agent 使用反馈自动调优，而非人工为每个场景设计规则', options: { fontSize: 11, color: C.blue } },
], { x: 0.45, y: ideaY + 0.02, w: W - 1, h: 0.44, a: 'center' });

// ══════════════════════════════════════
// 左列：策略参数 + 问题陈述
// ══════════════════════════════════════
const LX = 0.25, LW = 3.1;
const secY = 1.24;

txt('可学习的策略参数', { x: LX, y: secY, w: LW, h: 0.24, fs: 12, b: true, c: C.title });

const params = [
  { icon: '📥', name: '提取策略', sub: '提取什么类型？权重如何？', color: C.green, bg: C.greenBg },
  { icon: '⏳', name: '衰减函数', sub: '不同类型衰减速率？', color: C.orange, bg: C.orangeBg },
  { icon: '🔍', name: '检索公式', sub: '各信号混合权重？', color: C.blue, bg: C.blueBg },
  { icon: '🧠', name: 'Trait 焦点', sub: '反思什么行为维度？', color: C.purple, bg: C.purpleBg },
  { icon: '📦', name: '压缩策略', sub: '何时合并/归档？', color: C.gold, bg: C.goldBg },
];

let pY = secY + 0.28;
params.forEach((p) => {
  const ph = 0.42;
  rect(LX, pY, LW, ph, p.bg, p.color, 1);
  bar(LX, pY, 0.04, ph, p.color);
  txt(p.icon + ' ' + p.name, { x: LX + 0.1, y: pY + 0.02, w: 1.5, h: 0.2, fs: 11, b: true, c: p.color });
  txt(p.sub, { x: LX + 0.1, y: pY + 0.2, w: 2.8, h: 0.18, fs: 9, c: C.sec });
  // Arrow to flywheel
  arw(LX + LW + 0.04, pY + ph / 2, LX + LW + 0.28, pY + ph / 2, p.color, 1.5);
  pY += ph + 0.04;
});

// 问题：为什么不能手工设计？
const probY = pY + 0.12;
rect(LX, probY, LW, 1.3, C.redBg, C.red, 1);
bar(LX, probY, 0.04, 1.3, C.red);
txt('❌ 手工设计策略的问题', { x: LX + 0.1, y: probY + 0.04, w: 2.8, h: 0.22, fs: 11, b: true, c: C.red });
txt('• 需要领域专家，成本高', { x: LX + 0.1, y: probY + 0.28, w: 2.8, h: 0.2, fs: 10, c: C.text });
txt('• 用户行为在变，策略会过时', { x: LX + 0.1, y: probY + 0.48, w: 2.8, h: 0.2, fs: 10, c: C.text });
txt('• 无法 A/B 对比策略效果', { x: LX + 0.1, y: probY + 0.68, w: 2.8, h: 0.2, fs: 10, c: C.text });
txt('• 新场景上线周期长（月级）', { x: LX + 0.1, y: probY + 0.88, w: 2.8, h: 0.2, fs: 10, c: C.text });

// ══════════════════════════════════════
// 中间：飞轮三阶段（纵向排列，填满空间）
// ══════════════════════════════════════
const FX = 3.65, FW = 5.75;
rect(FX, secY, FW, 5.45, 'FAFBFD', 'DEE5ED', 1, 0.1);
txt('🔄 自演进飞轮', { x: FX, y: secY + 0.02, w: FW, h: 0.26, fs: 13, b: true, c: C.title, a: 'center' });

// Phase 1
const p1Y = secY + 0.32, p1H = 1.5;
rect(FX + 0.12, p1Y, FW - 0.24, p1H, C.greenBg, C.green, 2, 0.08);
oval(FX + 0.22, p1Y + 0.06, 0.6, 0.24, C.green);
txt('实时', { x: FX + 0.22, y: p1Y + 0.06, w: 0.6, h: 0.24, fs: 10, b: true, c: 'FFFFFF', a: 'center' });
txt('Phase 1：Q-value 实时反馈', { x: FX + 0.9, y: p1Y + 0.06, w: 4, h: 0.24, fs: 12, b: true, c: C.green });

txt('Agent 调用记忆 → 任务成功/失败 → Q-value 自动更新', { x: FX + 0.22, y: p1Y + 0.36, w: 5.2, h: 0.2, fs: 11, c: C.text });
txt('• 成功任务使用的记忆 → Q-value ↑  |  失败任务的记忆 → Q-value ↓', { x: FX + 0.22, y: p1Y + 0.58, w: 5.2, h: 0.2, fs: 10, c: C.text });
txt('• 更新公式：Q_new = Q_old + α×(reward - Q_old)，零 GPU，纯数值更新', { x: FX + 0.22, y: p1Y + 0.78, w: 5.2, h: 0.2, fs: 10, c: C.text });
txt('• MemRL 研究证实：Q-value 加权检索在复杂多步任务提升 56%', { x: FX + 0.22, y: p1Y + 0.98, w: 5.2, h: 0.2, fs: 10, b: true, c: C.green });

rect(FX + 0.22, p1Y + 1.22, 1.2, 0.2, C.green, null, 0, 0.03);
txt('Lakebase', { x: FX + 0.22, y: p1Y + 1.22, w: 1.2, h: 0.2, fs: 9, b: true, c: 'FFFFFF', a: 'center' });

// Arrow 1→2
arw(FX + FW / 2, p1Y + p1H + 0.02, FX + FW / 2, p1Y + p1H + 0.16, C.green, 2);
txt('Q-value 轨迹数据', { x: FX + FW / 2 - 1, y: p1Y + p1H + 0.01, w: 2, h: 0.14, fs: 8, c: C.green, a: 'center' });

// Phase 2
const p2Y = p1Y + p1H + 0.18, p2H = 1.5;
rect(FX + 0.12, p2Y, FW - 0.24, p2H, C.orangeBg, C.orange, 2, 0.08);
oval(FX + 0.22, p2Y + 0.06, 0.6, 0.24, C.orange);
txt('周级', { x: FX + 0.22, y: p2Y + 0.06, w: 0.6, h: 0.24, fs: 10, b: true, c: 'FFFFFF', a: 'center' });
txt('Phase 2：Lakebase 分支 A/B 实验', { x: FX + 0.9, y: p2Y + 0.06, w: 4, h: 0.24, fs: 12, b: true, c: C.orange });

txt('Copy-on-Write 分支 → 同一用户数据运行不同策略 → 对比效果', { x: FX + 0.22, y: p2Y + 0.36, w: 5.2, h: 0.2, fs: 11, c: C.text });
txt('• 分支 A：高衰减策略  vs  分支 B：低衰减策略 → 聚合对比胜出上线', { x: FX + 0.22, y: p2Y + 0.58, w: 5.2, h: 0.2, fs: 10, c: C.text });
txt('• DataLake 聚合 Q-value 轨迹 → 发现场景级模式（如：客服场景情绪记忆 Q-value 显著更高）', { x: FX + 0.22, y: p2Y + 0.78, w: 5.2, h: 0.2, fs: 10, c: C.text });
txt('• 自动调整：该场景的情绪提取权重 ↑、检索排序权重 ↑', { x: FX + 0.22, y: p2Y + 0.98, w: 5.2, h: 0.2, fs: 10, b: true, c: C.orange });

rect(FX + 0.22, p2Y + 1.22, 1.2, 0.2, C.orange, null, 0, 0.03);
txt('分支实验', { x: FX + 0.22, y: p2Y + 1.22, w: 1.2, h: 0.2, fs: 9, b: true, c: 'FFFFFF', a: 'center' });
rect(FX + 1.52, p2Y + 1.22, 1.2, 0.2, C.cyan, null, 0, 0.03);
txt('AI DataLake', { x: FX + 1.52, y: p2Y + 1.22, w: 1.2, h: 0.2, fs: 9, b: true, c: 'FFFFFF', a: 'center' });

// Arrow 2→3
arw(FX + FW / 2, p2Y + p2H + 0.02, FX + FW / 2, p2Y + p2H + 0.16, C.orange, 2);
txt('策略对比数据', { x: FX + FW / 2 - 1, y: p2Y + p2H + 0.01, w: 2, h: 0.14, fs: 8, c: C.orange, a: 'center' });

// Phase 3
const p3Y = p2Y + p2H + 0.18, p3H = 1.5;
rect(FX + 0.12, p3Y, FW - 0.24, p3H, C.purpleBg, C.purple, 2, 0.08);
oval(FX + 0.22, p3Y + 0.06, 0.7, 0.24, C.purple);
txt('月/季度', { x: FX + 0.22, y: p3Y + 0.06, w: 0.7, h: 0.24, fs: 10, b: true, c: 'FFFFFF', a: 'center' });
txt('Phase 3：RL 策略模型训练', { x: FX + 1.0, y: p3Y + 0.06, w: 4, h: 0.24, fs: 12, b: true, c: C.purple });

txt('导出 Q-value 轨迹 + Trait 证据链 → Lance 格式训练数据', { x: FX + 0.22, y: p3Y + 0.36, w: 5.2, h: 0.2, fs: 11, c: C.text });
txt('• RL 训练策略网络：输入(场景特征+记忆数据) → 输出(最优策略参数向量)', { x: FX + 0.22, y: p3Y + 0.58, w: 5.2, h: 0.2, fs: 10, c: C.text });
txt('• DPO 对比：好策略 vs 差策略 → 4B-8B 专用策略模型（Dria 证实 4B 专用 > 70B 通用）', { x: FX + 0.22, y: p3Y + 0.78, w: 5.2, h: 0.2, fs: 10, c: C.text });
txt('• 新场景冷启动：用已学场景的策略模型做迁移学习，几周内自动收敛', { x: FX + 0.22, y: p3Y + 0.98, w: 5.2, h: 0.2, fs: 10, b: true, c: C.purple });

rect(FX + 0.22, p3Y + 1.22, 0.9, 0.2, C.purple, null, 0, 0.03);
txt('Ray RL', { x: FX + 0.22, y: p3Y + 1.22, w: 0.9, h: 0.2, fs: 9, b: true, c: 'FFFFFF', a: 'center' });
rect(FX + 1.22, p3Y + 1.22, 1.0, 0.2, C.red, null, 0, 0.03);
txt('NPU/GPU', { x: FX + 1.22, y: p3Y + 1.22, w: 1.0, h: 0.2, fs: 9, b: true, c: 'FFFFFF', a: 'center' });

// Feedback loop: Phase 3 → Phase 1
dashed(FX + 0.06, p3Y + p3H / 2, FX - 0.08, p3Y + p3H / 2, C.purple);
dashed(FX - 0.08, p3Y + p3H / 2, FX - 0.08, p1Y + p1H / 2, C.purple);
arw(FX - 0.08, p1Y + p1H / 2, FX + 0.12, p1Y + p1H / 2, C.purple, 1.5);
txt('策略模型\n更新参数', { x: FX - 0.55, y: (p1Y + p3Y + p3H) / 2 - 0.15, w: 0.65, h: 0.35, fs: 8, c: C.purple, a: 'center' });

// ══════════════════════════════════════
// 右列：场景演进 + 壁垒
// ══════════════════════════════════════
const RX = 9.65, RW = 3.45;

txt('场景策略自动演进', { x: RX, y: secY, w: RW, h: 0.24, fs: 12, b: true, c: C.title });

const sceneCards = [
  { icon: '🎧', name: '客服', evolved: '情绪记忆权重 > 事实记忆', weeks: '~3 周', color: C.green, bg: C.greenBg },
  { icon: '💼', name: '销售', evolved: '决策人+竞品提及 重点提取', weeks: '~4 周', color: C.orange, bg: C.orangeBg },
  { icon: '📚', name: '教育', evolved: '知识点永不衰减+错题高权重', weeks: '~2 周', color: C.purple, bg: C.purpleBg },
  { icon: '🏥', name: '医疗', evolved: 'append-only + 症状时序链', weeks: '~4 周', color: C.red, bg: C.redBg },
  { icon: '⚖️', name: '法律', evolved: '先例关联 + 法规版本追踪', weeks: '~6 周', color: C.blue, bg: C.blueBg },
  { icon: '🔬', name: '研究', evolved: '论点链保留 + 矛盾检测', weeks: '~5 周', color: C.gold, bg: C.goldBg },
];

let scY = secY + 0.28;
sceneCards.forEach((sc) => {
  const sch = 0.44;
  rect(RX, scY, RW, sch, sc.bg, sc.color, 1);
  bar(RX, scY, 0.04, sch, sc.color);
  txt(sc.icon + ' ' + sc.name, { x: RX + 0.1, y: scY + 0.02, w: 0.8, h: 0.2, fs: 10, b: true, c: sc.color });
  rect(RX + RW - 0.85, scY + 0.03, 0.78, 0.18, sc.color, null, 0, 0.03);
  txt(sc.weeks + '收敛', { x: RX + RW - 0.85, y: scY + 0.03, w: 0.78, h: 0.18, fs: 8, b: true, c: 'FFFFFF', a: 'center' });
  txt('自动学会：' + sc.evolved, { x: RX + 0.1, y: scY + 0.22, w: 3.2, h: 0.18, fs: 9, c: C.text });
  scY += sch + 0.04;
});

// 跨场景迁移
const migrY = scY + 0.06;
rect(RX, migrY, RW, 0.52, C.title, C.title, 0, 0.06);
txt('跨场景迁移学习', { x: RX + 0.1, y: migrY + 0.04, w: 3.2, h: 0.2, fs: 10, b: true, c: 'FFD700' });
txt('新场景上线 → 已学策略模型热启动 → 几周内自动收敛到最优', { x: RX + 0.1, y: migrY + 0.24, w: 3.2, h: 0.24, fs: 9, c: 'FFFFFF', va: 'top' });

// 壁垒分析
const moatY = migrY + 0.6;
txt('为什么竞品无法复制', { x: RX, y: moatY, w: RW, h: 0.24, fs: 12, b: true, c: C.title });

const moats = [
  { who: 'Mem0 / MemOS', gap: '无 Q-value 反馈，策略写死在代码里' },
  { who: 'Supermemory', gap: '有图谱但无学习闭环，无法自优化' },
  { who: 'HydraDB', gap: '高精度但静态策略，不能适应场景变化' },
  { who: '手工 Preset', gap: '需领域专家，不能演进，会过时' },
];

let moY = moatY + 0.26;
moats.forEach((m) => {
  richTxt([
    { text: m.who + '：', options: { bold: true, fontSize: 9, color: C.sec } },
    { text: m.gap, options: { fontSize: 9, color: C.red } },
  ], { x: RX, y: moY, w: RW, h: 0.2 });
  moY += 0.22;
});

// ── Bottom bar ──
bar(0, H - 0.48, W, 0.48, C.title);
richTxt([
  { text: '竞争力本质：', options: { bold: true, fontSize: 13, color: 'FFFFFF' } },
  { text: '竞品在设计记忆策略，我们在学习记忆策略。', options: { bold: true, fontSize: 13, color: 'FFD700' } },
], { x: 0.4, y: H - 0.46, w: W - 0.8, h: 0.22, a: 'center' });
txt('Lakebase 分支实验  ×  DataLake 批量分析  ×  Q-value 闭环反馈  ×  Ray RL 策略训练  =  自演进飞轮', {
  x: 0.4, y: H - 0.24, w: W - 0.8, h: 0.2, fs: 10, c: 'B0C4DE', a: 'center',
});

const outPath = __dirname + '/memory-evolution-single.pptx';
pptx.writeFile({ fileName: outPath }).then(() => console.log('Created: ' + outPath)).catch(console.error);
