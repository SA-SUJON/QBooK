#!/usr/bin/env node
/**
 * Shared harness for the V3 offline system.
 *
 * Every regex and string the mirrors below use is EXTRACTED VERBATIM from
 * the production Kotlin files, so a drift between the tests and the app
 * fails here instead of shipping. The mirrors themselves are kept line-
 * for-line with the Kotlin they mirror - read them side by side, they are
 * the same algorithm in two languages.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const KT = (f) => path.join(ROOT, 'app/src/main/java/com/dustbook/app', f);
const SRC = (f) => fs.readFileSync(KT(f), 'utf8');

const assembly = SRC('offline/PageAssembly.kt');
const vault = SRC('offline/SectionVault.kt');
const docs = SRC('utils/OfflineDocs.kt');
const docsFeed = SRC('utils/OfflineFeed.kt');

// ------------------------------------------------------- verbatim extraction

/** Turn the raw interior of a Kotlin string literal into runtime text. */
function unescapeKotlin(s) {
  // Collapse escaped backslashes FIRST (behind a placeholder), then
  // escaped quotes, then restore - order is everything here.
  const PLACE = '\u0001';
  return s.split('\\\\').join(PLACE)
          .split('\\"').join('"')
          .split(PLACE).join('\\');
}

/**
 * Extract `NAME = Regex("..." [+ "..."] , RegexOption.IGNORE_CASE)` as a JS
 * RegExp, honouring the multi-line string concatenation CONTAINER uses.
 */
function kotlinRegex(src, name) {
  const i = src.indexOf(name + ' =');
  if (i < 0) throw new Error(name + ' not found');
  const j = src.indexOf('Regex(', i) + 'Regex('.length;
  const k = src.indexOf('RegexOption.IGNORE_CASE', j);
  if (j < 0 || k < 0) throw new Error(name + ' has no IGNORE_CASE regex');
  const region = src.slice(j, k);
  const parts = [...region.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1]);
  if (!parts.length) throw new Error(name + ' pattern not found');
  // Kotlin's Regex.replace replaces EVERY occurrence, so the mirror must
  // be global or a second script block inside one card would survive.
  return new RegExp(parts.map(unescapeKotlin).join(''), 'gi');
}

/** Extract a one-literal `const val NAME = "..."`. */
function kotlinConst(src, name) {
  const m = src.match(new RegExp(name + ' =\\s*\\n?\\s*"((?:[^"\\\\]|\\\\.)*)"'));
  if (!m) throw new Error(name + ' const not found');
  return unescapeKotlin(m[1]);
}

/**
 * Extract a `const val NAME = "..." + "..."` (concatenated literals,
 * joined), for the hide stylesheets PageAssembly builds in two parts.
 */
function kotlinConstJoined(src, name) {
  const i = src.indexOf(name + ' =');
  if (i < 0) throw new Error(name + ' const not found');
  const region = src.slice(i, src.indexOf('\n\n', i));
  const parts = [...region.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1]);
  if (!parts.length) throw new Error(name + ' const pattern not found');
  return parts.map(unescapeKotlin).join('');
}

// ------------------------------------------------- PageAssembly (verbatim)

const RX = {
  SCRIPT_BLOCK: kotlinRegex(assembly, 'SCRIPT_BLOCK'),
  SCRIPT_SELF: kotlinRegex(assembly, 'SCRIPT_SELF'),
  BASE_TAG: kotlinRegex(assembly, 'BASE_TAG'),
  CONTAINER: kotlinRegex(assembly, 'CONTAINER'),
  VSCROLLER: kotlinRegex(assembly, 'VSCROLLER'),
  VSCROLLER_TAG: kotlinRegex(assembly, 'VSCROLLER_TAG'),
  SCREEN_ROOT: kotlinRegex(assembly, 'SCREEN_ROOT'),
  MSCREEN_TAG: kotlinRegex(assembly, 'MSCREEN_TAG'),
  BODY: kotlinRegex(assembly, 'BODY'),
  TOKEN: kotlinRegex(assembly, 'TOKEN'),
  DIV_OPEN: kotlinRegex(assembly, 'DIV_OPEN'),
  DIV_CLOSE: kotlinRegex(assembly, 'DIV_CLOSE'),
  FIRST_TAG: kotlinRegex(assembly, 'FIRST_TAG'),
  MARGIN: kotlinRegex(assembly, 'MARGIN'),
  MARGIN_STRIP: kotlinRegex(assembly, 'MARGIN_STRIP'),
  POST_LINK: kotlinRegex(assembly, 'POST_LINK'),
  HEIGHT: kotlinRegex(assembly, 'HEIGHT'),
};
const HIDE_OLD = kotlinConst(assembly, 'HIDE_OLD');
const HIDE_SCREEN_ROOT = kotlinConstJoined(assembly, 'HIDE_SCREEN_ROOT');
const RESET_SCREEN_ROOT = kotlinConstJoined(assembly, 'RESET_SCREEN_ROOT');
const RESET_GENERAL = kotlinConstJoined(assembly, 'RESET_GENERAL');

