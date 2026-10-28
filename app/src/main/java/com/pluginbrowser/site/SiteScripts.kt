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
}
