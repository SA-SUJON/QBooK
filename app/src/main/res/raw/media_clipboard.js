(function() {
  if (window.__QBOOK_MEDIA_CLIPBOARD__) return;
  window.__QBOOK_MEDIA_CLIPBOARD__ = true;

  const BUTTON_ID = 'qbook-copy-media';
  const findImage = () => Array.from(document.querySelectorAll(
    'div[role="dialog"] img[src*="fbcdn"]:not([hidden]), img[src*="fbcdn"]:not([hidden])'
  )).find(image => {
    const rect = image.getBoundingClientRect();
    return rect.width >= 100 && rect.height >= 100 && rect.bottom >= 0 && rect.top <= window.innerHeight;
  });

  const copyCurrentImage = async () => {
    const image = findImage();
    if (!image || !window.ClipboardBridge?.copyImageToClipboard) return;
    try {
      const response = await fetch(image.currentSrc || image.src);
      const blob = await response.blob();
      const reader = new FileReader();
      reader.onloadend = () => {
        if (reader.result) window.ClipboardBridge.copyImageToClipboard(reader.result, blob.type || 'image/png');
      };
      reader.readAsDataURL(blob);
    } catch (error) {
      console.warn('QBooK image clipboard copy failed', error);
    }
  };

  const button = document.createElement('button');
  button.id = BUTTON_ID;
  button.type = 'button';
  button.title = 'Copy image to clipboard';
  button.setAttribute('aria-label', 'Copy image to clipboard');
  button.textContent = '⧉';
  button.style.cssText = 'position:fixed;right:16px;top:184px;width:44px;height:44px;border:1px solid rgba(255,255,255,.28);border-radius:50%;z-index:1000001;display:none;align-items:center;justify-content:center;background:rgba(80,90,110,.48);backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);color:var(--qbook-material-primary,#7da9ff);box-shadow:0 5px 18px rgba(0,0,0,.22);font-size:22px;cursor:pointer;';
  button.addEventListener('click', copyCurrentImage);
  document.body.appendChild(button);

  const update = () => {
    button.style.display = findImage() ? 'flex' : 'none';
  };
  new MutationObserver(update).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'style', 'class', 'hidden'] });
  window.addEventListener('scroll', update, { passive: true });
  update();
})();
