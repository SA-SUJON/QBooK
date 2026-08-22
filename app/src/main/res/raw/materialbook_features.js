(function() {
  if (window.__QBOOK_MATERIALBOOK_FEATURES__) return;
  window.__QBOOK_MATERIALBOOK_FEATURES__ = true;

  const features = window.MaterialbookFeaturesBridge || {};
  const stickyEnabled = features.isStickyNavbarEnabled?.() === true;
  const settingsEnabled = features.isInPageSettingsEnabled?.() === true;
  const selectableEnabled = features.isSelectableCaptionsEnabled?.() === true;
  const originalStyles = new WeakMap();
  const STICKY_CLASS = 'qbook-materialbook-sticky';
  const GEAR_ID = 'qbook-inpage-settings';

  const remember = (element) => {
    if (!originalStyles.has(element)) originalStyles.set(element, element.style.cssText);
  };

  const isFeed = () => {
    const path = window.location.pathname || '';
    return !/\/(stories|story|reel|reels|watch|videos|photos?|messages)\b/i.test(path);
  };

  const candidates = () => {
    const result = [];
    const selectors = [
      '[role="banner"]',
      '[data-pagelet="TopBar"]',
      '[data-pagelet*="TopBar"]',
      'header',
      '[role="navigation"]',
      '[role="tablist"]'
    ];
    selectors.forEach(selector => document.querySelectorAll(selector).forEach(element => {
      if (!result.includes(element)) result.push(element);
    }));
    return result.filter(element => element.offsetParent !== null && element.getBoundingClientRect().height > 0);
  };

  const applySticky = () => {
    const nodes = candidates();
    if (!stickyEnabled) {
      nodes.forEach(element => {
        const previous = originalStyles.get(element);
        if (previous !== undefined) element.style.cssText = previous;
      });
      return;
    }
    const nav = nodes.find(element => element.matches('[role="banner"], header, [data-pagelet*="TopBar"]'));
    const tabs = nodes.find(element => element.matches('[role="tablist"]'));
    if (nav) {
      remember(nav);
      nav.classList.add(STICKY_CLASS);
      nav.style.position = 'sticky';
      nav.style.top = '0';
      nav.style.zIndex = '1000';
      nav.style.background = getComputedStyle(nav).backgroundColor === 'rgba(0, 0, 0, 0)'
        ? 'var(--qbook-material-primary-soft, rgba(128,128,128,.12))'
        : getComputedStyle(nav).background;
    }
    if (tabs && tabs !== nav) {
      remember(tabs);
      tabs.classList.add(STICKY_CLASS);
      tabs.style.position = 'sticky';
      tabs.style.top = nav ? `${nav.getBoundingClientRect().height}px` : '0';
      tabs.style.zIndex = '999';
    }
  };

  window.backHandlerNB = () => {
    if (!isFeed()) return false;
    if (document.querySelector('[role="dialog"], [role="menu"]')) return false;
    const targets = [document.scrollingElement, document.documentElement, document.body]
      .concat(Array.from(document.querySelectorAll('[role="main"], [data-pagelet], div')))
      .filter(Boolean);
    const scrollable = targets.find(element => element.scrollHeight > element.clientHeight + 8 && element.scrollTop > 0);
    if (scrollable) {
      scrollable.scrollTo({ top: 0, behavior: 'smooth' });
      window.FBPro?.onScrollState?.(true);
      return true;
    }
    if (window.scrollY > 0) {
      window.scrollTo({ top: 0, behavior: 'smooth' });
      window.FBPro?.onScrollState?.(true);
      return true;
    }
    return false;
  };

  const selectionStyle = document.createElement('style');
  selectionStyle.id = 'qbook-materialbook-selection-style';
  selectionStyle.textContent = selectableEnabled ? `
    .native-text, .native-text * {
      -webkit-user-select: text !important;
      user-select: text !important;
      -webkit-touch-callout: default !important;
    }
    .native-text::selection, .native-text *::selection {
      background: var(--qbook-material-primary, #7da9ff) !important;
      color: var(--qbook-material-on-primary, #ffffff) !important;
    }
  ` : '';
  document.head.appendChild(selectionStyle);

  const createSettingsGear = () => {
    if (!settingsEnabled || document.getElementById(GEAR_ID)) return;
    const button = document.createElement('button');
    button.id = GEAR_ID;
    button.type = 'button';
    button.title = 'Open QBooK Control Center';
    button.setAttribute('aria-label', 'Open QBooK Control Center');
    button.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M19.43 12.98c.04-.32.07-.65.07-.98s-.02-.66-.07-.98l2.11-1.65a.5.5 0 0 0 .12-.64l-2-3.46a.5.5 0 0 0-.61-.22l-2.49 1a7.4 7.4 0 0 0-1.69-.98L14.5 2.42A.49.49 0 0 0 14 2h-4a.49.49 0 0 0-.49.42L9.13 5.07c-.6.25-1.16.58-1.69.98l-2.49-1a.49.49 0 0 0-.61.22l-2 3.46c-.14.24-.08.54.12.7l2.11 1.65c-.04.32-.08.65-.08.98s.03.66.08.98l-2.11 1.65a.5.5 0 0 0-.12.64l2 3.46c.12.24.4.34.61.22l2.49-1c.53.4 1.09.73 1.69.98l.38 2.65c.04.28.27.49.49.49h4c.25 0 .46-.21.49-.49l.38-2.65c.6-.25 1.16-.58 1.69-.98l2.49 1c.23.08.49.01.61-.22l2-3.46a.5.5 0 0 0-.12-.64zM12 15.5A3.5 3.5 0 1 1 12 8a3.5 3.5 0 0 1 0 7.5z"/></svg>';
    button.style.cssText = 'position:fixed;right:16px;top:132px;width:44px;height:44px;border:1px solid rgba(255,255,255,.28);border-radius:50%;z-index:1000001;display:flex;align-items:center;justify-content:center;background:rgba(80,90,110,.48);backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);color:var(--qbook-material-primary,#7da9ff);box-shadow:0 5px 18px rgba(0,0,0,.22);cursor:pointer;';
    button.querySelector('svg').style.cssText = 'width:24px;height:24px;';
    button.addEventListener('click', () => window.SettingsBridge?.onSettingsToggle?.());
    document.body.appendChild(button);
  };

  const observer = new MutationObserver(() => {
    applySticky();
    createSettingsGear();
  });
  observer.observe(document.documentElement, { childList: true, subtree: true });
  applySticky();
  createSettingsGear();
})();
