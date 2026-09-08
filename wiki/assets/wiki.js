(() => {
  'use strict';
  const ja = document.documentElement.lang === 'ja';
  const menu = document.querySelector('.menu-button'), sidebar = document.querySelector('.sidebar');
  menu?.addEventListener('click', () => menu.setAttribute('aria-expanded',String(sidebar.classList.toggle('is-open'))));
  const dialog = document.querySelector('.search-dialog'), input = document.querySelector('#search-input');
  const list = document.querySelector('.search-results'), status = document.querySelector('.search-status');
  const normalize = s => s.normalize('NFKC').toLowerCase().replace(/\s+/g,' ').trim();
  const entries = (window.OMMT_SEARCH_INDEX || []).filter(e=>e.lang===document.documentElement.lang).map(e=>({...e, haystack:normalize(e.title+' '+e.section+' '+e.text)}));
  const aliases = {'パン':'定位','ベロシティ':'音量','インポート':'読み込','アップロード':'送信','セーブ':'保存','音色':'楽器','パート移動':'既存のパート','再生できない':'音が出ない'};
  const alternatives = term => [term,...(ja && aliases[term] ? [aliases[term]] : [])];
  function results(query) {
    const terms=normalize(query).split(' ').filter(Boolean);
    return entries.map(e=>{
      if(!terms.every(t=>alternatives(t).some(a=>e.haystack.includes(a))))return null;
      const heading=normalize(e.title+' '+e.section);
      return {...e,score:terms.reduce((sum,t)=>sum+(alternatives(t).some(a=>heading.includes(a))?8:1),0)};
    }).filter(Boolean).sort((a,b)=>b.score-a.score);
  }
  function highlight(text,query) {
    const frag=document.createDocumentFragment(), tokens=normalize(query).split(' ').filter(Boolean).flatMap(alternatives), norm=normalize(text);
    if(norm.length!==text.length){frag.append(text);return frag;}
    let pos=0;
    while(pos<text.length){
      let best=-1,length=0;
      tokens.forEach(t=>{const at=norm.indexOf(t,pos);if(at>=0&&(best<0||at<best)){best=at;length=t.length;}});
      if(best<0){frag.append(text.slice(pos));break;}
      frag.append(text.slice(pos,best));const mark=document.createElement('mark');mark.textContent=text.slice(best,best+length);frag.append(mark);pos=best+length;
    }
    return frag;
  }
  function render() {
    const query=input.value.trim();list.replaceChildren();
    if(!query){status.textContent=ja?'調べたい言葉を入力してください。本文も検索します。':'Search article titles, headings, and text.';return;}
    const found=results(query);
    status.textContent=found.length?(ja?`${found.length}件${found.length>30?'（上位30件）':''}`:`${found.length} results${found.length>30?' (first 30 shown)':''}`):(ja?'見つかりませんでした。短い言葉や別の言い方で試してください。':'No results. Try a shorter phrase or a different word.');
    found.slice(0,30).forEach(e=>{
      const li=document.createElement('li'),a=document.createElement('a'),heading=document.createElement('strong'),snippet=document.createElement('p');
      a.href=e.href;heading.append(highlight(e.title+(e.section?' / '+e.section:''),query));
      const tokens=normalize(query).split(' ').flatMap(alternatives),at=Math.min(...tokens.map(t=>normalize(e.text).indexOf(t)).filter(i=>i>=0));
      const start=Number.isFinite(at)?Math.max(0,at-30):0;
      snippet.append(highlight((start?'…':'')+e.text.slice(start,start+150)+(e.text.length>start+150?'…':''),query));
      a.append(heading,snippet);li.append(a);list.append(li);a.addEventListener('click',()=>dialog.close());
    });
  }
  function open(){if(dialog.open)return;dialog.showModal();render();input.focus();input.select();}
  document.querySelector('[data-search-open]')?.addEventListener('click',open);
  document.querySelector('[data-search-close]')?.addEventListener('click',()=>dialog.close());
  input?.addEventListener('input',render);
  document.addEventListener('keydown',e=>{
    if(e.key==='/'&&!e.target.closest('input,textarea,select,[contenteditable="true"]')&&!e.ctrlKey&&!e.metaKey&&!e.altKey){e.preventDefault();open();}
  });
  dialog?.addEventListener('keydown',e=>{
    if(e.key==='Escape'){e.preventDefault();dialog.close();return;}
    if(e.key==='ArrowDown'||e.key==='ArrowUp'){
      const links=[...list.querySelectorAll('a')];if(!links.length)return;e.preventDefault();
      const current=links.indexOf(document.activeElement),next=e.key==='ArrowDown'?Math.min(current+1,links.length-1):current-1;
      if(next<0)input.focus();else links[next].focus();
    }
    if(e.key==='Enter'&&e.target===input){const first=list.querySelector('a');if(first){e.preventDefault();first.click();}}
  });
  if('IntersectionObserver' in window){
    const visible=new Set(),links=[...document.querySelectorAll('.toc a')];
    const observer=new IntersectionObserver(records=>{
      records.forEach(r=>r.isIntersecting?visible.add(r.target.id):visible.delete(r.target.id));
      const current=links.find(a=>visible.has(a.hash.slice(1)));
      links.forEach(a=>{if(a===current)a.setAttribute('aria-current','location');else a.removeAttribute('aria-current');});
    },{rootMargin:'-80px 0px -35% 0px'});
    document.querySelectorAll('article section[id]').forEach(section=>observer.observe(section));
  }
})();
