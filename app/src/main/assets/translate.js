/*
 * 整页翻译注入脚本。
 *
 * 收集分两种粒度：
 *  1) 块级（p/li/h1-h6/td…）整段翻译 —— 语序正常，但会丢掉 <b> 这类内联样式；
 *  2) 文本节点逐段翻译 —— 仅用于含 <a>/<img> 等交互元素的段落，保住可点性。
 * 原文保存在元素上，restore() 完整还原。
 *
 * id 约定：b<下标> 对应 blocks，t<下标> 对应 nodes。
 * 原生侧必须原样回传该**字符串** id —— 传数字会让 apply 抛异常、整页无变化。
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

  /** 返回 { applied, failed, total }；节点被页面重建时计入 failed 而不是静默丢弃 */
  function apply(pairs) {
    var applied = 0;
    var failed = 0;
    if (!pairs || typeof pairs.length !== 'number') {
      return { applied: 0, failed: 0, total: 0 };
    }

    for (var i = 0; i < pairs.length; i++) {
      var pair = pairs[i];
      if (!pair || typeof pair.id !== 'string' || typeof pair.t !== 'string' || !pair.t) {
        failed++;
        continue;
      }
      var index = parseInt(pair.id.slice(1), 10);
      if (isNaN(index)) {
        failed++;
        continue;
      }

      if (pair.id.charAt(0) === 'b') {
        var el = blocks[index];
        if (!el || !el.parentNode) {
          failed++;
          continue;
        }
        if (el.__pbOrigHtml === undefined) el.__pbOrigHtml = el.innerHTML;
        el.textContent = pair.t;
        applied++;
      } else {
        var node = nodes[index];
        if (!node || !node.parentNode) {
          failed++;
          continue;
        }
        if (node.__pbOrig === undefined) node.__pbOrig = node.nodeValue;
        var orig = node.__pbOrig;
        var lead = (orig.match(/^\s*/) || [''])[0];
        var trail = (orig.match(/\s*$/) || [''])[0];
        node.nodeValue = lead + pair.t + trail;
        applied++;
      }
    }
    return { applied: applied, failed: failed, total: pairs.length };
  }

  function restore() {
    var n = 0;
    for (var b = 0; b < blocks.length; b++) {
      var el = blocks[b];
      if (el && el.__pbOrigHtml !== undefined) {
        el.innerHTML = el.__pbOrigHtml;
        delete el.__pbOrigHtml;
        n++;
      }
    }
    for (var t = 0; t < nodes.length; t++) {
      var node = nodes[t];
      if (node && node.__pbOrig !== undefined) {
        node.nodeValue = node.__pbOrig;
        delete node.__pbOrig;
        n++;
      }
    }
    blocks = [];
    nodes = [];
    return n;
  }

  /* ---------- 语言检测 ---------- */

  /*
   * 为什么要自己判，而不是直接读 document.lang：
   * 一半的站点根本不写 lang，另一半写的是错的（整站抄模板，首页标 zh-CN，
   * 英文子站也不改）。所以 lang 只当参考，真正的判据是正文里各种文字的占比。
   */

  var TRAD_RE = /[這個說時會來對們為國學經還樣麼沒點開關門問題實際現應該發讓見東車話語讀寫購買賣價錢銀網頁體驗設頻聲]/g;
  var SIMP_RE = /[这个说时会对们为国学经还样么没点开关门问题实际现应该发让见东车话语读写购买卖价钱银网页体验设频声]/g;
  var EN_WORD = /\b(the|and|of|to|is|are|that|this|with|for|you|it|was|on|as|be|by|at|from|not|have|but|they|what|will|can|has|been|their|would|about)\b/gi;

  function countChars(text) {
    var stat = { han: 0, kana: 0, hangul: 0, latin: 0, cyr: 0 };
    for (var i = 0; i < text.length; i++) {
      var c = text.charCodeAt(i);
      if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0xF900 && c <= 0xFAFF)) {
        stat.han++;
      } else if (c >= 0x3040 && c <= 0x30FF) {
        stat.kana++;
      } else if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x1100 && c <= 0x11FF)) {
        stat.hangul++;
      } else if ((c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A)) {
        stat.latin++;
      } else if (c >= 0x0400 && c <= 0x04FF) {
        stat.cyr++;
      }
    }
    return stat;
  }

  /** 取一段有代表性的正文做样本：优先叶子块，凑不够就用整页文字 */
  function sampleText() {
    var out = '';
    var cands = document.querySelectorAll(BLOCK_TAGS);
    for (var i = 0; i < cands.length && out.length < 1400; i++) {
      var el = cands[i];
      if (skipped(el)) continue;
      if (el.querySelector(NESTED)) continue;
      var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
      if (t.length < 6) continue;
      out += t + ' ';
    }
    if (out.replace(/\s/g, '').length < 30 && document.body) {
      out = document.body.innerText || document.body.textContent || '';
    }
    return out.slice(0, 2000);
  }

  /**
   * 返回 { declared, guess, conf, variant, chars }。
   * guess 取 zh / en / ja / ko / latin / other / unknown —— 判不准就说不知道，
   * 让上层照常翻译，而不是猜一个错的语言把用户的页面锁死。
   */
  function detect() {
    var declared = (document.documentElement.getAttribute('lang') || '').trim();
    if (!declared) {
      var meta = document.querySelector(
        'meta[property="og:locale"], meta[name="language"], meta[http-equiv="content-language"]'
      );
      if (meta) declared = (meta.getAttribute('content') || '').trim();
    }

    var sample = sampleText();
    var s = countChars(sample);
    var letters = s.han + s.kana + s.hangul + s.latin + s.cyr;
    var guess = 'unknown';
    var conf = 0;

    // 少于 24 个字母基本没什么可判的（"加载中"、纯图页、还没渲染完）
    if (letters >= 24) {
      if (s.hangul > 2 && s.hangul / letters >= 0.15) {
        guess = 'ko';
        conf = s.hangul / letters;
      } else if (s.kana > 2 && s.kana / (s.han + s.kana) >= 0.03) {
        // 假名几乎只出现在日语里，哪怕比例很小也足以定性（日语汉字多，不能只看汉字）
        guess = 'ja';
        conf = Math.min(0.9, 0.4 + s.kana / (s.han + s.kana));
      } else if (s.han > 0 && s.han / (s.han + s.latin) >= 0.55 && s.han / letters >= 0.4) {
        guess = 'zh';
        conf = s.han / (s.han + s.latin);
      } else if (s.latin > 0 && s.latin / letters >= 0.6) {
        // 拉丁字母只能说明是拉丁语系。要判成英语得看高频功能词的比例：
        // 法语德语同样满屏字母，但 the/and/of/to 出现得少。
        var words = sample.split(/\s+/).filter(function (w) { return /^[A-Za-z][A-Za-z'’-]*$/.test(w); });
        var hits = (sample.match(EN_WORD) || []).length;
        var ratio = words.length ? hits / words.length : 0;
        if (words.length >= 8 && ratio >= 0.12) {
          guess = 'en';
          conf = Math.min(0.95, 0.5 + ratio);
        } else {
          guess = 'latin';
          conf = 0.55;
        }
      } else if (s.cyr > 0 && s.cyr / letters >= 0.5) {
        guess = 'other';
        conf = 0.6;
      }
    }

    // 繁简：汉字两边的占比，繁体站大多不写 lang，只能靠异形字
    var variant = 'unknown';
    if (guess === 'zh') {
      var trad = (sample.match(TRAD_RE) || []).length;
      var simp = (sample.match(SIMP_RE) || []).length;
      if (trad >= 3 && trad > simp * 1.5) variant = 'hant';
      else if (simp >= 3) variant = 'hans';
    }

    return {
      declared: declared,
      guess: guess,
      conf: Math.round(conf * 100) / 100,
      variant: variant,
      chars: letters
    };
  }

  window.__PB_TR = { collect: collect, apply: apply, restore: restore, detect: detect };
})();
