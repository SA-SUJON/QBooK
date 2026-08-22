(function() {
  if (window.__QBOOK_LABS_TOOLBOX__) return;
  window.__QBOOK_LABS_TOOLBOX__ = true;

  const style = document.createElement('style');
  style.textContent = `
    #qbook-labs-toolbox { position: fixed; right: 16px; bottom: 84px; z-index: 1000000; font-family: sans-serif; }
    #qbook-labs-toolbox button { border: 0; color: #fff; background: #0866ff; border-radius: 999px; min-width: 44px; min-height: 44px; margin: 4px; padding: 0 12px; box-shadow: 0 4px 16px rgba(0,0,0,.24); font-size: 13px; }
    #qbook-labs-toolbox .qbook-labs-panel { display: none; flex-direction: column; align-items: flex-end; margin-bottom: 6px; }
    #qbook-labs-toolbox.open .qbook-labs-panel { display: flex; }
    #qbook-labs-toolbox .qbook-labs-main { font-size: 20px; width: 50px; padding: 0; }
    body.qbook-reader-mode { background: #111 !important; }
    body.qbook-reader-mode > *:not(#qbook-labs-toolbox) { filter: grayscale(.1) contrast(1.05); }
  `;
  (document.head || document.documentElement).appendChild(style);

  const root = document.createElement('div');
  root.id = 'qbook-labs-toolbox';
  root.innerHTML = `
    <div class="qbook-labs-panel">
      <button data-action="save">Save media</button>
      <button data-action="batch">Save all visible media</button>
      <button data-action="copy">Copy link</button>
      <button data-action="screenshot">Screenshot</button>
      <button data-action="reader">Reader mode</button>
      <button data-action="center">Download Center</button>
    </div>
    <button class="qbook-labs-main" aria-label="QBooK Labs toolbox">+</button>`;
  document.body.appendChild(root);

  root.querySelector('.qbook-labs-main').addEventListener('click', () => root.classList.toggle('open'));
  root.querySelectorAll('[data-action]').forEach(button => button.addEventListener('click', () => {
    const action = button.getAttribute('data-action');
    if (action === 'save') document.getElementById('qbook-global-downloader')?.click();
    if (action === 'batch') document.getElementById('qbook-batch-downloader')?.click();
    if (action === 'copy' && window.FBPro?.copyCurrentLink) window.FBPro.copyCurrentLink();
    if (action === 'screenshot' && window.FBPro?.captureScreenshot) window.FBPro.captureScreenshot();
    if (action === 'center' && window.FBPro?.openLabsDownloadCenter) window.FBPro.openLabsDownloadCenter();
    if (action === 'reader') document.body.classList.toggle('qbook-reader-mode');
    root.classList.remove('open');
  }));
})();
