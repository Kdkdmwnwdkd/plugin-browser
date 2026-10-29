package com.pluginbrowser.site

/**
 * 注入到页面里的适配脚本。
 *
 * 之所以放在这里而不是拆成一堆 .js 资源：这两段脚本都需要「按站点条件」才注入，
 * 放在代码里能直接被规则命中与否决定，不用再维护一套资源—规则对应关系。
 */
object SiteScripts {

    /**
     * 拦「打开 App」：隐藏横幅浮层 + 拦掉指向 App 的点击。
     *
     * 必须能重复执行（每次加载完都会注一次），所以入口先看哨兵；
     * 遮罩大多是页面脚本后插进来的，光靠一次 querySelector 抓不到，
     * 所以挂一个节流的 MutationObserver 兜后续插入的节点。
     */
    val APP_BANNER: String = """
(function () {
  if (window.__pbBannerGuard) return 'already';
  window.__pbBannerGuard = true;

  var SEL = [
    '[class*="openapp" i]', '[id*="openapp" i]',
    '[class*="open-app" i]', '[id*="open-app" i]',
    '[class*="open-in-app" i]', '[id*="open-in-app" i]',
    '[class*="jumpapp" i]', '[class*="jump-app" i]',
    '[class*="launchapp" i]', '[class*="launch-app" i]',
    '[class*="callapp" i]', '[class*="call-app" i]',
    '[class*="downloadapp" i]', '[class*="download-app" i]', '[class*="app-download" i]',
    '[id*="download-app" i]',
    '[class*="smart-banner" i]', '[id*="smart-banner" i]', '#smartbanner'
  ].join(',');

  var SCHEME = /^(intent:|market:|itms-apps:|openapp\.|(taobao|tmall|alipay|alipays|zhihu|weibo|sinaweibo|bilibili|xhsdiscover|snssdk1128|snssdk2329|kwai|vipshop|pptv|sohu|iqiyi|youku|qqvideo|tenvideo|mgtv|douyin):)/i;

  function style() {
    if (document.getElementById('pb-site-style')) return;
    var s = document.createElement('style');
    s.id = 'pb-site-style';
    s.textContent = SEL + '{display:none !important;}';
    (document.head || document.documentElement).appendChild(s);
  }

  function hide() {
    var nodes = document.querySelectorAll(SEL);
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el === document.body || el === document.documentElement) continue;
      el.style.setProperty('display', 'none', 'important');
    }
  }

  function hint() {
    var old = document.getElementById('pb-site-hint');
    if (old && old.parentNode) old.parentNode.removeChild(old);
    if (!document.body) return;
    var d = document.createElement('div');
    d.id = 'pb-site-hint';
    d.textContent = '已拦截跳转 App';
    d.style.cssText = 'position:fixed;left:50%;bottom:64px;transform:translateX(-50%);z-index:2147483647;'
      + 'background:rgba(24,24,24,.86);color:#fff;font-size:13px;padding:8px 14px;border-radius:16px;'
      + 'font-family:system-ui,sans-serif;pointer-events:none;opacity:.96';
    document.body.appendChild(d);
    setTimeout(function () { if (d.parentNode) d.parentNode.removeChild(d); }, 1600);
  }

  function guard(e) {
    var node = e.target;
    if (!node || !node.closest) return;
    var a = node.closest('a[href], [data-href], [data-url]');
    if (!a) return;
    var href = a.getAttribute('href') || a.getAttribute('data-href') || a.getAttribute('data-url') || '';
    if (!href) return;
    if (!SCHEME.test(href)) return;
    e.preventDefault();
    e.stopPropagation();
    hint();
  }

  style();
  hide();

  if (document.documentElement) {
    var pending = false;
    var ob = new MutationObserver(function () {
      if (pending) return;
      pending = true;
      setTimeout(function () { pending = false; hide(); }, 600);
    });
    ob.observe(document.documentElement, { childList: true, subtree: true });
  }
  document.addEventListener('click', guard, true);

  var rawOpen = window.open;
  if (typeof rawOpen === 'function') {
    window.open = function (u) {
      if (typeof u === 'string' && SCHEME.test(u)) { hint(); return null; }
      return rawOpen.apply(window, arguments);
    };
  }
  return 'ok';
})()
""".trimIndent()

