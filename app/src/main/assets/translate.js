/*
 * 整页翻译注入脚本。
 *
 * 收集分两种粒度：
 *  1) 块级（p/li/h1-h6/td…）整段翻译 —— 语序正常，但会丢掉 <b> 这类内联样式；
 *  2) 文本节点逐段翻译 —— 仅用于含 <a>/<img> 等交互元素的段落，保住可点性。
 * 原文保存在元素上，restore() 完整还原。
 */
(function () {
  if (window.__PB_TR) return;

  var blocks = [];      // 块级元素
  var nodes = [];       // 零散文本节点

  var BLOCK_TAGS = 'p,li,h1,h2,h3,h4,h5,h6,td,th,dd,dt,blockquote,figcaption';
  var NESTED = 'p,li,h1,h2,h3,h4,h5,h6,td,th,div,section,article,blockquote,ul,ol,table';
  var UNSUPPORTED = 'a,img,button,input,select,textarea,video,audio,iframe,svg,canvas';
  var SKIP_TAGS = /^(SCRIPT|STYLE|NOSCRIPT|CODE|PRE|TEXTAREA|INPUT|SELECT|OPTION|SVG|CANVAS)$/;

  function skipped(el) {
    if (!el) return true;
    if (SKIP_TAGS.test(el.tagName)) return true;
    if (el.isContentEditable) return true;
    if (el.closest && el.closest('[data-pb-skip]')) return true;
    return false;
  }

  function hasText(text) {
    return text.length >= 2 && /[A-Za-z\u4e00-\u9fa5]/.test(text);
  }

  function collect() {
    blocks = [];
    nodes = [];
    var handled = [];

    // 1) 叶子块：内部不含其它块，也不含交互/媒体元素
    var candidates = document.querySelectorAll(BLOCK_TAGS);
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      if (skipped(el)) continue;
      if (el.querySelector(NESTED)) continue;
      if (el.querySelector(UNSUPPORTED)) continue;
      var text = (el.textContent || '').replace(/\s+/g, ' ').trim();
      if (!hasText(text)) continue;

      blocks.push(el);
      var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
      var n;
      while ((n = walker.nextNode())) handled.push(n);
    }

    // 2) 其余零散文本节点
    var walker2 = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    var node;
    while ((node = walker2.nextNode())) {
      if (handled.indexOf(node) >= 0) continue;
      if (skipped(node.parentElement)) continue;
      var raw = (node.nodeValue || '').trim();
      if (!hasText(raw)) continue;
      nodes.push(node);
    }

    var out = [];
    for (var b = 0; b < blocks.length; b++) {
      out.push({ id: 'b' + b, t: (blocks[b].textContent || '').replace(/\s+/g, ' ').trim() });
    }
    for (var t = 0; t < nodes.length; t++) {
      out.push({ id: 't' + t, t: nodes[t].nodeValue.trim() });
    }
    return out;
  }

  function apply(pairs) {
    for (var i = 0; i < pairs.length; i++) {
      var pair = pairs[i];
      if (!pair || typeof pair.t !== 'string' || !pair.t) continue;
      var index = parseInt(pair.id.slice(1), 10);

      if (pair.id.charAt(0) === 'b') {
        var el = blocks[index];
        if (!el) continue;
        if (el.__pbOrigHtml === undefined) el.__pbOrigHtml = el.innerHTML;
        el.textContent = pair.t;
      } else {
        var node = nodes[index];
        if (!node) continue;
        if (node.__pbOrig === undefined) node.__pbOrig = node.nodeValue;
        var orig = node.__pbOrig;
        var lead = (orig.match(/^\s*/) || [''])[0];
        var trail = (orig.match(/\s*$/) || [''])[0];
        node.nodeValue = lead + pair.t + trail;
      }
    }
  }

  function restore() {
    for (var b = 0; b < blocks.length; b++) {
      var el = blocks[b];
      if (el && el.__pbOrigHtml !== undefined) {
        el.innerHTML = el.__pbOrigHtml;
        delete el.__pbOrigHtml;
      }
    }
    for (var t = 0; t < nodes.length; t++) {
      var node = nodes[t];
      if (node && node.__pbOrig !== undefined) {
        node.nodeValue = node.__pbOrig;
        delete node.__pbOrig;
      }
    }
    blocks = [];
    nodes = [];
  }

  window.__PB_TR = { collect: collect, apply: apply, restore: restore };
})();
