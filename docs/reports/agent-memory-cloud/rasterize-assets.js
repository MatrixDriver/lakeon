const sharp = require('sharp');

async function createGradient(filename, w, h, c1, c2, angle = '135') {
  const x2 = angle === '135' ? '100%' : '0%';
  const y2 = '100%';
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}">
    <defs><linearGradient id="g" x1="0%" y1="0%" x2="${x2}" y2="${y2}">
      <stop offset="0%" style="stop-color:${c1}"/><stop offset="100%" style="stop-color:${c2}"/>
    </linearGradient></defs>
    <rect width="100%" height="100%" fill="url(#g)"/>
  </svg>`;
  await sharp(Buffer.from(svg)).png().toFile(filename);
}

async function main() {
  // Dark gradient backgrounds
  await createGradient('bg-dark.png', 1440, 810, '#0F0F1A', '#1A1A2E');
  await createGradient('bg-dark2.png', 1440, 810, '#1A1A2E', '#16213E');
  await createGradient('bg-dark3.png', 1440, 810, '#0D1117', '#161B22');
  await createGradient('bg-solution.png', 1440, 810, '#0A2E2A', '#1A1A2E');
  await createGradient('bg-path.png', 1440, 810, '#1A1A2E', '#0A2E2A');

  // Accent bar
  const barSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="8" height="100">
    <defs><linearGradient id="g" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" style="stop-color:#4ECDC4"/><stop offset="100%" style="stop-color:#44B3AA"/>
    </linearGradient></defs>
    <rect width="8" height="100" fill="url(#g)"/>
  </svg>`;
  await sharp(Buffer.from(barSvg)).png().toFile('accent-bar.png');

  // Red accent bar
  const redBarSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="8" height="100">
    <defs><linearGradient id="g" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" style="stop-color:#FF6B6B"/><stop offset="100%" style="stop-color:#EE5A5A"/>
    </linearGradient></defs>
    <rect width="8" height="100" fill="url(#g)"/>
  </svg>`;
  await sharp(Buffer.from(redBarSvg)).png().toFile('red-bar.png');

  console.log('Assets created');
}
main().catch(console.error);
