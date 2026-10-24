const { chromium } = require('playwright');
const pages = require('./pages.js');
(async () => {
  const b = await chromium.launch();
  for (const [n, html] of Object.entries(pages)) {
    const er = ['contents', 'installment', 'ending'].includes(n);
    const p = await b.newPage({ viewport: er ? { width: 632, height: 840 } : { width: 412, height: 915 } });
    await p.setContent(html);
    await p.screenshot({ path: `${n}.png` });
    await p.close();
  }
  await b.close();
})();