/** Integer const vals, matched as source text (kotlinConst reads strings).
 *  Evaluates the simple `N * M` products Kotlin allows in const vals. */
function kotlinInt(src, name) {
  const m = src.match(new RegExp(name + ' =\\s*(\\d+)(?:\\s*\\*\\s*(\\d+))?'));
  if (!m) throw new Error(name + ' int const not found');
  return parseInt(m[1], 10) * (m[2] ? parseInt(m[2], 10) : 1);
}
const MAX_CHROME_UNITS = kotlinInt(assembly, 'MAX_CHROME_UNITS');
const MAX_CHROME_BYTES = kotlinInt(assembly, 'MAX_CHROME_BYTES');

/** Mirror of PageAssembly.sanitize(). */
function sanitize(card) {
  return card.replace(RX.SCRIPT_BLOCK, '')
             .replace(RX.SCRIPT_SELF, '')
             .replace(RX.BASE_TAG, '');
}

/** Mirror of PageAssembly.holderHtml(). */
function holderHtml(cards) {
  const body = cards.map(sanitize).join('\n');
  return '<div id="__db_cards" data-db-cards="1">\n' + body + '\n</div>';
}

/** Reset a global regex before a one-shot exec/test on an input. */
function exec0(re, s) { re.lastIndex = 0; return re.exec(s); }
function test0(re, s) { re.lastIndex = 0; return re.test(s); }

/** Mirror of PageAssembly.topLevelChildren(). */
function topLevelChildren(doc, from) {
  const out = [];
  RX.TOKEN.lastIndex = from;
  let depth = 0, childStart = -1, m;
  while ((m = RX.TOKEN.exec(doc)) !== null) {
    const t = m[0];
    if (test0(RX.DIV_CLOSE, t)) {
      if (depth === 0) return out;
      depth--;
      if (depth === 0 && childStart >= 0) {
        out.push({ start: childStart, end: m.index + t.length });
        childStart = -1;
      }
    } else if (test0(RX.DIV_OPEN, t)) {
      if (depth === 0) childStart = m.index;
      depth++;
    }
    // comments and scripts match TOKEN first and are skipped
  }
  return out;
}

const MOVE = 0, LEAVE = 1, STOP = 2, MOVE_TAB = 3;

/** Mirror of PageAssembly.classify(), using the vault's own signatures. */
function classify(slice) {
  if (slice.toLowerCase().includes(JUNK.AD_TAG.toLowerCase())) return LEAVE;
  // The floating tab row is decided by two or more EXACT navigation
  // labels and by nothing else - the real row is anchors and carries
  // /reel/ right inside it, so a story-link excuse here loses navigation
  // itself (the round-9 report). The vault keeps that guard for storage.
  let labels = 0;
  JUNK.TAB_LABEL.lastIndex = 0;
  while (JUNK.TAB_LABEL.exec(slice) !== null) {
    if (++labels >= 2) return MOVE_TAB;
  }
  if (test0(RX.POST_LINK, slice)) return STOP;
  return MOVE;
}

/** Mirror of PageAssembly.stripVirtualMargin(). */
function stripVirtualMargin(slice) {
  const tag = exec0(RX.FIRST_TAG, slice);
  if (!tag) return slice;
  RX.MARGIN_STRIP.lastIndex = 0;
  const stripped = tag[0].replace(RX.MARGIN_STRIP, '');
  return stripped + slice.slice(tag[0].length);
}

/** Mirror of PageAssembly.ownMargin(). */
function ownMargin(slice) {
  const tag = exec0(RX.FIRST_TAG, slice);
  if (!tag) return null;
  const m = exec0(RX.MARGIN, tag[0]);
  return m ? parseInt(m[1], 10) : null;
}