    /**
     * 让页面按手机屏幕排版，并把缩放权还给用户。
     *
     * 国内站点有两个通病，合起来就是「字小得看不清、还捏不大」：
     *
     *  1. 页面压根没有 viewport meta。WebView 的 useWideViewPort + loadWithOverviewMode
     *     会把页面按 980px 排版，再整体缩到屏幕宽——桌面版原样缩小，正文只剩 6~8px。
     *  2. 有 viewport 的，往往写着 user-scalable=no、maximum-scale=1。
     *     这是站点怕用户缩放出排版问题，但代价是用户连放大看清都做不到。
     *
     * 做法分两种，都不粗暴：
     *  · 已有 viewport：只把禁缩放的字段改回允许，width 一点不动（动了就重排，风险大）。
     *  · 没有 viewport：补一个 width=device-width 试一次；300ms 后如果页面被撑出
     *    明显横向滚动，说明它本来就没打算做移动端，立刻撤回，保持原来的桌面缩放。
     *
     * 必须可重复执行：每次 onPageFinished 都会注一次。
     */
    val VIEWPORT_FIX: String = """
(function () {
  try {
    var head = document.head || document.documentElement;
    if (!head) return 'no-head';

    function unlock(el) {
      var c = el.getAttribute('content') || '';
      var fixed = c
        .replace(/user-scalable\s*=\s*(no|0)/gi, 'user-scalable=yes')
        .replace(/maximum-scale\s*=\s*[\d.]+/gi, 'maximum-scale=5.0')
        .replace(/minimum-scale\s*=\s*[\d.]+/gi, 'minimum-scale=0.25');
      /* 只有 maximum-scale 没有 user-scalable 的，补一句明确允许 */
      if (/maximum-scale/i.test(fixed) && !/user-scalable/i.test(fixed)) {
        fixed += ', user-scalable=yes';
      }
      if (fixed !== c) el.setAttribute('content', fixed);
      return fixed;
    }

    /*
     * 还有一层不在 viewport 里：样式表写 touch-action:none / pan-y 时，
     * 双指手势在浏览器默认行为之前就被吃掉，viewport 改得再对也捏不动。
     * 用 auto 而不是 manipulation——后者会顺手把双击缩放也禁掉，
     * 而双击放大恰恰是手机上最省事的那一手。
     */
    function unlockTouch() {
      try {
        if (document.getElementById('__pbTouchFix')) return;
        var s = document.createElement('style');
        s.id = '__pbTouchFix';
        s.textContent =
          'html,body{touch-action:auto !important;-ms-touch-action:auto !important}';
        (document.head || document.documentElement).appendChild(s);
      } catch (e) {}
    }

    var vp = document.querySelector('meta[name="viewport"]');
    var result;

    if (vp) {
      result = 'unlocked';
      unlock(vp);
    } else {
      /* 已经试过并判定「不适合移动版」的，这次不再试，省一次重排 */
      if (window.__pbVpGaveUp === location.href) return 'skip';
      var m = document.createElement('meta');
      m.setAttribute('name', 'viewport');
      m.setAttribute('content',
        'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes');
      m.setAttribute('data-pb-injected', '1');
      head.appendChild(m);
      result = 'injected';

      setTimeout(function () {
        try {
          var de = document.documentElement;
          /* 横向被撑开说明这站是固定宽度的桌面版，硬套只会更难用 */
          if (de.scrollWidth > (window.innerWidth || 360) * 1.15) {
            m.parentNode && m.parentNode.removeChild(m);
            window.__pbVpGaveUp = location.href;
          }
        } catch (e) {}
      }, 300);
    }

    unlockTouch();

    /*
     * 站点常在首屏渲染完、路由切换、懒加载之后重写 viewport，把刚改的覆盖回去。
     * 只在 onPageFinished 改一次是不够的——实测模拟页每秒重写一次，3 秒后
     * 就又变回 user-scalable=no 了，用户这时去捏合当然不动。
     *
     * 所以观察器不主动断开：它只监听 head 里的属性/节点变化，页面重写一次就
     * 纠正一次，开销可以忽略（不是轮询）。页面整体销毁时随 DOM 一起回收。
     * unlock 内部有「值没变就不写」的判断，不会被自己的写入再次触发成死循环。
     */
    try {
      if (window.MutationObserver && !window.__pbVpObs) {
        var root = document.head || document.documentElement;
        var ob = new MutationObserver(function () {
          var cur = document.querySelector('meta[name="viewport"]');
          if (cur) unlock(cur);
        });
        ob.observe(root, {
          attributes: true,
          attributeFilter: ['content'],
          childList: true,
          subtree: true
        });
        window.__pbVpObs = ob;
      }
    } catch (e) {}

    var now = document.querySelector('meta[name="viewport"]');
    return result + '|' + (now ? now.getAttribute('content') : 'none');
  } catch (e) {
    return 'err';
  }
})()
""".trimIndent()

