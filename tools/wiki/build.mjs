import {readFile, writeFile, mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {pages, groups} from './content.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const output = path.join(root, 'wiki');
const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const plain = html => html.replace(/<[^>]+>/g,' ').replace(/&[^;]+;/g,' ').replace(/\s+/g,' ').trim();
const filename = (id, lang) => `${id}${lang === 'en' ? '-en' : ''}.html`;
const prop = (p, lang) => p[lang];
await mkdir(output, {recursive:true});
const version = (await readFile(path.join(root,'gradle.properties'),'utf8')).match(/^mod_version=(.+)$/m)[1].trim();
const index = [];
const labels = {
  ja: {home:'ホーム', menu:'ページ一覧', search:'Wiki内を検索', close:'閉じる', toc:'このページ', next:'次のページ', prev:'前のページ', lang:'English', theme:'配色', support:'不具合を報告', nojs:'検索にはJavaScriptが必要です。ページ一覧からも記事を探せます。', hint:'例：音が出ない、パート移動、リリース', footer:'おやさいサーバー専用 · Minecraft 26.2', skip:'本文へ移動'},
  en: {home:'Home', menu:'Contents', search:'Search the handbook', close:'Close', toc:'On this page', next:'Next', prev:'Previous', lang:'日本語', theme:'Theme', support:'Report an issue', nojs:'Search needs JavaScript. All articles are available in the contents menu.', hint:'Try: no sound, move parts, release', footer:'For the Oyasai Server · Minecraft 26.2', skip:'Skip to content'}
};
for (const lang of ['ja','en']) {
  const l = labels[lang];
  for (const [position, p] of pages.entries()) {
    const title = prop(p,lang), group = groups.find(g=>g[0]===p.group)[lang==='ja'?1:2];
    const sections = p.sections.map(s=>({id:s[0], title:s[lang==='ja'?1:3], body:s[lang==='ja'?2:4]}));
    const intro = p[lang==='ja'?'introJa':'introEn'];
    const nav = groups.map(g=>`<section class="nav-group"><h2>${esc(g[lang==='ja'?1:2])}</h2><ul>${pages.filter(q=>q.group===g[0]).map(q=>`<li><a href="${filename(q.id,lang)}"${q.id===p.id?' aria-current="page"':''}>${esc(prop(q,lang))}</a></li>`).join('')}</ul></section>`).join('');
    let body = sections.map(s=>`<section id="${s.id}"><h2>${esc(s.title)}</h2>${s.body}</section>`).join('\n');
    // Retain old bookmark anchors, even where a topic now has its own article.
    const legacy = {index:['flow-title','guide-title','before-title'],install:['versions','first-run','files'],editing:['import','parts','shortcuts'],sound:['panels','volume-pan','tempo','release'],settings:['style','theme'], 'save-upload':['preview']};
    body = (legacy[p.id] || []).filter(id=>!sections.some(s=>s.id===id)).map(id=>`<span id="${id}" class="legacy-anchor"></span>`).join('') + body;
    index.push({lang, title, section:'', href:filename(p.id,lang), text:intro});
    sections.forEach(s=>index.push({lang,title,section:s.title,href:`${filename(p.id,lang)}#${s.id}`,text:plain(s.body)}));
    const neighbor = (q,direction) => q?`<a href="${filename(q.id,lang)}"><small>${l[direction]}</small><span>${esc(prop(q,lang))}</span></a>`:'<span></span>';
    const html = `<!doctype html>
<html lang="${lang}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="description" content="${esc(intro)}"><meta name="color-scheme" content="light dark">
<title>${esc(title)} · OMMT Wiki</title><link rel="icon" href="assets/favicon.svg"><link rel="stylesheet" href="assets/styles.css">
<link rel="alternate" hreflang="${lang==='ja'?'en':'ja'}" href="${filename(p.id,lang==='ja'?'en':'ja')}">
<script src="assets/theme.js" defer></script><script src="assets/search-index.js" defer></script><script src="assets/wiki.js" defer></script></head>
<body><a class="skip" href="#main">${l.skip}</a>
<header class="topbar"><a class="brand" href="${filename('index',lang)}">OMMT<span>Wiki</span></a>
<button class="menu-button" aria-controls="sidebar" aria-expanded="false">${l.menu}</button>
<button class="search-button" data-search-open aria-haspopup="dialog">${l.search}<kbd>/</kbd></button>
<a class="language" lang="${lang==='ja'?'en':'ja'}" href="${filename(p.id,lang==='ja'?'en':'ja')}">${l.lang}</a>
<button class="theme-button" aria-label="${l.theme}" title="${l.theme}">${l.theme}</button></header>
<div class="layout"><aside class="sidebar" id="sidebar"><nav aria-label="${l.menu}">${nav}</nav><div class="sidebar-footer">OMMT ${esc(version)}<br>Minecraft 26.2</div></aside>
<main id="main" tabindex="-1"><nav class="breadcrumbs" aria-label="${lang==='ja'?'パンくず':'Breadcrumb'}"><ol><li><a href="${filename('index',lang)}">${l.home}</a></li><li>${esc(group)}</li><li aria-current="page">${esc(title)}</li></ol></nav>
<article><header class="article-header"><h1>${esc(title)}</h1><p class="intro">${esc(intro)}</p></header>
<details class="mobile-toc"><summary>${l.toc}</summary><ul>${sections.map(s=>`<li><a href="#${s.id}">${esc(s.title)}</a></li>`).join('')}</ul></details>
${body}</article><nav class="page-turn" aria-label="${lang==='ja'?'前後の記事':'Adjacent articles'}">${neighbor(pages[position-1],'prev')}${neighbor(pages[position+1],'next')}</nav>
<footer>${l.footer}<a href="${filename('help',lang)}#report">${l.support}</a></footer></main>
<aside class="toc"><nav aria-label="${l.toc}"><p>${l.toc}</p><ul>${sections.map(s=>`<li><a href="#${s.id}">${esc(s.title)}</a></li>`).join('')}</ul></nav></aside></div>
<dialog class="search-dialog" aria-labelledby="search-title"><div class="search-top"><h2 id="search-title">${l.search}</h2><button data-search-close aria-label="${l.close}">${l.close}</button></div>
<label class="sr-only" for="search-input">${l.search}</label><input id="search-input" type="search" maxlength="120" autocomplete="off" placeholder="${l.hint}">
<p class="search-status" role="status" aria-live="polite"></p><ul class="search-results"></ul></dialog>
<noscript><p class="nojs">${l.nojs}</p></noscript></body></html>`;
    await writeFile(path.join(output, filename(p.id,lang)),html);
  }
}
await writeFile(path.join(output,'assets/search-index.js'),`// Generated from article headings and body text. No network requests.\nwindow.OMMT_SEARCH_INDEX = ${JSON.stringify(index).replace(/</g,'\\u003c')};\n`);
await writeFile(path.join(output,'404.html'),`<!doctype html><html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>ページが見つかりません · OMMT Wiki</title></head><body><main><h1>ページが見つかりません</h1><p>ページ一覧から探してください。 / Please use the handbook contents.</p><p><a href="https://sahyuya.github.io/OMMTmod/">OMMT Wikiへ / Back to the handbook</a></p></main></body></html>`);
console.log(`Built ${pages.length * 2} articles and ${index.length} search entries.`);
