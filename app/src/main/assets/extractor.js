// 현재 WebView 페이지에서 소설 본문, 제목, 이전/다음 화 링크를 뽑아내는 함수.
// 앱이 이 파일 전체를 "(...)(규칙)" 형태로 감싸서 실행하므로, 파일은 함수 표현식 하나여야 한다.
// rule: { content, title, next, prev, workTitle } — 사이트별 CSS 선택자(없으면 빈 문자열).
function (rule) {
  'use strict';
  rule = rule || {};
  var warnings = [];
  var invalid = {};

  function toSet(s) { var o = {}; s.split(' ').forEach(function (k) { o[k] = 1; }); return o; }

  // 본문이 될 수 없는 태그
  var SKIP = toSet('SCRIPT STYLE NOSCRIPT IFRAME NAV HEADER FOOTER ASIDE FORM BUTTON SELECT TEXTAREA INPUT SVG CANVAS VIDEO AUDIO INS TEMPLATE OBJECT EMBED IMG LINK META');
  // 줄바꿈을 만드는 블록 태그
  var BLOCK = toSet('P DIV SECTION ARTICLE MAIN LI UL OL DL DD DT BLOCKQUOTE PRE TABLE THEAD TBODY TFOOT TR TD TH H1 H2 H3 H4 H5 H6 CENTER FIGURE FIGCAPTION ADDRESS');
  // 문단 역할을 하는 블록 태그(안쪽 내용을 그대로 신뢰)
  var PARA = toSet('P BLOCKQUOTE PRE LI DD DT TD TH');
  // 광고, 메뉴, 작품 정보 등으로 보이는 class/id
  var SUSPICIOUS = /adv|(^|[^a-z])ads?([^a-z]|$)|banner|share|comment|recommend|related|foot|nav|menu|tool|copyright|crumb|pager|btn|button|info|meta|author|vote|reward|login|qrcode|hidden|tips?([^a-z]|$)/i;

  function clean(s) {
    return (s || '').replace(/[ 　  ​﻿]/g, ' ').replace(/[ \t\r\n\f]+/g, ' ').trim();
  }
  function punct(s) { var m = s.match(/[，。！？、；：“”…,.!?]/g); return m ? m.length : 0; }
  function isHeading(tag) { return /^H[1-6]$/.test(tag); }
  function ident(el) { return (el.getAttribute('class') || '') + ' ' + (el.id || ''); }
  function linkLen(el) {
    var n = 0, as = el.getElementsByTagName('a');
    for (var i = 0; i < as.length; i++) n += clean(as[i].textContent).length;
    return n;
  }
  function hasBlockChild(el) {
    for (var c = el.firstElementChild; c; c = c.nextElementSibling) if (BLOCK[c.tagName]) return true;
    return false;
  }
  function hiddenStyle(el) {
    var cs;
    try { cs = window.getComputedStyle(el); } catch (e) { return false; }
    if (!cs) return false;
    return cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.fontSize) === 0 || parseFloat(cs.opacity) === 0;
  }
  function q(sel, all) {
    if (!sel) return all ? [] : null;
    try {
      return all ? Array.prototype.slice.call(document.querySelectorAll(sel)) : document.querySelector(sel);
    } catch (e) {
      warnings.push('잘못된 선택자: ' + sel);
      invalid[sel] = 1;
      return all ? [] : null;
    }
  }
  function describe(el) {
    if (!el) return '';
    var s = el.tagName.toLowerCase();
    if (el.id) return s + '#' + el.id;
    var c = clean(el.getAttribute('class') || '');
    if (c) s += '.' + c.split(' ')[0];
    return s;
  }

  // ---------- 본문 블록 찾기 ----------

  // 요소 "바로 아래"에 있는 글의 양을 점수로 계산한다. 링크가 많으면 감점, 문장 부호가 많으면 가점.
  function directScore(el) {
    var total = 0, links = 0, p = 0, plain = 0, leafDiv = 0;
    for (var n = el.firstChild; n; n = n.nextSibling) {
      if (n.nodeType === 3) {
        var t = clean(n.nodeValue);
        total += t.length; plain += t.length; p += punct(t);
      } else if (n.nodeType === 1) {
        var tag = n.tagName;
        if (SKIP[tag] || tag === 'BR' || tag === 'HR' || tag === 'A' || isHeading(tag)) continue;
        var block = !!BLOCK[tag];
        if (block && hasBlockChild(n)) continue;
        if (block && !PARA[tag] && SUSPICIOUS.test(ident(n))) continue;
        var t2 = clean(n.textContent);
        total += t2.length; links += linkLen(n); p += punct(t2);
        if (block && !PARA[tag]) leafDiv += t2.length; else plain += t2.length;
      }
    }
    return { score: total - 3 * links + 10 * p, plain: plain, leafDiv: leafDiv };
  }

  function findContentRoot() {
    var cands = document.querySelectorAll('body, div, article, section, main, td, dd, blockquote, pre, center, p');
    var scored = [];
    for (var i = 0; i < cands.length; i++) {
      var el = cands[i];
      if (el.closest('nav, footer, header, aside')) continue;
      var s = directScore(el);
      if (s.score > 0) scored.push({ el: el, s: s });
    }
    scored.sort(function (a, b) { return b.s.score - a.s.score; });
    for (var j = 0; j < scored.length && j < 5; j++) {
      // 화면에 실제로 보이는 블록만 채택
      if (scored[j].el.getClientRects().length > 0) return scored[j];
    }
    return scored.length ? scored[0] : null;
  }

  // ---------- 본문 블록에서 문단 뽑기 ----------

  // trusted: 사용자가 규칙으로 직접 지정한 블록이면 광고 추정 필터를 적용하지 않는다.
  // divMode: 문단 하나하나가 <div>로 되어 있는 사이트.
  function collect(root, trusted, divMode, out, state) {
    var buf = '', bufLinks = 0;
    function flush() {
      var t = clean(buf);
      // 줄의 절반 이상이 링크 글자면 광고/메뉴 줄로 보고 버린다.
      if (t && bufLinks * 2 < t.length) out.push(t);
      buf = ''; bufLinks = 0;
    }
    function allowBlock(n, tag) {
      var t = clean(n.textContent);
      if (!t) return false;
      if (hiddenStyle(n)) return false;
      var density = linkLen(n) / t.length;
      if (PARA[tag]) return density <= 0.5;
      if (density > 0.3) return false;
      if (trusted) return true;
      if (SUSPICIOUS.test(ident(n))) return false;
      if (!divMode && (t.length < 50 || punct(t) < 2)) return false;
      return true;
    }
    function walk(node) {
      for (var n = node.firstChild; n; n = n.nextSibling) {
        if (n.nodeType === 3) { buf += n.nodeValue; continue; }
        if (n.nodeType !== 1) continue;
        var tag = n.tagName;
        if (SKIP[tag]) continue;
        if (tag === 'BR') { flush(); continue; }
        if (tag === 'HR') { flush(); out.push('-----'); continue; }
        if (isHeading(tag)) {
          flush();
          var h = clean(n.textContent);
          if (/^H[1-3]$/.test(tag)) { if (!state.heading) state.heading = h; }
          else if (h) out.push(h);
          continue;
        }
        if (tag === 'A') {
          var before = buf.length;
          walk(n);
          bufLinks += clean(buf.substring(before)).length;
          continue;
        }
        if (BLOCK[tag]) {
          flush();
          if (allowBlock(n, tag)) walk(n);
          flush();
        } else if (!hiddenStyle(n)) {
          walk(n);
        }
      }
    }
    walk(root);
    flush();
  }

  var paragraphs = [];
  var state = { heading: '' };
  var contentSource = 'none', contentPath = '';

  var ruleRoots = q(rule.content, true);
  if (rule.content && !ruleRoots.length && !invalid[rule.content]) warnings.push('본문 선택자에 해당하는 요소가 없어 자동 추출로 대체: ' + rule.content);
  if (ruleRoots.length) {
    contentSource = 'rule';
    contentPath = rule.content;
    ruleRoots.forEach(function (r) { if (!hiddenStyle(r)) collect(r, true, true, paragraphs, state); });
  } else {
    var best = findContentRoot();
    if (best) {
      contentSource = 'auto';
      contentPath = describe(best.el);
      collect(best.el, false, best.s.leafDiv > best.s.plain, paragraphs, state);
    }
  }

  // ---------- 제목 ----------

  function meta(name) {
    var el = document.querySelector('meta[property="' + name + '"], meta[name="' + name + '"]');
    return el ? clean(el.getAttribute('content')) : '';
  }
  function textOf(sel) { var el = q(sel); return el ? clean(el.textContent) : ''; }

  // 많은 중국 소설 사이트가 쓰는 전역 변수(작품명, 회차명, 이전/다음 주소가 들어 있음)
  var bookinfo = (typeof window.bookinfo === 'object' && window.bookinfo) || {};
  function biStr(k) { return typeof bookinfo[k] === 'string' ? clean(bookinfo[k]) : ''; }

  var pageH1 = document.querySelector('h1') || document.querySelector('h2');
  var chapterTitle = textOf(rule.title) || biStr('chaptername') || state.heading ||
    (pageH1 ? clean(pageH1.textContent) : '') || meta('og:title');
  var workTitle = textOf(rule.workTitle) || meta('og:novel:book_name') || biStr('articlename');

  // ---------- 이전/다음 화 ----------

  var NEXT = [
    ['下一页', '下一頁', '下页', '下頁'],
    ['下一章', '下一节', '下一節', '下一篇', '下一话', '下一話', '下一回', '下一卷', '下章', '后一章', '後一章', '次の話', '次へ', '다음화', '다음편', '다음장', '다음회', '다음글', '다음페이지', '다음'],
    ['nextchapter', 'nextpage', 'next']
  ];
  var PREV = [
    ['上一页', '上一頁', '上页', '上頁'],
    ['上一章', '上一节', '上一節', '上一篇', '上一话', '上一話', '上一回', '上一卷', '上章', '前一章', '前の話', '前へ', '이전화', '이전편', '이전장', '이전회', '이전글', '이전페이지', '이전'],
    ['previouschapter', 'prevchapter', 'previouspage', 'prevpage', 'previous', 'prev']
  ];

  function normNav(s) {
    return (s || '').replace(/[\s»›>→<«‹←▶◀⇒⇐\[\]【】()（）:：.。]/g, '').toLowerCase();
  }
  // 낮을수록 좋은 순위를 돌려준다. 해당 없으면 -1.
  function matchNav(norm, groups) {
    if (!norm || norm.length > 16) return -1;
    for (var g = 0; g < groups.length; g++) {
      for (var i = 0; i < groups[g].length; i++) {
        var pat = groups[g][i];
        if (norm === pat) return g * 2;
        var loose = /^[a-z]/.test(pat) || pat.length <= 2;
        var hit = loose ? norm.indexOf(pat) === 0 : norm.indexOf(pat) >= 0;
        var slack = /^[a-z]/.test(pat) ? 10 : (loose ? 4 : 8);
        if (hit && norm.length <= pat.length + slack) return g * 2 + 1;
      }
    }
    return -1;
  }
  function stripHash(u) { return (u || '').replace(/#.*$/, ''); }
  function absUrl(u) {
    if (!u) return null;
    try { return new URL(u, document.baseURI).href; } catch (e) { return null; }
  }
  function navTarget(el) {
    var a = el.tagName === 'A' || el.tagName === 'LINK' ? el : (el.closest('a') || el.querySelector('a'));
    var url = null;
    if (a) {
      var raw = a.getAttribute('href') || '';
      if (raw && raw.charAt(0) !== '#' && !/^javascript:/i.test(raw)) url = absUrl(raw);
    }
    if (url && stripHash(url) === stripHash(location.href)) url = null;
    return url;
  }

  var marked = document.querySelectorAll('[data-clauder-nav]');
  for (var mi = 0; mi < marked.length; mi++) marked[mi].removeAttribute('data-clauder-nav');

  function findNav(kind, ruleSel, groups, otherGroups, relName, biKey, hint) {
    function result(el, url) {
      // 주소가 없는 버튼형 링크는 표시해 두었다가 앱이 눌러서 이동한다.
      var clickable = !!el && el.tagName !== 'LINK';
      if (clickable) el.setAttribute('data-clauder-nav', kind);
      return { url: url, clickable: clickable };
    }
    if (ruleSel) {
      var r = q(ruleSel);
      if (r) return result(r, navTarget(r));
      if (!invalid[ruleSel]) warnings.push((kind === 'next' ? '다음 화' : '이전 화') + ' 선택자에 해당하는 요소가 없어 자동 인식으로 대체: ' + ruleSel);
    }
    var rel = document.querySelector('a[rel~="' + relName + '"], link[rel~="' + relName + '"]');
    if (rel && navTarget(rel)) return result(rel, navTarget(rel));

    var els = document.querySelectorAll('a, button, input[type="button"], input[type="submit"], [role="button"], [onclick]');
    var bestEl = null, bestUrl = null, bestRank = 1e9;
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var label = clean(el.textContent) || el.value || el.getAttribute('title') || el.getAttribute('aria-label') || '';
      var norm = normNav(label);
      var rank = matchNav(norm, groups);
      if (rank < 0 || matchNav(norm, otherGroups) >= 0 && norm.length > 6) continue;
      var url = navTarget(el);
      var total = rank * 2 + (url ? 0 : 1);
      if (total < bestRank) { bestRank = total; bestEl = el; bestUrl = url; }
    }
    if (bestEl) return result(bestEl, bestUrl);

    var bi = absUrl(biStr(biKey));
    if (bi && stripHash(bi) !== stripHash(location.href)) return { url: bi, clickable: false };

    var hinted = document.querySelectorAll('a[id*="' + hint + '" i], a[class*="' + hint + '" i]');
    for (var h = 0; h < hinted.length; h++) {
      var hu = navTarget(hinted[h]);
      if (hu) return result(hinted[h], hu);
    }
    return { url: null, clickable: false };
  }

  var next = findNav('next', rule.next, NEXT, PREV, 'next', 'next_page', 'next');
  var prev = findNav('prev', rule.prev, PREV, NEXT, 'prev', 'preview_page', 'prev');

  // ---------- 보안 확인(봇 검사) 페이지 여부 ----------

  var blocked = typeof window._cf_chl_opt !== 'undefined' ||
    !!document.querySelector('#challenge-form, #challenge-stage, #challenge-error-text, #cf-wrapper, iframe[src*="challenges.cloudflare.com"], iframe[src*="recaptcha"], iframe[src*="hcaptcha"]');

  return {
    url: location.href,
    host: location.hostname,
    docTitle: clean(document.title),
    workTitle: workTitle,
    chapterTitle: chapterTitle,
    paragraphs: paragraphs,
    nextUrl: next.url,
    prevUrl: prev.url,
    nextClickable: next.clickable,
    prevClickable: prev.clickable,
    blocked: blocked,
    contentSource: contentSource,
    contentPath: contentPath,
    warnings: warnings
  };
}
