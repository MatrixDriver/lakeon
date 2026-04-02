const pptxgen = require('pptxgenjs');
const pptx = new pptxgen();
pptx.layout = 'LAYOUT_WIDE';
pptx.author = 'DBay Team';
pptx.title = '场景化记忆策略';

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
};

function rect(x,y,w,h,fill,line,lw,r){slide.addShape(pptx.shapes.ROUNDED_RECTANGLE,{x,y,w,h,rectRadius:r||0.06,fill:fill?{color:fill}:undefined,line:line?{color:line,width:lw||1}:undefined})}
function bar(x,y,w,h,c){slide.addShape(pptx.shapes.RECTANGLE,{x,y,w,h,fill:{color:c},line:{width:0}})}
function txt(t,o){slide.addText(t,{x:o.x,y:o.y,w:o.w,h:o.h||0.25,fontSize:o.fs||12,fontFace:'Arial',color:o.c||C.text,bold:o.b||false,italic:o.i||false,align:o.a||'left',valign:o.va||'middle'})}
function arw(x1,y1,x2,y2,c,w){slide.addShape(pptx.shapes.LINE,{x:x1,y:y1,w:x2-x1||0.001,h:y2-y1||0.001,line:{color:c,width:w||1.5,endArrowType:'triangle'}})}

// ── Title ──
bar(0, 0, W, 0.58, C.title);
txt('不同 Agent 场景需要不同的记忆策略', { x: 0.4, y: 0.05, w: 11, h: 0.28, fs: 19, b: true, c: 'FFFFFF' });
txt('5 类 Agent 场景 × 3 层记忆系统 = 场景化 Preset 配置', { x: 0.4, y: 0.3, w: 12, h: 0.2, fs: 10, c: 'B0C4DE' });

// ══════════════════════════════════════
// 上部：5 类 Agent 场景卡片
// ══════════════════════════════════════
const scY = 0.68;
txt('5 类 Agent 场景', { x: 0.3, y: scY, w: 3, h: 0.26, fs: 13, b: true, c: C.title });

const scenes = [
  { icon: '💻', name: '编码助手', sub: 'Claude Code / Cursor', color: C.blue, bg: C.blueBg,
    load: 'Markdown 全量注入', loadSub: 'CLAUDE.md 200行上限',
    mem: 'Decision / Rejection / Pattern', decay: '不衰减（决策长期有效）',
    retrieval: '无语义检索，全量加载', token: '<500 tokens',
    key: '人工可编辑 · Git 可追踪' },
  { icon: '🤖', name: '个人助理', sub: 'OpenClaw / ChatGPT', color: C.green, bg: C.greenBg,
    load: 'Markdown + SQLite 混合', loadSub: '核心文件全注入 + 历史搜索',
    mem: 'Fact / Trait / Episode', decay: '指数衰减 λ=0.95/周',
    retrieval: 'Vector+BM25+RRF+MMR', token: '<800 tokens',
    key: '精准召回 · 节省 72% Token' },
  { icon: '🏢', name: '业务垂直', sub: '客服 / 销售 / 法律', color: C.orange, bg: C.orangeBg,
    load: '结构化 DB 按需加载', loadSub: '客户ID精确 + 语义补充',
    mem: 'Fact / Decision / Episode', decay: '不衰减（审计合规）',
    retrieval: 'ID精确 + 语义 + RBAC', token: '按需完整加载',
    key: '审计轨迹 · Append-only' },
  { icon: '🔄', name: '多Agent协作', sub: 'CrewAI / AutoGen', color: C.purple, bg: C.purpleBg,
    load: '共享状态实时读取', loadSub: '任务ID精确查询',
    mem: 'Context / Result', decay: '任务结束整体清除',
    retrieval: '任务ID精确查询', token: '<200 tokens',
    key: '跨Agent状态共享' },
  { icon: '💬', name: '长期陪伴', sub: 'Character.AI', color: C.red, bg: C.redBg,
    load: '人格 + 情感 + 关系图谱', loadSub: '情感权重优先',
    mem: 'Trait / Episode + 情感标注', decay: '强衰减 λ=0.92/天',
    retrieval: '语义+情感+关系图谱', token: '<1500 tokens',
    key: '人格一致 · 情感记忆弧' },
];