/** Mirror of PageAssembly.ownHeight(). */
function ownHeight(slice) {
  const tag = exec0(RX.FIRST_TAG, slice);
  if (!tag) return null;
  const m = exec0(RX.HEIGHT, tag[0]);
  return m ? parseInt(m[1], 10) : null;
}

/** Mirror of PageAssembly.chromeKey(), regexes extracted verbatim. */
const HREF = kotlinRegex(assembly, 'HREF');
const TAG_STRIP = kotlinRegex(assembly, 'TAG_STRIP');
const SPACE = kotlinRegex(assembly, 'SPACE');
function chromeKey(slice) {
  const hrefs = [];
  HREF.lastIndex = 0;
  let h;
  while ((h = HREF.exec(slice)) !== null && hrefs.length < 4) hrefs.push(h[1]);
  hrefs.sort();
  if (hrefs.length) return 'L:' + hrefs.join('|');
  TAG_STRIP.lastIndex = 0; SPACE.lastIndex = 0;
  const text = slice.replace(TAG_STRIP, ' ').replace(SPACE, ' ').trim();
  return 'T:' + text.slice(0, 120);
}

/** Mirror of PageAssembly.withMargin(). */
function withMargin(unit, gapPx) {
  const tag = exec0(RX.FIRST_TAG, unit);
  if (!tag) return unit;
  const open = tag[0];
  const newOpen = open.indexOf('style="') >= 0 ?
    open.replace('style="', 'style="margin-top:' + gapPx + 'px;') :
    open.slice(0, -1) + ' style="margin-top:' + gapPx + 'px">';
  return newOpen + unit.slice(open.length);
}

/** Mirror of PageAssembly.stolenOffset(). */
function stolenOffset(doc, children) {
  for (let i = 0; i < children.length && i < 3; i++) {
    const slice = doc.slice(children[i].start, children[i].end);
    const tag = exec0(RX.FIRST_TAG, slice);
    if (!tag) continue;
    const m = exec0(RX.MARGIN, tag[0]);
    if (m) return parseInt(m[1], 10);
  }
  return null;
}

/** Mirror of PageAssembly.lastScreenTagBefore(). */
function lastScreenTagBefore(doc, pos) {
  RX.MSCREEN_TAG.lastIndex = 0;
  let last = null, m;
  while ((m = RX.MSCREEN_TAG.exec(doc)) !== null && m.index < pos) last = m;
  return last;
}

/**
 * Mirror of PageAssembly.compose(), branch for branch. Kotlin's
 * m.range.last + 1 is index + match length.
 */
