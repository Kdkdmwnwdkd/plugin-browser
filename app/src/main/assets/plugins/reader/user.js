/*
 * 内置插件：阅读模式
 * 演示：正文提取启发式算法、浮层 UI、PB.storage 记忆字号
 */
(function () {
  if (window.__pb_reader__) return;
  window.__pb_reader__ = true;

  var fontSize = 17;
  var panel = null;
  var fab = null;

  /* ---------- 浮动入口按钮 ---------- */
  fab = document.createElement('div');
  fab.id = 'pb-reader-fab';
  fab.textContent = '阅';
  fab.style.cssText = 'position:fixed;right:14px;bottom:84px;width:44px;height:44px;border-radius:50%;' +
    'background:#0b57d0;color:#fff;font-size:18px;line-height:44px;text-align:center;' +
    'z-index:2147483646;box-shadow:0 2px 8px rgba(0,0,0,.3);cursor:pointer;user-select:none;';
  fab.onclick = toggle;
  (document.body || document.documentElement).appendChild(fab);

  /* ---------- 正文提取 ---------- */
  function extractArticle() {
    var paras = document.querySelectorAll('p, article, .article, .content, #content, .post, .entry');
    var scores = {};
    var best = null;
    var bestScore = 0;

    for (var i = 0; i < paras.length; i++) {
      var p = paras[i];
      var text = (p.textContent || '').replace(/\s+/g, ' ').trim();
      if (text.length < 30) continue;
      var node = p;
      for (var depth = 0; depth < 5 && node && node !== document.body; depth++) {
        var parent = node.parentElement;
        if (!parent) break;
        var key = parent.tagName + '#' + (parent.id || '') + '.' + (parent.className || '');
        if (!scores[key]) scores[key] = { el: parent, score: 0 };
        scores[key].score += text.length;
        if (scores[key].score > bestScore) { bestScore = scores[key].score; best = parent; }
        node = parent;
      }
    }

    var title = document.title || '';
    var h1 = document.querySelector('h1');
    if (h1 && (h1.textContent || '').trim()) title = h1.textContent.trim();

    var out = [];
    if (best) {
      var nodes = best.querySelectorAll('h1, h2, h3, p, blockquote, li, pre');
      for (var j = 0; j < nodes.length; j++) {
        var el = nodes[j];
        var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
        if (t.length < 8) continue;
        var tag = el.tagName.toLowerCase();
        if (tag === 'h1' || tag === 'h2' || tag === 'h3') {
          out.push('<' + tag + '>' + escapeHtml(t) + '</' + tag + '>');
        } else if (tag === 'blockquote') {
          out.push('<blockquote>' + escapeHtml(t) + '</blockquote>');
        } else if (tag === 'pre') {
          out.push('<pre>' + escapeHtml(t) + '</pre>');
        } else {
          out.push('<p>' + escapeHtml(t) + '</p>');
        }
      }
    }
    if (!out.length) out.push('<p>未能自动识别正文，请换一个文章页面试试。</p>');
    return { title: title, html: out.join('') };
  }

  function escapeHtml(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  /* ---------- 浮层 ---------- */
  function buildPanel() {
    var art = extractArticle();
    panel = document.createElement('div');
    panel.id = 'pb-reader-panel';
    panel.style.cssText = 'position:fixed;inset:0;z-index:2147483647;overflow-y:auto;' +
      'background:#fafafa;color:#1b1b1f;padding:20px 18px 60px;font-family:-apple-system,"PingFang SC",sans-serif;';
    if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
      panel.style.background = '#16171c';
      panel.style.color = '#e3e3e9';
    }
    panel.innerHTML =
      '<div style="display:flex;align-items:center;gap:10px;margin-bottom:14px;">' +
      '  <button id="pb-reader-close" style="border:none;background:none;font-size:22px;color:inherit;">✕</button>' +
      '  <div style="flex:1"></div>' +
      '  <button id="pb-reader-smaller" style="border:none;background:#0b57d0;color:#fff;border-radius:8px;padding:6px 12px;font-size:14px;">A-</button>' +
      '  <button id="pb-reader-bigger" style="border:none;background:#0b57d0;color:#fff;border-radius:8px;padding:6px 12px;font-size:14px;">A+</button>' +
      '</div>' +
      '<h1 style="font-size:22px;line-height:1.4;margin:0 0 16px;">' + escapeHtml(art.title) + '</h1>' +
      '<div id="pb-reader-body" style="font-size:' + fontSize + 'px;line-height:1.9;">' + art.html + '</div>';
    document.body.appendChild(panel);
    panel.querySelector('#pb-reader-close').onclick = close;
    panel.querySelector('#pb-reader-bigger').onclick = function () { setFont(fontSize + 1); };
    panel.querySelector('#pb-reader-smaller').onclick = function () { setFont(fontSize - 1); };
    fab.style.display = 'none';
  }

  function setFont(px) {
    fontSize = Math.max(12, Math.min(28, px));
    var body = panel && panel.querySelector('#pb-reader-body');
    if (body) body.style.fontSize = fontSize + 'px';
    if (window.PB && PB.storage) PB.storage.set('font_size', { value: fontSize });
  }

  function close() {
    if (panel && panel.parentNode) panel.parentNode.removeChild(panel);
    panel = null;
    if (fab) fab.style.display = 'flex';
  }

  function toggle() {
    if (panel) close();
    else buildPanel();
  }

  // 读取记忆的字号
  if (window.PB && PB.storage) {
    PB.storage.get('font_size', function (v) {
      if (v && typeof v.value === 'number') fontSize = v.value;
    });
  }
})();