const cardW = 2.38, cardH = 2.75, cardGap = 0.12;
const cardX0 = (W - 5 * cardW - 4 * cardGap) / 2;

scenes.forEach((sc, i) => {
  const cx = cardX0 + i * (cardW + cardGap);

  rect(cx, scY + 0.3, cardW, cardH, sc.bg, sc.color, 1.5);
  bar(cx, scY + 0.3, cardW, 0.38, sc.color);
  txt(sc.icon + '  ' + sc.name, { x: cx + 0.05, y: scY + 0.3, w: cardW - 0.1, h: 0.22, fs: 14, b: true, c: 'FFFFFF', a: 'center' });
  txt(sc.sub, { x: cx + 0.05, y: scY + 0.5, w: cardW - 0.1, h: 0.16, fs: 8, c: 'FFFFFF', a: 'center' });

  const ty = scY + 0.74;
  // Load strategy (new section - highlighted)
  txt('加载策略', { x: cx + 0.08, y: ty, w: 1.2, h: 0.16, fs: 8, b: true, c: sc.color });
  txt(sc.load, { x: cx + 0.08, y: ty + 0.16, w: cardW - 0.16, h: 0.16, fs: 8, c: C.text });
  txt(sc.loadSub, { x: cx + 0.08, y: ty + 0.3, w: cardW - 0.16, h: 0.16, fs: 7, c: C.sec, i: true });

  txt('记忆类型', { x: cx + 0.08, y: ty + 0.52, w: 1, h: 0.16, fs: 8, b: true, c: sc.color });
  txt(sc.mem, { x: cx + 0.08, y: ty + 0.68, w: cardW - 0.16, h: 0.16, fs: 8, c: C.text });

  txt('衰减 / 检索', { x: cx + 0.08, y: ty + 0.9, w: 1.5, h: 0.16, fs: 8, b: true, c: sc.color });
  txt(sc.decay, { x: cx + 0.08, y: ty + 1.06, w: cardW - 0.16, h: 0.16, fs: 7.5, c: C.text });
  txt(sc.retrieval, { x: cx + 0.08, y: ty + 1.22, w: cardW - 0.16, h: 0.16, fs: 7.5, c: C.text });

  txt('Token 预算：' + sc.token, { x: cx + 0.08, y: ty + 1.44, w: cardW - 0.16, h: 0.16, fs: 7.5, c: C.sec });

  // Key highlight at bottom
  bar(cx + 0.06, ty + 1.66, cardW - 0.12, 0.24, sc.color);
  txt('⚡ ' + sc.key, { x: cx + 0.08, y: ty + 1.66, w: cardW - 0.16, h: 0.24, fs: 8, b: true, c: 'FFFFFF', a: 'center' });
});

// ══════════════════════════════════════
// 下部：三层记忆系统矩阵
// ══════════════════════════════════════
const botY = 3.82;
rect(0.2, botY, W - 0.4, 3.1, 'FAFBFD', 'DEE5ED', 1, 0.1);
txt('统一三层记忆系统 — 同一引擎，场景化配置', { x: 0.35, y: botY + 0.04, w: 6, h: 0.26, fs: 13, b: true, c: C.title });

const layerX = 0.35, layerW = 1.6;
const contentX = layerX + layerW + 0.12;
const contentW = W - contentX - 0.35;
const colW = contentW / 5;
const sceneColors = [C.blue, C.green, C.orange, C.purple, C.red];