    /**
     * 收集页面里的图片。
     *
     * 只收 http(s) 的绝对地址，data: 的 base64 内联图（图标、占位图）一概跳过——
     * 那些存下来既没意义又把列表塞满。srcset 取最大的一档，background-image
     * 只在图片数量很少时补扫，避免大页面全量遍历样式。
     */
    val COLLECT_IMAGES: String = """
(function () {
  try {
    var out = [], seen = {};
    function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return null; } }
    function push(u, w, h) {
      if (!u || /^data:/i.test(u)) return;
      var a = abs(u);
      if (!a || !/^https?:/i.test(a)) return;
      if (seen[a]) return;
      seen[a] = 1;
      out.push({ url: a, w: w || 0, h: h || 0 });
    }
    var imgs = document.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
      var el = imgs[i];
      var src = el.currentSrc || el.getAttribute('data-src') || el.getAttribute('data-original') || el.src;
      var ss = el.getAttribute('srcset');
      if (ss) {
        var best = null, bestW = -1;
        ss.split(',').forEach(function (part) {
          var p = part.trim().split(/\s+/);
          var w = parseInt((p[1] || '').replace(/[^\d]/g, ''), 10) || 0;
          if (w >= bestW) { bestW = w; best = p[0]; }
        });
        if (best) src = best;
      }
      if (el.naturalWidth && el.naturalWidth < 80 && el.naturalHeight < 80) continue;
      push(src, el.naturalWidth, el.naturalHeight);
    }
    if (out.length < 40) {
      var nodes = document.querySelectorAll('[style*="background"]');
      for (var k = 0; k < nodes.length && k < 300; k++) {
        var bg = nodes[k].style.backgroundImage || '';
        var m = bg.match(/url\(["']?([^"')]+)["']?\)/);
        if (m) push(m[1], 0, 0);
      }
    }
    return JSON.stringify({
      ok: true,
      title: (document.title || location.hostname).slice(0, 40),
      page: location.href,
      items: out.slice(0, 300)
    });
  } catch (e) {
    return JSON.stringify({ ok: false, reason: String(e) });
  }
})()
""".trimIndent()

    /**
     * 正文提取。返回值就是结果：evaluateJavascript 会把它当成字符串再编码一层，
     * 所以这里返回 JSON.stringify 的结果，原生侧解码两次拿到对象。
     *
     * 打分思路照 Readability 的简化版：文本量越大越像正文，链接占比越高越不像，
     * 段落（p）越多越像。宁可多留一点也不要提空——提空了用户只会觉得按钮坏了。
     */
    val READER_EXTRACT: String = """
(function () {
  try {
    var DROP = 'script,style,noscript,iframe,form,nav,header,footer,aside,button,input,select,textarea,canvas,svg,video,audio,[aria-hidden="true"],[role="navigation"],[role="complementary"]';

    function txt(el) {
      if (!el) return '';
      return (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
    }

    function textLen(el) {
      return (el.textContent || '').replace(/\s+/g, ' ').trim().length;
    }

    var cands = [];
    var strong = document.querySelectorAll('article, main, [role="main"], [itemprop="articleBody"], #js_content, .rich_media_content, .article-content, .article_content, .post-content, .entry-content, .main-content, #article, #content, .content');
    for (var i = 0; i < strong.length; i++) cands.push({ el: strong[i], bonus: 3000 });
    if (!cands.length) {
      var blocks = document.querySelectorAll('div, section, td');
      for (var j = 0; j < blocks.length; j++) cands.push({ el: blocks[j], bonus: 0 });
    }

    var best = null, bestScore = 0;
    for (var k = 0; k < cands.length; k++) {
      var el = cands[k].el;
      var len = textLen(el);
      if (len < 240) continue;
      var linkLen = 0;
      var as = el.querySelectorAll('a');
      for (var m = 0; m < as.length; m++) linkLen += (as[m].textContent || '').length;
      var ps = el.querySelectorAll('p').length;
      var s = len - linkLen * 1.4 + ps * 30 + cands[k].bonus;
      if (el.getElementsByTagName('*').length > 4000) s -= 6000;
      if (s > bestScore) { bestScore = s; best = el; }
    }
    if (!best) return JSON.stringify({ ok: false, reason: 'no-content' });

    var clone = best.cloneNode(true);
    var junk = clone.querySelectorAll(DROP);
    for (var n = junk.length - 1; n >= 0; n--) {
      var j2 = junk[n];
      if (j2.parentNode) j2.parentNode.removeChild(j2);
    }

    var all = clone.querySelectorAll('p, div, span, section');
    for (var q = all.length - 1; q >= 0; q--) {
      var e2 = all[q];
      if (e2.querySelector('img, picture, pre, table, code, h1, h2, h3, blockquote')) continue;
      var t2 = (e2.textContent || '').replace(/\s+/g, '');
      if (t2.length === 0) { if (e2.parentNode) e2.parentNode.removeChild(e2); continue; }
      var lk = e2.querySelectorAll('a');
      if (t2.length < 40 && lk.length === 1) {
        var lt = (lk[0].textContent || '').replace(/\s+/g, '').length;
        if (lt >= t2.length - 2 && e2.parentNode) e2.parentNode.removeChild(e2);
      }
    }

    var h1 = document.querySelector('h1');
    var bylineEl = document.querySelector('[rel="author"], .author, .byline, .article-author, .rich_media_meta_text, #js_name');
    var html = clone.innerHTML || '';
    if (html.length > 400000) html = html.slice(0, 400000);

    return JSON.stringify({
      ok: true,
      title: txt(h1) || document.title || location.hostname,
      site: location.hostname,
      byline: txt(bylineEl).slice(0, 60),
      url: location.href,
      length: textLen(clone),
      html: html
    });
  } catch (e) {
    return JSON.stringify({ ok: false, reason: String(e) });
  }
})()
""".trimIndent()


