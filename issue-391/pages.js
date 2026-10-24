// Mockups for issue 391: a Project Gutenberg book as a serial source. Sample data only.
const phoneCss = `
*{box-sizing:border-box;margin:0;padding:0}
body{width:412px;height:915px;overflow:hidden;background:#F7F4EC;color:#1C1B1A;font-family:Roboto,"DejaVu Sans",sans-serif;position:relative;font-size:16px}
.serif{font-family:"Noto Serif","DejaVu Serif",Georgia,serif}
.top{min-height:64px;padding-top:8px;padding-bottom:8px;display:flex;align-items:center;padding:0 16px;gap:20px}
.top h1{font:700 22px "DejaVu Serif",serif;flex:1}
.ic{font-size:24px;width:24px;text-align:center}
.pad{padding:0 16px}
.muted{color:#4A4740}
.small{font-size:13px;line-height:18px;letter-spacing:.2px}
.link{text-decoration:underline}
.btn{display:inline-block;border:1px solid #8A8477;border-radius:20px;padding:9px 20px;font-size:14px;font-weight:600;color:#4A4740}
.fill{background:#8C2F1B;color:#fff;border-color:#8C2F1B}
.text{color:#8C2F1B;font-weight:600;font-size:14px}
hr{border:0;border-top:1px solid #948D80}
.row{padding:14px 16px}
.row .t{font-size:17px}
.row .d{font-size:13px;color:#4A4740;margin-top:3px}
.h{font:700 18px "DejaVu Serif",serif;padding:18px 16px 8px}
.scrim{position:absolute;inset:0;background:rgba(0,0,0,.32)}
.dlg{position:absolute;left:24px;right:24px;top:70px;background:#EAE4D5;border-radius:28px;padding:24px}
.dlg h2{font:400 22px "DejaVu Serif",serif;margin-bottom:14px}
.field{border:1px solid #8A8477;border-radius:4px;padding:14px 12px;color:#8A8477;font-size:15px;margin:8px 0 4px}
.choice{padding:8px 0}
.choice .d{font-size:13px;color:#4A4740}
.sub{font-size:14px;font-weight:600;font-family:"DejaVu Serif",serif;margin-top:16px}
.acts{display:flex;justify-content:flex-end;gap:24px;margin-top:20px}
.radio{display:flex;gap:14px;padding:10px 0;align-items:flex-start}
.rb{width:20px;height:20px;border:2px solid #4A4740;border-radius:50%;flex:none;margin-top:2px;position:relative}
.rb.on{border-color:#8C2F1B}.rb.on:after{content:"";position:absolute;inset:3px;background:#8C2F1B;border-radius:50%}
.bar{height:8px;background:#E4DDCC;border-radius:4px;overflow:hidden;margin:10px 0 6px}
.bar i{display:block;height:100%;background:#8C2F1B}
.art{display:flex;gap:14px;padding:12px 16px 12px 16px;border-bottom:1px solid #948D80;margin-left:0}
.art .m{width:20px;text-align:center;font-size:18px;flex:none}
.art .t{font-size:16px}
.art .d{font-size:13px;color:#4A4740;margin-top:2px}
.dim .t{color:#6b675f;font-weight:300}
.note{position:absolute;left:16px;right:16px;bottom:14px;color:#8C2F1B;font-size:13px}
`;
const phone = (body) => `<!doctype html><html><head><meta charset="utf-8"><style>${phoneCss}</style></head><body>${body}</body></html>`;

const sourcesBehind = `
<div class="top"><h1>Sources</h1><span class="ic">⟳</span><span class="ic">⋮</span></div>
<div class="row"><div class="t">Your reading list</div><div class="d">Links you share to NewspapeRSS from any app</div></div><hr>
<div class="row"><div class="t">Aeon</div><div class="d">aeon.co · Full articles</div></div><hr>`;

