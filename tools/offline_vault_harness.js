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

// ------------------------------------------------- PageAssembly (verbatim)

const RX = {
  SCRIPT_BLOCK: kotlinRegex(assembly, 'SCRIPT_BLOCK'),
  SCRIPT_SELF: kotlinRegex(assembly, 'SCRIPT_SELF'),
  BASE_TAG: kotlinRegex(assembly, 'BASE_TAG'),
  FEED_VSCROLLER: kotlinRegex(assembly, 'FEED_VSCROLLER'),
  CONTAINER: kotlinRegex(assembly, 'CONTAINER'),
  BODY: kotlinRegex(assembly, 'BODY'),
};
const HIDE_OLD = kotlinConst(assembly, 'HIDE_OLD');

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

/**
 * Mirror of PageAssembly.compose(), branch for branch:
 * container match -> HIDE_OLD + holder as first child; else after <body>;
 * else appended. Kotlin's m.range.last + 1 is index + match length.
 */
function compose(doc, cards) {
  if (!cards.length) return doc;
  const holder = holderHtml(cards);
  // Feed scroller first, exactly as the Kotlin: only its later children
  // hide, and the pinned header above it keeps its place.
  RX.FEED_VSCROLLER.lastIndex = 0;
  let m = RX.FEED_VSCROLLER.exec(doc);
  if (m) {
    const at = m.index + m[0].length;
    return doc.slice(0, at) + HIDE_OLD + holder + doc.slice(at);
  }
  RX.CONTAINER.lastIndex = 0;
  m = RX.CONTAINER.exec(doc);
  if (m) {
    const at = m.index + m[0].length;
    return doc.slice(0, at) + HIDE_OLD + holder + doc.slice(at);
  }
  RX.BODY.lastIndex = 0;
  m = RX.BODY.exec(doc);
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
};

/** Mirror of SectionVault.isJunk(), branch for branch. */
function isJunk(section, html) {
  if (html.toLowerCase().includes(JUNK.AD_TAG.toLowerCase())) return true;
  if (section !== JUNK.FEED_ID) return false;
  JUNK.STORY_LINK.lastIndex = 0;
  if (JUNK.STORY_LINK.test(html)) return false;
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

/** Mirror of SectionVault.addItems() merge: newest first, deduped, floored. */
function addItems(existing, incoming, limit, floor) {
  const keep = Math.max(limit, floor);
  const seen = new Set();
  const merged = [];
  const keyFor = (it) => it.id && it.id.length ? it.id :
    ((it.media[0] || '') + '|' + it.html.slice(0, 180));
  for (const it of [...incoming, ...existing]) {
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
  unescapeKotlin, kotlinRegex, kotlinConst,
  RX, HIDE_OLD, sanitize, holderHtml, compose,
  photoKey, isVideoUrl, isAvatar, isChrome, isComplete, addItems,
  JUNK, isJunk,
  sources: { assembly, vault, docs, docsFeed },
};