function compose(doc, cards) {
  if (!cards.length) return doc;
  const holder = holderHtml(cards);
  let m = exec0(RX.CONTAINER, doc);
  if (m) {
    const at = m.index + m[0].length;
    const joinedScreenRoot =
      test0(RX.SCREEN_ROOT, m[0]) && test0(RX.VSCROLLER, doc.slice(at));
    if (joinedScreenRoot) {
      const rest = doc.slice(at);
      const vs = exec0(RX.VSCROLLER_TAG, rest);
      const vsStart = at + vs.index;
      const vsOpenEnd = at + vs.index + vs[0].length;

      let base = doc;
      const tagM = lastScreenTagBefore(doc, vsStart);
      if (tagM) {
        const tag = tagM[0], pos = tagM.index;
        base = doc.slice(0, pos) + tag.slice(0, -1) + ' data-db-active="1">' +
               doc.slice(pos + tag.length);
      }
      const shift = base.length - doc.length;
      const vsStartB = vsStart + shift, vsOpenEndB = vsOpenEnd + shift;

      const children = topLevelChildren(base, vsOpenEndB);
      const moved = [], tabs = [];
      const chromeSeen = new Set();
      let bytes = 0;
      let cursor = vsOpenEndB;
      let inner = '';
      for (let i = 0; i < children.length; i++) {
        if (moved.length + tabs.length >= MAX_CHROME_UNITS ||
            bytes >= MAX_CHROME_BYTES) break;
        const c = children[i];
        const slice = base.slice(c.start, c.end);
        const kind = classify(slice);
        if (kind === STOP) break;
        // A recycled twin of an already-moved chrome unit stays hidden.
        if ((kind === MOVE || kind === MOVE_TAB) &&
            chromeSeen.has(chromeKey(slice))) continue;
        if (kind === MOVE || kind === MOVE_TAB) chromeSeen.add(chromeKey(slice));
        if (kind === MOVE || kind === MOVE_TAB) {
          inner += base.slice(cursor, c.start);
          cursor = c.end;
          // [unit without virtual margin, own absolute offset, own height]
          (kind === MOVE_TAB ? tabs : moved).push(
            [stripVirtualMargin(slice), ownMargin(slice), ownHeight(slice)]);
          bytes += slice.length;
        }
      }
      // Facebook's margins are absolute y offsets; rebuilt as gaps they
      // reconstruct the online layout with Facebook's own numbers.
      const seq = tabs.concat(moved);
      const padTopI = (seq.length && seq[0][1] != null) ? seq[0][1]
        : (children.length ? stolenOffset(base, children) : null);
      const pad = padTopI != null && padTopI > 0 ? padTopI + 'px' : null;
      const parts = seq.map((entry, i) => {
        const own = entry[1];
        let gap = 0;
        if (i > 0 && own != null && seq[i - 1][1] != null) {
          gap = Math.max(0, own - seq[i - 1][1] - (seq[i - 1][2] || 0));
        }
        return gap > 0 ? withMargin(entry[0], gap) : entry[0];
      });
      const padCss = '<style id="__db_top_pad">' +
        (seq.length ? '#__db_chrome' : '#__db_cards') +
        '{padding-top:' + (pad || '0') + '!important}</style>';
      const chromeBody = parts.join('\n');
      const chrome = !chromeBody ? '' :
        '<div id="__db_chrome"' + (pad ? ' style="padding-top:' + pad + '"' : '') +
        '>' + chromeBody + '</div>';
      return base.slice(0, vsStartB) +
        RESET_SCREEN_ROOT + HIDE_SCREEN_ROOT + (pad ? padCss : '') +
        chrome + holder +
        base.slice(vsStartB, vsOpenEndB) + inner + base.slice(cursor);
    }
    return doc.slice(0, at) + RESET_GENERAL + HIDE_OLD + holder + doc.slice(at);
  }
  m = exec0(RX.BODY, doc);
  if (m) {
    const at = m.index + m[0].length;
    return doc.slice(0, at) + RESET_GENERAL + holder + doc.slice(at);
  }
  return doc + RESET_GENERAL + holder;
}

/** The v5.2.6 hide rule, kept for the legacy contrast mirror only. */
const LEGACY_HIDE_SCROLLER =
  '<style id="__db_hide_old">#__db_cards~[data-type="vscroller"]' +
  '{display:none!important}</style>';

/**
 * The v5.2.6 compose body, kept ONLY for contrast tests: it must still
 * demonstrate the faults that this round fixes (cards above the header,
 * chrome hidden with the scroller), or the new shape is fixing nothing.
 */
function composeLegacy(doc, cards) {
  if (!cards.length) return doc;
  const holder = holderHtml(cards);
  let m = exec0(RX.CONTAINER, doc);
  if (m) {
    const at = m.index + m[0].length;
    const joinedScreenRoot =
      test0(RX.SCREEN_ROOT, m[0]) && test0(RX.VSCROLLER, doc.slice(at));
    const hide = joinedScreenRoot ? LEGACY_HIDE_SCROLLER : HIDE_OLD;
    return doc.slice(0, at) + hide + holder + doc.slice(at);
  }
  m = exec0(RX.BODY, doc);
  if (m) {
    const at = m.index + m[0].length;
    return doc.slice(0, at) + holder + doc.slice(at);
  }
  return doc + holder;
}

// ---------------------------------------------------- SectionVault mirror
//
// The predicates below mirror companion-object functions of SectionVault.
// Their EXACT shapes are additionally asserted in the suites against the
// Kotlin source, so the mirror cannot silently diverge.

/** SectionVault.photoKey: filename, lowercase, size markers stripped. */
const pkRegion = vault.slice(vault.indexOf('fun photoKey'));
const pkRx = [...pkRegion.matchAll(/Regex\("((?:[^"\\]|\\.)*)"\)/g)]
  .map((m) => new RegExp(unescapeKotlin(m[1]), 'g'));
function photoKey(url) {
  const stem = url.split('?')[0].split('/').pop().toLowerCase();
  return stem.replace(pkRx[0], '').replace(pkRx[1], '');
}

function isVideoUrl(url) {
  const c = url.split('?')[0].toLowerCase();
  return c.endsWith('.mp4') || c.endsWith('.webm') || c.includes('/v/t2/');
}

function isAvatar(url) {
  const c = url.split('?')[0];
  return c.includes('/t39.30808-1/') || (c.includes('profile') && c.includes('_s.'));
}

