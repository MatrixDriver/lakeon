const pptxgen = require('pptxgenjs');
const html2pptx = require('./html2pptx');

async function main() {
  const pptx = new pptxgen();
  pptx.layout = 'LAYOUT_16x9';
  pptx.author = 'DBay';
  pptx.title = 'Agent Memory: From Local Files to Cloud';

  const slides = [
    'slide1-cover.html',
    'slide2-status.html',
    'slide3-nurture.html',
    'slide4-enterprise.html',
    'slide5-solution.html',
    'slide6-path.html',
  ];

  for (const s of slides) {
    console.log(`Processing ${s}...`);
    await html2pptx(s, pptx);
  }

  await pptx.writeFile({ fileName: 'agent-memory-cloud.pptx' });
  console.log('Done: agent-memory-cloud.pptx');
}

main().catch(e => { console.error(e); process.exit(1); });
