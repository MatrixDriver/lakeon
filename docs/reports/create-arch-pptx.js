const pptxgen = require('pptxgenjs');
const pptx = new pptxgen();

pptx.layout = 'LAYOUT_WIDE';
pptx.author = 'DBay Team';
pptx.title = 'DBay 数据港湾 - 系统架构';

const slide = pptx.addSlide();
slide.background = { color: 'FFFFFF' };

// ── Layout ──
const W = 13.33, H = 7.5;
const ML = 0.3;
const LW = 8.6;   // left: architecture
const RX = 9.2;   // right: competitiveness
const RW = 3.9;

// ── Colors ──
const C = {
  title: '0070C0', subtitle: '5B9BD5',
  purple: '7B5EA7', purpleLight: 'F0EBF8',
  gold: 'B8860B', goldLight: 'FFF8E1',
  amber: 'C07B10', amberLight: 'FFF3E0',
  orange: 'A0612B', orangeLight: 'FFEDE0',
  honey: 'A69020', honeyLight: 'FEFCE8',
  cogBorder: 'C8A000', cogFill: 'FFFDE7', reqFill: 'FFF9C4',
  cyan: '0078B8', cyanLight: 'E1F5FE',
  green: '1B8A4A', greenLight: 'E8F5E9',
  gray: '607D8B', grayLight: 'F5F7FA',
  text: '333333', textSec: '555555', syncArrow: '4A90D9',
  // Right column
  rBg: 'F8FAFC', rBorder: 'DEE5ED',
};

// ── Helpers ──
function addBox(o) {
  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: o.x, y: o.y, w: o.w, h: o.h, rectRadius: o.r || 0.08,
    fill: o.fill ? { color: o.fill } : undefined,
    line: o.line ? { color: o.line, width: o.lineW || 1.5 } : undefined,
  });
}
function addLabel(t, o) {
  slide.addText(t, {
    x: o.x, y: o.y, w: o.w, h: o.h || 0.3,
    fontSize: o.fs || 12, fontFace: 'Arial',
    color: o.color || '444444', bold: o.bold || false, italic: o.italic || false,
    align: o.align || 'center', valign: o.valign || 'middle',
  });
}
function addLine(x1, y1, x2, y2, color, w) {
  slide.addShape(pptx.shapes.LINE, {
    x: x1, y: y1, w: x2 - x1 || 0.001, h: y2 - y1 || 0.001,
    line: { color, width: w || 1.5 },
  });
}
// Right-side competitiveness block
function addCompBlock(y, h, titleColor, title, items) {
  addBox({ x: RX, y, w: RW, h, fill: C.rBg, line: titleColor, lineW: 1.5, r: 0.06 });
  // colored left accent bar
  slide.addShape(pptx.shapes.RECTANGLE, {
    x: RX, y: y, w: 0.06, h: h,
    fill: { color: titleColor }, line: { width: 0 },
    rectRadius: 0,
  });
  addLabel(title, { x: RX + 0.15, y: y + 0.04, w: RW - 0.25, h: 0.22, fs: 10.5, bold: true, color: titleColor, align: 'left' });
  items.forEach((item, i) => {
    addLabel('• ' + item, { x: RX + 0.15, y: y + 0.26 + i * 0.17, w: RW - 0.3, h: 0.17, fs: 9, color: C.textSec, align: 'left' });
  });
}

// ══════════════════════════════════════
//  LEFT SIDE: Architecture Diagram
// ══════════════════════════════════════

// ── Title ──
addLabel('DBay 数据港湾 - 系统架构', { x: 0, y: 0.08, w: W, h: 0.38, fs: 22, bold: true, color: C.title });
addLabel('面向 Agent 的 Serverless 数据底座', { x: 0, y: 0.4, w: W, h: 0.22, fs: 11, color: C.subtitle });

// ═══ Layer 4: Agent 接入层 ═══
const L4Y = 0.7, L4H = 0.65;
addBox({ x: ML, y: L4Y, w: LW, h: L4H, fill: C.purpleLight, line: C.purple, lineW: 2 });
addLabel('Agent 接入层', { x: ML, y: L4Y + 0.02, w: LW, h: 0.24, fs: 14, bold: true, color: C.purple });
const accessItems = ['MCP Server', 'Skill', 'Python SDK', 'REST API', '多 Agent 编排'];
const aW = LW / accessItems.length;
accessItems.forEach((t, i) => addLabel(t, { x: ML + i * aW, y: L4Y + 0.24, w: aW, h: 0.2, fs: 10, color: C.text }));
addLabel('支持 Claude / GPT / Gemini 等多种 Agent 接入 · 标准化工具协议', {
  x: ML, y: L4Y + 0.44, w: LW, h: 0.18, fs: 8.5, italic: true, color: C.purple
});

// connections
const c1Y = L4Y + L4H, c1Y2 = c1Y + 0.14;
[1.0, 2.8, 5.2, 7.6].forEach(cx => addLine(cx, c1Y, cx, c1Y2, C.purple, 1));