function isChrome(url) {
  const c = url.split('?')[0].toLowerCase();
  return c.includes('/emoji.php/') || c.includes('static.xx.fbcdn.net') ||
         c.includes('/rsrc.php/') || c.endsWith('.svg');
}

// ------------------------------------------------- vault junk filter

/** Junk signatures, extracted verbatim from SectionVault's companion. */
const JUNK = {
  AD_TAG: kotlinConst(vault, 'JUNK_AD_TAG'),
  FEED_ID: kotlinConst(vault, 'SECTION_FEED_ID'),
  STORY_LINK: kotlinRegex(vault, 'JUNK_STORY_LINK'),
  TAB_LABEL: kotlinRegex(vault, 'JUNK_TAB_LABEL'),
  TRAY_LINK: kotlinRegex(vault, 'JUNK_TRAY_LINK'),
};

/** Mirror of SectionVault.isJunk(), branch for branch. */
function isJunk(section, html) {
  if (html.toLowerCase().includes(JUNK.AD_TAG.toLowerCase())) return true;
  if (section !== JUNK.FEED_ID) return false;
  JUNK.STORY_LINK.lastIndex = 0;
  if (JUNK.STORY_LINK.test(html)) return false;
  // The stories tray: two or more DISTINCT story links and, per the
  // guard above, no post permalink. A saved tray heals at read.
  let stories = 0, m2;
  const seenStories = new Set();
  JUNK.TRAY_LINK.lastIndex = 0;
  while ((m2 = JUNK.TRAY_LINK.exec(html)) !== null) {
    if (!seenStories.has(m2[1])) {
      seenStories.add(m2[1]);
      if (++stories >= 2) return true;
    }
  }
  JUNK.TAB_LABEL.lastIndex = 0;
  let labels = 0, m;
  while ((m = JUNK.TAB_LABEL.exec(html)) !== null) {
    if (++labels >= 2) return true;
  }
  return false;
}

/**
 * Mirror of SectionVault.isComplete(). `has(url)` answers whether the
 * asset's file exists in the vault's own media folder; by construction
 * a file there only ever exists COMPLETE (written .part, renamed in).
 */
function isComplete(media, has) {
  if (media.length === 0) return true;

  const videos = media.filter(isVideoUrl);
  if (videos.length > 0) {
    return videos.some(has);
  }

  const photos = media.filter((u) => !isAvatar(u) && !isChrome(u));
  if (photos.length === 0) return true;

  const groups = {};
  for (const p of photos) {
    const k = photoKey(p);
    (groups[k] = groups[k] || []).push(p);
  }
  return Object.values(groups).every((vs) => vs.some(has));
}

/**
 * Mirror of SectionVault.addItems() merge: newest first, deduped, floored.
 * With a hardCap, a re-capture of a stored id passes for free (it replaces
 * rather than adds) while a brand-new id spends one unit of room, decided
 * in one pass - exactly the production lock's rule.
 */
function addItems(existing, incoming, limit, floor, hardCap) {
  const keep = Math.max(limit, floor);
  const existingIds = new Set(
    existing.map((e) => e.id).filter((x) => x && x.length));
  let room = (hardCap == null) ? Number.MAX_SAFE_INTEGER :
    hardCap - existing.length;
  const allowed = incoming.filter((it) => {
    if (it.id && it.id.length && existingIds.has(it.id)) return true;
    if (room > 0) { room--; return true; }
    return false;
  });
  const seen = new Set();
  const merged = [];
  const keyFor = (it) => it.id && it.id.length ? it.id :
    ((it.media[0] || '') + '|' + it.html.slice(0, 180));
  for (const it of [...allowed, ...existing]) {
    const k = keyFor(it);
    if (seen.has(k)) continue;
    seen.add(k);
    merged.push(it);
    if (merged.length >= keep) break;
  }
  return merged;
}

module.exports = {
  ROOT, KT, SRC,
  unescapeKotlin, kotlinRegex, kotlinConst, kotlinConstJoined,
  RX, HIDE_OLD, HIDE_SCREEN_ROOT, RESET_SCREEN_ROOT, RESET_GENERAL,
  sanitize, holderHtml, compose, composeLegacy,
  classify, stripVirtualMargin, stolenOffset, topLevelChildren,
  ownMargin, ownHeight, withMargin, chromeKey,
  photoKey, isVideoUrl, isAvatar, isChrome, isComplete, addItems,
  JUNK, isJunk,
  sources: { assembly, vault, docs, docsFeed },
};
