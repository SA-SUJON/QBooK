(function() {
  if (window.__QBOOK_MATERIALBOOK_OVERRIDES__) return;
  window.__QBOOK_MATERIALBOOK_OVERRIDES__ = true;

  const bridge = window.MaterialbookOverridesBridge || {};
  const desktopMode = bridge.isDesktopModeEnabled?.() === true;
  const desktopCleanup = bridge.isDesktopCleanupEnabled?.() === true;
  const transparentProgress = bridge.isTransparentProgressEnabled?.() === true;
  const greyTap = bridge.isGreyTapEnabled?.() === true;
  const STYLE_ID = 'qbook-materialbook-overrides';

  const removeThirdInteraction = () => {
    if (!desktopMode || !desktopCleanup || !window.matchMedia('(min-width: 768px)').matches) return;
    const parents = document.querySelectorAll('.xbmvrgn.x1diwwjn');
    parents.forEach(parent => {
      const children = parent.querySelectorAll('.x10b6aqq.x1yrsyyn.xs83m0k');
      if (children.length === 4 && children[2].parentNode === parent) children[2].remove();
    });
  };

  let style = document.getElementById(STYLE_ID);
  if (style) style.remove();
  style = document.createElement('style');
  style.id = STYLE_ID;
  const css = [];
  if (transparentProgress) {
    css.push('.revamped-progress-bar-color .loading-bar-background{background:transparent !important;}');
    css.push('.loading-bar-background{background-color:transparent !important;background:transparent !important;}');
  }
  if (greyTap) {
    css.push('*{-webkit-tap-highlight-color:rgba(180,180,180,.35) !important;}');
  }
  style.textContent = css.join('');
  if (css.length) (document.head || document.documentElement).appendChild(style);

  removeThirdInteraction();
  const observer = new MutationObserver(() => removeThirdInteraction());
  observer.observe(document.body || document.documentElement, { childList: true, subtree: true });
})();
