const pptxgen = require('pptxgenjs');
const html2pptx = require('/Users/jacky/.claude/plugins/marketplaces/anthropic-agent-skills/skills/pptx/scripts/html2pptx.js');
const path = require('path');

const WS = path.join(__dirname);

async function main() {
  const pptx = new pptxgen();
  pptx.layout = 'LAYOUT_16x9';
  pptx.author = 'Jacky Li';
  pptx.title = '1人+AI 20天构建云服务原型';

  // Slide 1: Summary with charts
  const s1 = await html2pptx(path.join(WS, 'slide1.html'), pptx);

  const techChart = s1.placeholders.find(p => p.id === 'tech-chart');
  if (techChart) {
    s1.slide.addChart(pptx.charts.BAR, [{
      name: "代码行数",
      labels: ["Java (API)", "Vue 3 (前端)", "Shell/Helm", "Python"],
      values: [30000, 27000, 10000, 7000]
    }], {
      ...techChart, barDir: 'bar', showTitle: false, showLegend: false,
      showValue: true, dataLabelPosition: 'outEnd', dataLabelColor: '333333', dataLabelFontSize: 7,
      catAxisLabelFontSize: 7, valAxisHidden: true, catAxisLineShow: false,
      valAxisLineShow: false, valAxisMajorGridShow: false, chartColors: ["2c5f8a"],
      plotArea: { fill: { color: "FFFFFF" } }
    });
  }

  const effChart = s1.placeholders.find(p => p.id === 'eff-chart');
  if (effChart) {
    s1.slide.addChart(pptx.charts.BAR, [{
      name: "效率",
      labels: ["公司基线(低)", "公司基线(高)", "本项目"],
      values: [500, 1000, 78500]
    }], {
      ...effChart, barDir: 'bar', showTitle: false, showLegend: false,
      showValue: true, dataLabelPosition: 'outEnd', dataLabelColor: '333333', dataLabelFontSize: 7,
      catAxisLabelFontSize: 7, valAxisHidden: true, catAxisLineShow: false,
      valAxisLineShow: false, valAxisMajorGridShow: false, chartColors: ["AAAAAA", "AAAAAA", "c0392b"],
      plotArea: { fill: { color: "FFFFFF" } }
    });
  }

  // Slide 2: Product screenshot + gap to production
  await html2pptx(path.join(WS, 'slide2.html'), pptx);

  // Slide 3: Recommendations with ROI chart
  const s3 = await html2pptx(path.join(WS, 'slide3.html'), pptx);

  const roiChart = s3.placeholders.find(p => p.id === 'roi-chart');
  if (roiChart) {
    s3.slide.addChart(pptx.charts.BAR, [{
      name: "效率倍数",
      labels: ["普通工程师(低)", "普通工程师(高)", "架构师级别"],
      values: [10, 20, 79]
    }], {
      ...roiChart, barDir: 'bar', showTitle: false, showLegend: false,
      showValue: true, dataLabelPosition: 'outEnd', dataLabelColor: '333333', dataLabelFontSize: 7,
      dataLabelFontFace: 'Arial',
      catAxisLabelFontSize: 7, valAxisHidden: true, catAxisLineShow: false,
      valAxisLineShow: false, valAxisMajorGridShow: false,
      chartColors: ["AAAAAA", "AAAAAA", "2c5f8a"],
      plotArea: { fill: { color: "FFFFFF" } }
    });
  }

  const outFile = path.join(__dirname, '..', 'ai-dev-report-executive-summary.pptx');
  await pptx.writeFile({ fileName: outFile });
  console.log('Created:', outFile);
}

main().catch(e => { console.error(e); process.exit(1); });