    /**
     * 把一段 CSS 注入成 <style>。
     *
     * 不用 evaluateJavascript 拼字符串插值（选择器里的单引号会把脚本拼坏），
     * 这里统一做转义后再嵌进 JS 字符串。
     */
    fun hideCss(css: String): String {
        val literal = css
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", " ")
        return "(function(){var id='__pb_block_style';var el=document.getElementById(id);" +
            "if(!el){el=document.createElement('style');el.id=id;" +
            "(document.head||document.documentElement).appendChild(el);}" +
            "el.textContent='$literal';return 'ok';})()"
    }

    /**
     * 元素屏蔽的点选模式。
     *
     * 不猜「哪块是广告」——猜不准还会连正文一起藏掉。
     * 让用户点一下要藏的东西，这里算出能稳定命中的选择器交给原生存起来，
     * 下次打开这个站点直接按选择器隐藏。
     *
     * 用捕获阶段的 click 并 preventDefault：很多站点的浮层是「点任意处跳转」，
     * 不拦住的话用户一点就被带走了。
     */
    val ELEMENT_PICK: String = """
(function () {
  if (window.__pbPickerActive) return 'already';
  window.__pbPickerActive = true;

  var style = document.createElement('style');
  style.id = '__pb_picker_style';
  style.textContent = '*.__pb_pick_hover{outline:2px solid #ff6b35 !important;' +
    'outline-offset:-2px !important;background:rgba(255,107,53,.12) !important;}';
  (document.head || document.documentElement).appendChild(style);

  var last = null;
  function clearHover() {
    if (last) { try { last.classList.remove('__pb_pick_hover'); } catch (e) {} }
    last = null;
  }
  function esc(s) {
    if (window.CSS && CSS.escape) return CSS.escape(s);
    return String(s).replace(/[^a-zA-Z0-9_-]/g, '\\\$&');
  }
  function pathOf(el) {
    var parts = [];
    while (el && el.nodeType === 1 && parts.length < 5) {
      if (el.id) { parts.unshift('#' + esc(el.id)); break; }
      var sel = el.tagName.toLowerCase();
      var raw = (typeof el.className === 'string') ? el.className : '';
      var cls = raw.trim().split(/\s+/).filter(Boolean);
      if (cls.length) sel += '.' + cls.slice(0, 2).map(esc).join('.');
      var parent = el.parentElement;
      if (parent) {
        var idx = Array.prototype.indexOf.call(parent.children, el) + 1;
        sel += ':nth-child(' + idx + ')';
      }
      parts.unshift(sel);
      el = parent;
    }
    return parts.join(' > ');
  }
  function labelOf(el) {
    var t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
    return el.tagName.toLowerCase() + (t ? ' · ' + t.slice(0, 24) : '');
  }
  function stop() {
    window.__pbPickerActive = false;
    document.removeEventListener('click', onClick, true);
    document.removeEventListener('mousemove', onMove, true);
    document.removeEventListener('keydown', onKey, true);
    clearHover();
    var st = document.getElementById('__pb_picker_style');
    if (st) st.remove();
  }
  function onClick(e) {
    var el = e.target;
    if (!el || el.nodeType !== 1) return;
    e.preventDefault();
    e.stopPropagation();
    var selector = pathOf(el);
    var label = labelOf(el);
    stop();
    try { el.style.setProperty('display', 'none', 'important'); } catch (err) {}
    try { window.__pbPicker.picked(selector, label); } catch (err) {}
  }
  function onMove(e) {
    var el = e.target;
    if (!el || el.nodeType !== 1 || el === document.documentElement || el === document.body) return;
    if (el === last) return;
    clearHover();
    last = el;
    try { el.classList.add('__pb_pick_hover'); } catch (err) {}
  }
  function onKey(e) {
    if (e.key !== 'Escape') return;
    e.preventDefault();
    stop();
    try { window.__pbPicker.cancelled(); } catch (err) {}
  }

  document.addEventListener('click', onClick, true);
  document.addEventListener('mousemove', onMove, true);
  document.addEventListener('keydown', onKey, true);
  return 'ok';
})()
""".trimIndent()
}