// Column headers
const sceneHeaders = ['💻 编码助手', '🤖 个人助理', '🏢 业务垂直', '🔄 多Agent', '💬 长期陪伴'];
const lY0 = botY + 0.36;
sceneHeaders.forEach((h, i) => {
  bar(contentX + i * colW + 0.02, lY0, colW - 0.04, 0.24, sceneColors[i]);
  txt(h, { x: contentX + i * colW + 0.02, y: lY0, w: colW - 0.04, h: 0.24, fs: 9, b: true, c: 'FFFFFF', a: 'center' });
});

// Layer rows
const lH = 0.72, lGap = 0.08;
const layers = [
  { name: '策略层', sub: '提取 / 衰减 / 排序', color: C.blue, bg: C.blueBg,
    data: [
      ['Markdown 全量', '不衰减', '全文匹配'],
      ['自动提取', '指数衰减', 'Q-value 加权'],
      ['规则提取', '不衰减(合规)', 'ID + 语义'],
      ['任务结束提取', '整体清除', '任务ID精确'],
      ['异步提取', '情感权重衰减', '情感 + 语义'],
    ]},
  { name: '组织层', sub: '类型 / 元数据 / 图谱', color: C.green, bg: C.greenBg,
    data: [
      ['Decision/Pattern', 'YAML 前缀元数据', 'Git 版本追踪'],
      ['Fact/Trait/Episode', '置信度+时间戳', '人物关系图谱'],
      ['Fact/Decision', '审计日志+RBAC', '客户关系图谱'],
      ['Context/Result', '任务依赖标注', '工作流 DAG'],
      ['Trait/Episode', '情感+人格标注', '情感关系图谱'],
    ]},
  { name: '存储层', sub: '向量 / 全文 / 图谱', color: C.orange, bg: C.orangeBg,
    data: [
      ['本地 Markdown', '+ 远程 pgvector', '人工可编辑'],
      ['pgvector + BM25', '+ sqlite-vec', 'RRF+MMR 融合'],
      ['PostgreSQL', '+ Append-only', 'RBAC 权限隔离'],
      ['Redis 共享', '+ PG 持久化', '亚秒级延迟'],
      ['pgvector + 图谱', '+ 时间线分区', '情感索引'],
    ]},
];

layers.forEach((layer, li) => {
  const ly = lY0 + 0.3 + li * (lH + lGap);
  rect(layerX, ly, layerW, lH, layer.bg, layer.color, 1.5);
  bar(layerX, ly, 0.05, lH, layer.color);
  txt(layer.name, { x: layerX + 0.1, y: ly + 0.04, w: 1.4, h: 0.24, fs: 12, b: true, c: layer.color });
  txt(layer.sub, { x: layerX + 0.1, y: ly + 0.3, w: 1.4, h: 0.36, fs: 9, c: C.sec, va: 'top' });

  layer.data.forEach((col, ci) => {
    const cx = contentX + ci * colW;
    rect(cx + 0.02, ly, colW - 0.04, lH, 'FFFFFF', 'E0E0E0', 0.5);
    col.forEach((t, j) => {
      txt('• ' + t, { x: cx + 0.06, y: ly + 0.04 + j * 0.22, w: colW - 0.12, h: 0.22, fs: 9, c: C.text });
    });
  });

  if (li < layers.length - 1) {
    arw(layerX + layerW / 2, ly + lH + 0.01, layerX + layerW / 2, ly + lH + lGap - 0.01, C.sec, 1);
  }
});

// ── Bottom bar ──
bar(0, H - 0.48, W, 0.48, C.title);
txt('统一引擎 + 场景 Preset：编码助手用 Markdown 全量注入，个人助理用精准召回，业务 Agent 用审计存储 — 一套代码，配置差异化', {
  x: 0.4, y: H - 0.46, w: W - 0.8, h: 0.44, fs: 11, b: true, c: 'FFFFFF', a: 'center',
});

const outPath = __dirname + '/memory-scenes.pptx';
pptx.writeFile({ fileName: outPath }).then(() => console.log('Created: ' + outPath)).catch(console.error);
