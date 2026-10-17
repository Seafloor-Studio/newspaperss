const { chromium } = require('playwright');
const page = require('./page.js');
const shots = {
  'share-before': { toast: 'Saved for your next edition' },
  'share-offer-phone': { dim: true, dialog: 'Get its new articles in your paper?' },
  'share-offer-ttrss': { dim: true, dialog: 'Subscribe in your tt-rss, in <b>News</b>?' },
  'share-subscribed-phone': { toast: 'Example News added to your sites' },
};
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 412, height: 915 } });
  for (const [n, o] of Object.entries(shots)) {
    await p.setContent(page(o));
    await p.screenshot({ path: `${n}.png` });
  }
  await b.close();
})();