// ═══ Layer 3: Agent 认知数据层 ═══
const L3Y = c1Y2, L3H = 2.2;
addBox({ x: ML, y: L3Y, w: LW, h: L3H, fill: C.cogFill, line: C.cogBorder, lineW: 2 });
addLabel('Agent 认知数据层', { x: ML, y: L3Y + 0.02, w: LW, h: 0.2, fs: 12, bold: true, color: C.cogBorder });

const sY = L3Y + 0.24, sH = 1.0, sGap = 0.1;
const sW = (LW - 0.3 - sGap * 3) / 4, sX0 = ML + 0.15;
const svcs = [
  { n: '知识库', c: C.gold, f: C.goldLight, items: ['文档解析与切片', '融合检索', '表知识库', '版本管理 + 回滚'] },
  { n: '记忆库', c: C.amber, f: C.amberLight, items: ['Agent 长期记忆', '多类型记忆管理', '语义检索 + 时间衰减', '记忆压缩与合并'] },
  { n: '对话历史库', c: C.orange, f: C.orangeLight, items: ['多轮对话存储', '会话上下文管理', '轨迹回放与分析', '对话数据导出'] },
  { n: '状态库', c: C.honey, f: C.honeyLight, items: ['Agent 运行状态', '任务进度追踪', '配置与偏好存储', '检查点与恢复'] },
];
svcs.forEach((s, i) => {
  const sx = sX0 + i * (sW + sGap);
  addBox({ x: sx, y: sY, w: sW, h: sH, fill: s.f, line: s.c, lineW: 1.5 });
  addLabel(s.n, { x: sx, y: sY + 0.03, w: sW, h: 0.22, fs: 13, bold: true, color: s.c });
  s.items.forEach((it, j) => addLabel('• ' + it, { x: sx + 0.06, y: sY + 0.26 + j * 0.17, w: sW - 0.12, h: 0.17, fs: 9, color: C.text, align: 'left' }));
});

// common requirements
const rqY = sY + sH + 0.08, rqH = 0.58;
addBox({ x: sX0, y: rqY, w: LW - 0.3, h: rqH, fill: C.reqFill, line: C.cogBorder, lineW: 1, r: 0.06 });
addLabel('共性需求', { x: sX0, y: rqY + 0.02, w: LW - 0.3, h: 0.18, fs: 10, bold: true, color: C.cogBorder });
addLabel('轻量化快速弹性 · 快速启动 · 多分支支持 Agent 并行试错和恢复', { x: sX0, y: rqY + 0.18, w: LW - 0.3, h: 0.17, fs: 9.5, color: C.text });
addLabel('向量 + 全文检索 + 图 + 结构化融合检索 · 多模态数据沉淀、加工与分析', { x: sX0, y: rqY + 0.35, w: LW - 0.3, h: 0.17, fs: 9.5, color: C.text });

// connections
const c2Y = L3Y + L3H, c2Y2 = c2Y + 0.14;
[C.gold, C.amber, C.orange, C.honey].forEach((clr, i) => {
  const cx = sX0 + i * (sW + sGap) + sW / 2;
  addLine(cx, c2Y, cx, c2Y2, clr, 1);
});

// ═══ Layer 2: Lakebase + AI DataLake ═══
const L2Y = c2Y2, L2H = 0.98;
const lbW = LW * 0.48, dlW = LW * 0.44;
const lbX = ML, dlX = ML + LW - dlW, gMid = dlX - (lbX + lbW);

addBox({ x: lbX, y: L2Y, w: lbW, h: L2H, fill: C.cyanLight, line: C.cyan, lineW: 2.5 });
addLabel('Lakebase (Neon 内核)', { x: lbX, y: L2Y + 0.03, w: lbW, h: 0.24, fs: 16, bold: true, color: C.cyan });
addLabel('存算分离 · 多版本 · 时间旅行 · 分支管理', { x: lbX, y: L2Y + 0.28, w: lbW, h: 0.18, fs: 9.5, color: C.text });
addLabel('按需启动 (3ms) · 自动扩缩容 · 多租户隔离', { x: lbX, y: L2Y + 0.44, w: lbW, h: 0.18, fs: 9.5, color: C.text });
addLabel('pgvector · zhparser · AGE Graph · PostGIS', { x: lbX, y: L2Y + 0.6, w: lbW, h: 0.18, fs: 9.5, color: C.text });

const aX = lbX + lbW + gMid * 0.1, aW2 = gMid * 0.8;
addLabel('数据 →', { x: aX, y: L2Y + 0.2, w: aW2, h: 0.18, fs: 8.5, bold: true, color: C.syncArrow });
addLabel('← 同步', { x: aX, y: L2Y + 0.45, w: aW2, h: 0.18, fs: 8.5, bold: true, color: C.syncArrow });

