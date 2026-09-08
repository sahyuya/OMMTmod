(() => {
  'use strict';
  document.documentElement.classList.add('js');
  const key = 'ommt-wiki-theme';
  let mode = 'auto';
  try { const saved = localStorage.getItem(key); if (['auto','light','dark'].includes(saved)) mode = saved; } catch {}
  const button = document.querySelector('.theme-button');
  const ja = document.documentElement.lang === 'ja';
  function apply() {
    if (mode === 'auto') delete document.documentElement.dataset.theme;
    else document.documentElement.dataset.theme = mode;
    const label = ja ? {auto:'自動',light:'ライト',dark:'ダーク'}[mode] : mode;
    if (button) { button.textContent = label; button.setAttribute('aria-label',(ja?'配色: ':'Theme: ')+label); }
  }
  apply();
  button?.addEventListener('click', () => { mode = ['auto','light','dark'][(['auto','light','dark'].indexOf(mode)+1)%3]; apply(); try { localStorage.setItem(key,mode); } catch {} });
})();