const add = phone(`${sourcesBehind}<div class="scrim"></div>
<div class="dlg">
  <h2>Add a source</h2>
  <div class="small">Paste a website or feed address.</div>
  <div class="field">example.com</div>
  <div class="sub">Curated lists</div>
  <div class="choice"><div>Arts &amp; Letters Daily</div><div class="d">Three picks a day from essays and reviews</div></div>
  <div class="sub">A book, a part at a time</div>
  <div class="small muted" style="margin:4px 0 4px">A classic from Project Gutenberg, one part in each edition, until it ends.</div>
  <div class="choice"><div>The Adventures of Sherlock Holmes</div><div class="d">Arthur Conan Doyle · 12 stories</div></div>
  <div class="choice"><div>Pride and Prejudice</div><div class="d">Jane Austen · 61 chapters</div></div>
  <div class="choice"><div>Frankenstein</div><div class="d">Mary Shelley · 28 letters and chapters</div></div>
  <div class="choice"><div class="text">More books…</div><div class="d">Or paste a gutenberg.org book link above</div></div>
  <div class="acts"><span class="text">Cancel</span><span class="text">Add</span></div>
</div>
<div class="note">1 · Add a source: books under the curated lists</div>`);

const pace = phone(`${sourcesBehind}<div class="scrim"></div>
<div class="dlg" style="top:120px">
  <h2>The Adventures of Sherlock Holmes</h2>
  <div class="small muted">Arthur Conan Doyle · 12 stories · about 7½ hours to read</div>
  <div class="sub" style="margin-top:18px">How much in each edition?</div>
  <div class="radio"><span class="rb"></span><div><div>About 10 minutes</div><div class="small muted">44 parts</div></div></div>
  <div class="radio"><span class="rb on"></span><div><div>About 15 minutes</div><div class="small muted">30 parts · about 6 weeks of your weekday paper</div></div></div>
  <div class="radio"><span class="rb"></span><div><div>A whole story each time</div><div class="small muted">12 parts, 30 to 45 minutes each</div></div></div>
  <div class="small muted" style="margin-top:10px">Parts end at a chapter or a paragraph, never mid-sentence. It counts towards your paper's 30 minutes.</div>
  <div class="acts"><span class="text">Back</span><span class="btn fill">Start reading</span></div>
</div>
<div class="note">2 · After picking a book: how big a part</div>`);

const page = phone(`
<div class="top"><span class="ic">←</span><h1>The Adventures of Sherlock Holmes</h1><span class="ic">⋮</span></div>
<div class="pad" style="margin-top:4px">
  <div><span class="link">gutenberg.org/ebooks/1661</span> ↗</div>
  <div style="font-size:17px;margin-top:12px">Part 8 of 30 in your next edition</div>
  <div class="bar"><i style="width:23%"></i></div>
  <div class="small muted">7 parts read · about 15 minutes each · Arthur Conan Doyle</div>
  <div class="small muted">At your schedule, it ends around Nov 26.</div>
  <div style="display:flex;gap:10px;margin:16px 0 18px"><span class="btn">Pause</span><span class="btn">About 15 min ▾</span></div>
</div>
<hr>
<div class="h" style="display:flex;justify-content:space-between;align-items:baseline">Parts <span class="text" style="font-family:Roboto,'DejaVu Sans',sans-serif">Start over</span></div>
<div class="art dim"><span class="m">✓</span><div><div class="t">III. A Case of Identity, part 1 of 2</div><div class="d">16 min · Sent Friday</div></div></div>
<div class="art dim"><span class="m">✓</span><div><div class="t">III. A Case of Identity, part 2 of 2</div><div class="d">14 min · Sent Monday</div></div></div>
<div class="art"><span class="m">●</span><div><div class="t" style="font-weight:600">IV. The Boscombe Valley Mystery, part 1 of 3</div><div class="d" style="color:#8C2F1B">15 min · In your next edition</div></div></div>
<div class="art"><span class="m" style="color:#8A8477">·</span><div><div class="t">IV. The Boscombe Valley Mystery, part 2 of 3</div><div class="d">15 min</div></div></div>
<div class="art"><span class="m" style="color:#8A8477">·</span><div><div class="t">IV. The Boscombe Valley Mystery, part 3 of 3</div><div class="d">13 min</div></div></div>
<div class="art"><span class="m" style="color:#8A8477">·</span><div><div class="t">V. The Five Orange Pips</div><div class="d">14 min</div></div></div>
<div class="note">3 · The book's page: where you are, pace, pause, start over</div>`);

