const pptxgen = require('pptxgenjs');
const html2pptx = require('./html2pptx');

async function main() {
  const pptx = new pptxgen();
  pptx.layout = 'LAYOUT_16x9';
  pptx.author = 'DBay';
  pptx.title = 'Agent Memory Evolution - From Local to Cloud';

  // New slides: pain points and solution (white theme, to be inserted before existing content)
  const newSlides = [
    'slide-a-status.html',
    'slide-b-nurture.html',
    'slide-c-enterprise.html',
    'slide-d-solution.html',
  ];

  for (const s of newSlides) {
    console.log(`Processing ${s}...`);
    await html2pptx(s, pptx);
  }

  await pptx.writeFile({ fileName: 'new-slides-white.pptx' });
  console.log('Done: new-slides-white.pptx');
}

main().catch(e => { console.error(e); process.exit(1); });
