import {readFile,readdir,mkdir} from 'node:fs/promises';
import {createServer} from 'node:http';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
import assert from 'node:assert/strict';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const dir=path.join(root,'wiki');
const names=(await readdir(dir)).filter(n=>n.endsWith('.html'));
const documents=new Map(await Promise.all(names.map(async n=>[n,await readFile(path.join(dir,n),'utf8')])));
for(const [name,html] of documents){
  const ids=[...html.matchAll(/\bid="([^"]+)"/g)].map(m=>m[1]);
  assert.equal(ids.length,new Set(ids).size,`${name}: duplicate IDs`);
  assert.equal((html.match(/<h1[ >]/g)||[]).length,1,`${name}: h1`);
  for(const match of html.matchAll(/(?:href|src)="([^"]+)"/g)){
    const href=match[1];if(/^[a-z]+:/i.test(href))continue;
    const [target,anchor]=href.split('#');const file=target||name;
    const text=documents.get(file)||await readFile(path.join(dir,file),'utf8');
    if(anchor)assert.ok(text.includes(`id="${anchor}"`),`${name} -> ${href}`);
  }
}
console.log(`PASS: ${names.length} HTML documents, local links, assets, fragments, unique IDs.`);
if(!process.env.WIKI_NODE_MODULES){console.log('Browser checks skipped: set WIKI_NODE_MODULES to an existing Playwright installation.');process.exit(0);}
const require=createRequire(import.meta.url);
const {chromium}=require(require.resolve('playwright',{paths:[process.env.WIKI_NODE_MODULES]}));
const server=createServer(async(req,res)=>{
  try{
    const url=new URL(req.url,'http://localhost');
    if(!url.pathname.startsWith('/OMMTmod/')){res.writeHead(404);res.end();return;}
    const relative=decodeURIComponent(url.pathname.slice('/OMMTmod/'.length))||'index.html';
    const file=path.resolve(dir,relative);if(!file.startsWith(dir+path.sep))throw Error('path');
    const bytes=await readFile(file);const ext=path.extname(file);
    res.setHeader('Content-Type',({'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml'})[ext]||'application/octet-stream');res.end(bytes);
  }catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const base=`http://127.0.0.1:${server.address().port}/OMMTmod/`;
let browser;
try{
  browser=await chromium.launch({headless:true,channel:'msedge'});
  const page=await browser.newPage({viewport:{width:1440,height:1000},colorScheme:'light'});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  for(const name of names.filter(n=>n!=='404.html')){
    await page.goto(base+name);
    assert.equal(await page.locator('.sidebar [aria-current="page"]').count(),1,name);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,`${name}: overflow`);
    assert.equal(await page.locator('.breadcrumbs [aria-current="page"]').innerText(),await page.locator('h1').innerText());
  }
  await page.goto(base+'index.html');await page.keyboard.press('/');
  assert.equal(await page.locator('#search-input').evaluate(el=>el===document.activeElement),true);
  for(const q of ['リリース','パート移動','ベロシティ','１／１２８','ミリ秒','音が出ない']){
    await page.locator('#search-input').fill(q);assert.ok(await page.locator('.search-results a').count()>0,`search ${q}`);
  }
  await page.locator('#search-input').fill('<img src=x onerror=alert(1)>');
  assert.equal(await page.locator('.search-results img').count(),0);
  await page.locator('#search-input').fill('存在しないキーワードxyz');assert.equal(await page.locator('.search-results a').count(),0);
  await page.locator('#search-input').fill('リリース');await page.keyboard.press('ArrowDown');
  assert.equal(await page.evaluate(()=>document.activeElement.tagName),'A');
  await page.keyboard.press('Enter');await page.waitForURL(/release/);
  const screenshots=path.join(root,'build/wiki-review');await mkdir(screenshots,{recursive:true});
  await page.goto(base+'first-song.html');await page.screenshot({path:path.join(screenshots,'desktop.png'),fullPage:true});
  await page.goto(base+'index.html');await page.screenshot({path:path.join(screenshots,'home.png')});
  await page.keyboard.press('/');await page.locator('#search-input').fill('パート移動');
  await page.screenshot({path:path.join(screenshots,'search.png')});await page.keyboard.press('Escape');
  await page.locator('dialog').waitFor({state:'hidden'});
  assert.equal(await page.locator('dialog').evaluate(el=>el.open),false);
  await page.locator('.theme-button').click();await page.locator('.theme-button').click();
  assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
  await page.reload();assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
  await page.screenshot({path:path.join(screenshots,'dark.png')});
  for(const width of [320,390,768]){
    await page.setViewportSize({width,height:844});
    for(const name of ['first-song.html','settings.html','shortcuts.html','index-en.html']){
      await page.goto(base+name);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,`${name}: overflow at ${width}`);
    }
  }
  await page.setViewportSize({width:390,height:844});await page.goto(base+'first-song.html');
  await page.locator('.menu-button').click();assert.equal(await page.locator('.menu-button').getAttribute('aria-expanded'),'true');
  assert.equal(await page.locator('.sidebar').isVisible(),true);await page.locator('.menu-button').click();
  await page.screenshot({path:path.join(screenshots,'mobile.png')});
  await page.goto(base+'index-en.html');await page.locator('[data-search-open]').click();await page.locator('#search-input').fill('retrigger');
  assert.ok(await page.locator('.search-results a').count()>0);
  const nojs=await browser.newPage({javaScriptEnabled:false});await nojs.goto(base+'first-song.html');
  assert.ok(await nojs.locator('article section').count()>=5);assert.equal(await nojs.locator('.sidebar').isVisible(),true);
  assert.deepEqual(errors,[]);
  console.log('PASS: desktop articles, search text/aliases/NFKC/empty/injection, keyboard navigation, themes, mobile 320/390/768, English, no-JS reading.');
  console.log(`Screenshots: ${screenshots}`);
}finally{await browser?.close();await new Promise(resolve=>server.close(resolve));}
