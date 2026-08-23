(function() {
  if (window.__QBOOK_MATERIAL_YOU_LOADED__) return;
  window.__QBOOK_MATERIAL_YOU_LOADED__ = true;

  // Adapted for QBooK from eepiemi/Materialbook (GPL-3.0):
  // https://github.com/eepiemi/Materialbook
  const MATERIALYOU_PRIMARY = window.MaterialYouBridge.getMaterialYouPrimaryRgbString();
  const MATERIALYOU_ONPRIMARY = window.MaterialYouBridge.getMaterialYouOnPrimaryRgbString();
  const MATERIALYOU_PRIMARY_RGB = JSON.parse(window.MaterialYouBridge.getMaterialYouPrimaryRgb());
  const MATERIALYOU_ONPRIMARY_RGB  = JSON.parse(window.MaterialYouBridge.getMaterialYouOnPrimaryRgb());
  const EXTENDED_MATERIAL_YOU = window.MaterialYouBridge.isExtendedMaterialYouEnabled?.() === true;

  const backgroundColorRegex = /background-color\s*:\s*(rgba\s*\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d*\.?\d+)\s*\)|rgb\s*\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)|#([0-9a-fA-F]{6}))\s*;/gi;
  const backgroundRegex = /background\s*:\s*(rgba\s*\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d*\.?\d+)\s*\)|rgb\s*\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)|#([0-9a-fA-F]{6}))\s*;/gi;
  const colorRegex = /color\s*:\s*(rgba\s*\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d*\.?\d+)\s*\)|rgb\s*\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)|#([0-9a-fA-F]{6}))\s*;/gi;
  const borderRegex = /border\s*:\s*(\d{1}px)\s+solid\s+(rgba\s*\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d*\.?\d+)\s*\)|rgb\s*\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)|#([0-9a-fA-F]{6}))\s*;/gi;
  const borderColorRegex = /border-color\s*:\s*(rgba\s*\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d*\.?\d+)\s*\)|rgb\s*\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)|#([0-9a-fA-F]{6}))\s*;/gi;

  function isFacebookBlue(r, g, b) {
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    const saturation = max - min;

    return (
      b > r + 40 &&
      b > g + 20 &&
      saturation > 60 &&
      b > 100
    );
  }

  // Mechanism to prevent some parts of the code infinitely triggering the MO, causing the function to loop
  const processedStyles = new WeakSet();

  function processMaterialYouStyles() {
    document.querySelectorAll('style')?.forEach(style => {
      if (processedStyles.has(style)) return;
      processedStyles.add(style);

      if (style.innerHTML) {
        style.innerHTML = style.innerHTML?.replace(backgroundColorRegex, (m, gr, Ar, Ag, Ab, Aa, r, g, b, hex) => {
          if ((r && g && b && isFacebookBlue(+r, +g, +b)) ||
          (hex && isFacebookBlue(...[0, 2, 4].map(i => parseInt(hex.slice(i, i + 2), 16))))) return `background-color:${MATERIALYOU_PRIMARY};`;
          if (Ar && Ag && Ab && isFacebookBlue(+Ar, +Ag, +Ab)) return `background-color: rgba(${MATERIALYOU_PRIMARY_RGB.r}, ${MATERIALYOU_PRIMARY_RGB.g}, ${MATERIALYOU_PRIMARY_RGB.b}, ${+Aa});`;
          if (gr === 'rgba(0,152,124,1.0)') return `background-color:${MATERIALYOU_ONPRIMARY};`;
          if (gr === 'rgba(231,243,255,1.0)') return `background-color: rgba(${MATERIALYOU_PRIMARY_RGB.r}, ${MATERIALYOU_PRIMARY_RGB.g}, ${MATERIALYOU_PRIMARY_RGB.b}, 0.098);`;
          if (gr === 'rgba(235,245,255,1.0)') return `background-color: rgba(${MATERIALYOU_PRIMARY_RGB.r}, ${MATERIALYOU_PRIMARY_RGB.g}, ${MATERIALYOU_PRIMARY_RGB.b}, 0.2);`;
          if (gr === 'rgba(37,47,60,1.0)') return `background-color: rgb(${Object.values(MATERIALYOU_PRIMARY_RGB).map(c => Math.round(c * 0.49)).join(', ')});`;
          if (hex === '252f3c') return `background-color: rgba(${MATERIALYOU_PRIMARY_RGB.r}, ${MATERIALYOU_PRIMARY_RGB.g}, ${MATERIALYOU_PRIMARY_RGB.b}, 0.49);`;
          return m;
        });

        style.innerHTML = style.innerHTML?.replace(borderColorRegex, (m, gr, Ar, Ag, Ab, Aa, r, g, b, hex) => {
          if ((r && g && b && isFacebookBlue(+r, +g, +b)) ||
          (hex && isFacebookBlue(...[0, 2, 4].map(i => parseInt(hex.slice(i, i + 2), 16))))) return `border-color:${MATERIALYOU_PRIMARY};`;
          if (Ar && Ag && Ab && isFacebookBlue(+Ar, +Ag, +Ab)) return `border-color: rgba(${MATERIALYOU_PRIMARY_RGB.r}, ${MATERIALYOU_PRIMARY_RGB.g}, ${MATERIALYOU_PRIMARY_RGB.b}, ${+Aa});`;
          return m;
        });
      }
    });

    document.querySelectorAll('[style*="color"]')?.forEach(el => {
      const style = el.getAttribute('style')?.replace(colorRegex, (m, gr, Ar, Ag, Ab, Aa, r, g, b, hex) => {
        if ((r && g && b && isFacebookBlue(+r, +g, +b)) ||
        (hex && isFacebookBlue(...[0, 2, 4].map(i => parseInt(hex.slice(i, i + 2), 16)))) || hex === 'e8eaee') return `color:${MATERIALYOU_PRIMARY};`;
        if (Ar && Ag && Ab && isFacebookBlue(+Ar, +Ag, +Ab)) return `color: rgba(${MATERIALYOU_PRIMARY_RGB.r}, ${MATERIALYOU_PRIMARY_RGB.g}, ${MATERIALYOU_PRIMARY_RGB.b}, ${+Aa});`;
        if (gr === 'rgb(17, 17, 18)') return `color:#111112; caret-color:${MATERIALYOU_PRIMARY};`; // hacky communication between amoled_black.js and this file
        return m;
      });
      if (style !== el.getAttribute('style')) el.setAttribute('style', style);
    });

    document.querySelectorAll('[style*="background-color"]')?.forEach(el => {
      const style = el.getAttribute('style')?.replace(backgroundColorRegex, (m, gr, Ar, Ag, Ab, Aa, r, g, b, hex) => {
        if ((r && g && b && isFacebookBlue(+r, +g, +b)) ||
        (hex && isFacebookBlue(...[0, 2, 4].map(i => parseInt(hex.slice(i, i + 2), 16))))) return `background-color:${MATERIALYOU_PRIMARY};`;
        if (Ar && Ag && Ab && isFacebookBlue(+Ar, +Ag, +Ab)) return `background-color: rgba(${MATERIALYOU_PRIMARY_RGB.r}, ${MATERIALYOU_PRIMARY_RGB.g}, ${MATERIALYOU_PRIMARY_RGB.b}, ${+Aa});`;
        if (gr === 'rgb(102, 106, 114)') return `background-color: ${MATERIALYOU_PRIMARY};`;
        if (hex === '252f3c') return `background-color: transparent;`;
        return m;
      });
      if (style !== el.getAttribute('style')) el.setAttribute('style', style);
    });

    document.querySelectorAll('[style*="background"]')?.forEach(el => {
      const style = el.getAttribute('style')?.replace(backgroundRegex, (m, gr, Ar, Ag, Ab, Aa, r, g, b, hex) => {
        if ((r && g && b && isFacebookBlue(+r, +g, +b)) ||
        (hex && isFacebookBlue(...[0, 2, 4].map(i => parseInt(hex.slice(i, i + 2), 16))))) return `background:${MATERIALYOU_PRIMARY};`;
        if (Ar && Ag && Ab && isFacebookBlue(+Ar, +Ag, +Ab)) return `background: rgba(${MATERIALYOU_PRIMARY_RGB.r}, ${MATERIALYOU_PRIMARY_RGB.g}, ${MATERIALYOU_PRIMARY_RGB.b}, ${+Aa});`;
        return m;
      });
      if (style !== el.getAttribute('style')) el.setAttribute('style', style);
    });

    document.querySelectorAll('[style*="border:"]')?.forEach(el => {
      const style = el.getAttribute('style')?.replace(borderRegex, (m, px, gr, Ar, Ag, Ab, Aa, r, g, b, hex) => {
        if ((r && g && b && isFacebookBlue(+r, +g, +b)) ||
        (hex && isFacebookBlue(...[0, 2, 4].map(i => parseInt(hex.slice(i, i + 2), 16))))) return `border: ${px} solid ${MATERIALYOU_PRIMARY};`;
        if (Ar && Ag && Ab && isFacebookBlue(+Ar, +Ag, +Ab)) return `border: ${px} solid rgba(${MATERIALYOU_PRIMARY_RGB.r}, ${MATERIALYOU_PRIMARY_RGB.g}, ${MATERIALYOU_PRIMARY_RGB.b}, ${+Aa});`;
        if (gr === 'rgb(102, 106, 114)') return `border: ${px} solid ${MATERIALYOU_PRIMARY};`;
        return m;
      });
      if (style !== el.getAttribute('style')) el.setAttribute('style', style);
    });

    document.querySelectorAll('[style*="--nbc"]')?.forEach(el => {
      if (isFacebookBlue(...[1, 3, 5].map(i => parseInt(el.style.getPropertyValue('--nbc').slice(i, i + 2), 16)))) el.style.setProperty('--nbc', MATERIALYOU_PRIMARY);
    });

    // Recolor story ring on the profile page + feed FB logo in light mode
    document.querySelectorAll('img[src*="vTzapVsBgS_.webp"], img[src*="NtDLSQuICbO.webp"]')?.forEach(img => {
      if (processedStyles.has(img)) return;
      processedStyles.add(img);
      img.style.cssText += `
        background-color: ${MATERIALYOU_PRIMARY};
        mask: url(${img.src}) no-repeat center / contain;
        content-visibility: hidden;
      `;
    });

    // Swap the injected Materialbook mark on the login screen for QBooK Q branding.
    document.querySelectorAll('img[src*="DUiOg0mJTjz.webp"]')?.forEach(img => {
      const size = parseInt(img.style.maxHeight || '72', 10);
      const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="${size * 1.5}px" height="${size * 1.5}px" aria-label="QBooK">
                    <circle cx="54" cy="54" r="54" fill="${MATERIALYOU_PRIMARY}" />
                    <text x="54" y="73" text-anchor="middle" font-family="sans-serif" font-size="58" font-weight="700" fill="${MATERIALYOU_ONPRIMARY}">Q</text>
                   </svg>`;
      img.outerHTML = svg;
    });
  }

  processMaterialYouStyles();

  function materialYouCSS() {
    const style = document.createElement('style');
    const extendedCSS = EXTENDED_MATERIAL_YOU ? `
      :root {
        --qbook-material-primary: ${MATERIALYOU_PRIMARY};
        --qbook-material-on-primary: ${MATERIALYOU_ONPRIMARY};
        --qbook-material-primary-soft: rgba(${MATERIALYOU_PRIMARY_RGB.r}, ${MATERIALYOU_PRIMARY_RGB.g}, ${MATERIALYOU_PRIMARY_RGB.b}, 0.16);
        --qbook-material-primary-medium: rgba(${MATERIALYOU_PRIMARY_RGB.r}, ${MATERIALYOU_PRIMARY_RGB.g}, ${MATERIALYOU_PRIMARY_RGB.b}, 0.28);
      }
      button, [role="button"], input[type="submit"], input[type="button"] {
        accent-color: var(--qbook-material-primary) !important;
      }
      [aria-selected="true"], [aria-checked="true"], [data-selected="true"] {
        outline-color: var(--qbook-material-primary) !important;
        background-color: var(--qbook-material-primary-soft) !important;
      }
      :focus-visible {
        outline: 2px solid var(--qbook-material-primary) !important;
        outline-offset: 2px !important;
      }
      video, audio, img {
        caret-color: var(--qbook-material-primary) !important;
      }
      [style*="background-color: rgb(255, 255, 255)"],
      [style*="background-color: #ffffff"] {
        background-color: color-mix(in srgb, var(--qbook-material-primary-soft) 22%, transparent) !important;
      }
    ` : '';
    style.textContent = `
      ::selection {
        background: rgba(${MATERIALYOU_PRIMARY_RGB.r}, ${MATERIALYOU_PRIMARY_RGB.g}, ${MATERIALYOU_PRIMARY_RGB.b}, 0.5);
        color: ${MATERIALYOU_ONPRIMARY};
      }

      .wbloks_11 { --wbloks-fig-blue-tint-10: ${MATERIALYOU_ONPRIMARY} !important; }
      .wbloks_70, ._al7j a, ._aqwl { color: ${MATERIALYOU_PRIMARY}; }
      ._al7j ._al65, ._9nqa:checked + ._9nqb, .pull-to-refresh-spinner, .loading-bar-animation { background-color: ${MATERIALYOU_PRIMARY}; }
      .revamped-progress-bar-color .loading-bar-animation { background: ${MATERIALYOU_PRIMARY}; }
      .pull-to-refresh-spinner-icon { color: ${MATERIALYOU_ONPRIMARY}; }

      :root, .__fb-light-mode:root, .__fb-light-mode {
        --primary-button-background: ${MATERIALYOU_PRIMARY};
        --blue-link: ${MATERIALYOU_PRIMARY};
        --focus-ring-blue: ${MATERIALYOU_PRIMARY};
        --accent: ${MATERIALYOU_PRIMARY};
        --primary-deemphasized-button-text: ${MATERIALYOU_PRIMARY};
        --highlight-bg: rgba(${MATERIALYOU_PRIMARY_RGB.r}, ${MATERIALYOU_PRIMARY_RGB.g}, ${MATERIALYOU_PRIMARY_RGB.b}, 0.2);
        --primary-deemphasized-button-background: rgba(${MATERIALYOU_PRIMARY_RGB.r}, ${MATERIALYOU_PRIMARY_RGB.g}, ${MATERIALYOU_PRIMARY_RGB.b}, 0.2);
        --primary-deemphasized-button-pressed-overlay: rgba(${MATERIALYOU_PRIMARY_RGB.r}, ${MATERIALYOU_PRIMARY_RGB.g}, ${MATERIALYOU_PRIMARY_RGB.b}, 0.15);
      }
      ${extendedCSS}
    `;
    document.head.appendChild(style);
  }

  materialYouCSS();

  new MutationObserver(() => {
    processMaterialYouStyles();
  }).observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['style']
  });
})();