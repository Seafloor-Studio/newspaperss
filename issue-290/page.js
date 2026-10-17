// Builds one mockup page: a browser showing a sample article, with the share result on top.
module.exports = function page({ toast, dialog, dim }) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>
  *{box-sizing:border-box;margin:0}
  body{width:412px;height:915px;font-family:Roboto,"Noto Sans",Arial,sans-serif;background:#fff;position:relative;overflow:hidden;color:#1f1f1f}
  .status{height:28px;background:#f1f1f1;font-size:13px;padding:6px 16px;display:flex;justify-content:space-between}
  .bar{height:56px;background:#f1f1f1;display:flex;align-items:center;padding:0 12px;gap:10px;border-bottom:1px solid #ddd}
  .url{flex:1;background:#e3e3e3;border-radius:24px;padding:10px 16px;font-size:15px;color:#444}
  .art{padding:24px 20px}
  .site{font:700 14px Georgia,serif;letter-spacing:.08em;text-transform:uppercase;color:#0b5a7a}
  h1{font:700 30px/1.2 Georgia,"Noto Serif",serif;margin:14px 0 10px}
  .by{font-size:13px;color:#666;margin-bottom:18px}
  .img{height:200px;background:linear-gradient(135deg,#c9d6df,#8fa9b8);border-radius:4px;margin-bottom:18px}
  p{font:17px/1.6 Georgia,serif;color:#333;margin-bottom:14px}
  .scrim{position:absolute;inset:0;background:rgba(0,0,0,.32)}
  .toast{position:absolute;left:50%;transform:translateX(-50%);bottom:96px;background:#2f2f2f;color:#fff;font-size:14px;padding:12px 20px;border-radius:24px;white-space:nowrap}
  .dlg{position:absolute;left:28px;right:28px;top:50%;transform:translateY(-50%);background:#EAE4D5;border-radius:28px;padding:24px}
  .dlg .k{font-size:14px;color:#4A4740;margin-bottom:16px}
  .dlg h2{font:700 24px/1.3 "Noto Serif",Georgia,serif;color:#1C1B1A;margin-bottom:16px}
  .dlg .b{font-size:14px;line-height:20px;color:#4A4740;letter-spacing:.25px}
  .btns{display:flex;justify-content:flex-end;gap:8px;margin-top:24px}
  .btns span{font-weight:500;font-size:14px;color:#8C2F1B;padding:10px 12px;letter-spacing:.1px}
  </style></head><body>
  <div class="status"><span>9:41</span><span>▾ ◢ ▮</span></div>
  <div class="bar"><span>⌂</span><div class="url">example-news.org/2026/10/libraries</div><span>⋮</span></div>
  <div class="art"><div class="site">Example News</div>
  <h1>The town library that lends out seeds</h1><div class="by">By A. Writer · 6 min read</div>
  <div class="img"></div>
  <p>Every spring the shelves by the door fill with small paper envelopes: beans, poppies, three kinds of tomato. Borrowers plant them, and in autumn they bring some back.</p>
  <p>It began with one box on a librarian's desk…</p></div>
  ${dim ? '<div class="scrim"></div>' : ''}
  ${dialog ? `<div class="dlg"><div class="k">Saved for your next edition</div><h2>Example News has a feed</h2><div class="b">${dialog}</div><div class="btns"><span>Not now</span><span>Subscribe</span></div></div>` : ''}
  ${toast ? `<div class="toast">${toast}</div>` : ''}
  </body></html>`;
};