const done = phone(`
<div class="top"><span class="ic">←</span><h1>The Adventures of Sherlock Holmes</h1><span class="ic">⋮</span></div>
<div class="pad" style="margin-top:4px">
  <div><span class="link">gutenberg.org/ebooks/1661</span> ↗</div>
  <div style="font-size:17px;margin-top:12px">Finished: all 30 parts sent</div>
  <div class="bar"><i style="width:100%"></i></div>
  <div class="small muted">Last part went out on Thursday, Nov 26.</div>
  <div style="display:flex;gap:10px;margin:16px 0 18px"><span class="btn">Remove</span><span class="btn">Read it again</span></div>
  <div class="small muted">It takes no room in your paper now. Pick another book from Add a source.</div>
</div>
<hr>
<div class="h">On Sources</div>
<div class="row" style="padding-top:4px"><div class="t">Aeon</div><div class="d">aeon.co · Full articles</div></div><hr>
<div class="h" style="padding-top:14px">Books</div>
<div class="row" style="padding-top:4px"><div class="t">Pride and Prejudice</div><div class="d">Part 12 of 40 next</div></div><hr style="margin-left:16px">
<div class="row"><div class="t">The Adventures of Sherlock Holmes</div><div class="d">Finished Nov 26</div></div><hr>
<div class="note">4 · When the book ends, and the Books group on Sources</div>`);

// E-reader pages use the book's own stylesheet, at the render tool's Kindle width.
const fs = require('fs');
const epubCss = fs.readFileSync(__dirname + '/epub.css', 'utf8');
const ereader = (body) => `<!doctype html><html><head><meta charset="utf-8"><style>
html{background:#fff}body{width:632px;height:840px;overflow:hidden;padding:40px 48px;box-sizing:border-box;font-size:22px;color:#111}
${epubCss}
.cap{position:absolute;left:48px;bottom:14px;color:#8C2F1B;font:13px Roboto,"DejaVu Sans",sans-serif}
</style></head><body>${body}</body></html>`;

const contents = ereader(`
<p class="dateline">Tuesday, October 27</p>
<h1 class="contents-title">In this edition</h1>
<p class="meta totals" style="margin-bottom:1em">4 articles · 31 min</p>
<ol class="contents">
<li><a>Why city trees are planted in pairs</a><br/><span class="meta">Aeon · 7 min</span></li>
<li><a>The quiet return of the paperback</a><br/><span class="meta">The Marginalian · 5 min</span></li>
<li><a>A short history of the night train</a><br/><span class="meta">Longreads · 4 min</span></li>
<li><a>IV. The Boscombe Valley Mystery, part 1 of 3</a><br/><span class="meta">The Adventures of Sherlock Holmes · part 8 of 30 · 15 min</span></li>
<li><a>That's all for today</a></li>
</ol>
<div class="cap">5 · The contents page: the book's part goes last, before the closing page</div>`);

const installment = ereader(`
<p class="kicker">The Adventures of Sherlock Holmes · part 8 of 30</p>
<h1 class="article-title">IV. The Boscombe Valley Mystery, part 1 of 3</h1>
<p class="byline">By Arthur Conan Doyle · 15 min read</p>
<p class="note">Last time: the end of “A Case of Identity”. This is the start of a new story.</p>
<hr class="rule"/>
<div class="article-body">
<p>We were seated at breakfast one morning, my wife and I, when the maid brought in a telegram. It was from Sherlock Holmes and ran in this way:</p>
<p>“Have you a couple of days to spare? Have just been wired for from the west of England in connection with Boscombe Valley tragedy. Shall be glad if you will come with me. Air and scenery perfect. Leave Paddington by the 11:15.”</p>
<p>“What do you say, dear?” said my wife, looking across at me. “Will you go?”</p>
</div>
<div class="cap">6 · The part's first page: kicker, part count, a line on where it picks up</div>`);

const ending = ereader(`
<div class="article-body">
<p>Lestrade laughed indulgently. “You have, no doubt, already formed your conclusions from the newspapers,” he said. “The case is as plain as a pikestaff, and the more one goes into it the plainer it becomes.”</p>
</div>
<p class="end-mark">To be continued</p>
<p class="source-link">Part 9 of 30 comes in your next edition. From Project Gutenberg, eBook #1661: <a>gutenberg.org/ebooks/1661</a></p>
<p class="article-nav">Next: That's all for today</p>
<div class="cap">7 · The part's last page: “To be continued” in place of the end mark</div>`);

module.exports = { add, pace, page, done, contents, installment, ending };