addBox({ x: dlX, y: L2Y, w: dlW, h: L2H, fill: C.greenLight, line: C.green, lineW: 2.5 });
addLabel('AI 多模数据湖', { x: dlX, y: L2Y + 0.03, w: dlW, h: 0.24, fs: 16, bold: true, color: C.green });
addLabel('多模态格式 (Lance + 自研) · Parquet 导出', { x: dlX, y: L2Y + 0.28, w: dlW, h: 0.18, fs: 9.5, color: C.text });
addLabel('Python/Ray 任务调度 · SFT/RL 训练', { x: dlX, y: L2Y + 0.44, w: dlW, h: 0.18, fs: 9.5, color: C.text });
addLabel('Kata VM 隔离 · 增量 CDC 调度', { x: dlX, y: L2Y + 0.6, w: dlW, h: 0.18, fs: 9.5, color: C.text });

// connections
const c3Y = L2Y + L2H, c3Y2 = c3Y + 0.14;
addLine(lbX + lbW / 2, c3Y, lbX + lbW / 2, c3Y2, C.cyan, 1);
addLine(dlX + dlW / 2, c3Y, dlX + dlW / 2, c3Y2, C.green, 1);

// ═══ Layer 1: 基础设施层 ═══
const L1Y = c3Y2, L1H = H - L1Y - 0.08;
addBox({ x: ML, y: L1Y, w: LW, h: L1H, fill: C.grayLight, line: C.gray, lineW: 1 });
addLabel('基础设施层', { x: ML, y: L1Y + 0.02, w: LW, h: 0.22, fs: 13, bold: true, color: C.gray });
const infra = ['Kubernetes (CCE)', '对象存储 (OBS)', '元数据库 (RDS)', '弹性节点池', 'VPC EP', 'NPU/GPU'];
const iW = LW / infra.length;
infra.forEach((t, i) => addLabel(t, { x: ML + i * iW, y: L1Y + 0.24, w: iW, h: 0.18, fs: 9, color: C.textSec }));

addLabel('关键基础设施需求', { x: ML, y: L1Y + 0.48, w: LW, h: 0.2, fs: 10.5, bold: true, color: C.gray });
slide.addText([
  { text: '认知数据层 → ', options: { color: C.cogBorder, bold: true, fontSize: 10 } },
  { text: '高性价比低时延 Token 服务 · 高质量多模态 Embedding 模型', options: { color: C.text, fontSize: 10 } },
], { x: ML + 0.3, y: L1Y + 0.68, w: LW - 0.6, h: 0.18, fontFace: 'Arial', valign: 'middle' });
slide.addText([
  { text: 'AI 数据湖 → ', options: { color: C.green, bold: true, fontSize: 10 } },
  { text: '多租 Ray/Python 作业安全隔离 · CCI 支持 NPU Pod (关键依赖)', options: { color: C.text, fontSize: 10 } },
], { x: ML + 0.3, y: L1Y + 0.86, w: LW - 0.6, h: 0.18, fontFace: 'Arial', valign: 'middle' });

// ══════════════════════════════════════
//  RIGHT SIDE: 架构竞争力
// ══════════════════════════════════════

addLabel('架构竞争力', { x: RX, y: 0.08, w: RW, h: 0.35, fs: 18, bold: true, color: C.title });

// Layer 4 competitiveness
addCompBlock(L4Y, L4H, C.purple, '接入层竞争力', [
  '标准 MCP 协议，Agent 生态即插即用',
  '一套接口适配 Claude/GPT/Gemini 等',
  'L0/L1/L2 渐进式检索，Agent 按需下钻',
]);

// Layer 3 competitiveness
addCompBlock(L3Y, L3H, C.cogBorder, '认知数据层竞争力', [
  '9 步 Trait 反思引擎，Agent 持续学习用户',
  'Q-value 效用评分，检索即个性化(MemRL)',
  'LoCoMo 基准 82%，时序推理业界领先',
  '向量+BM25+图+结构化 RRF 融合检索',
  '多分支并行试错，Agent 可恢复探索',
  '知识全流程托管：解析→切片→Embedding→层级摘要',
  '数据飞轮：Q-value 反馈→批量优化→专用模型训练',
]);

// Layer 2 competitiveness
addCompBlock(L2Y, L2H, C.cyan, '存储引擎竞争力', [
  '国内唯一 Serverless PG，填补 Neon 空白',
  'Copy-on-Write 分支秒级创建零存储开销',
  '全能 PG：pgvector+BM25+Graph 单库融合',
  'Lance 格式向量操作快 Parquet 100 倍',
  '3 层数据飞轮：运行时→批量→模型训练',
]);

// Layer 1 competitiveness
addCompBlock(L1Y, L1H, C.gray, '基础设施竞争力', [
  '华为云 CCE 原生，按需扩缩容',
  'CCI Serverless 容器，免运维零闲置',
  'NPU/GPU 资源池支撑 AI 训练推理',
  '网络型 VPC EP 保障数据安全',
]);

// ── Save ──
const outPath = __dirname + '/dbay-architecture.pptx';
pptx.writeFile({ fileName: outPath }).then(() => console.log('Created: ' + outPath)).catch(console.error);
