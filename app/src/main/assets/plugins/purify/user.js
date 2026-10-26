/*
 * 内置插件：网页净化
 * 演示：DOM 查询与移除、MutationObserver 动态监听
 * 注意：选择器偏保守，含大量文字的容器不会被删除，避免误伤正文
 */
(function () {
  if (window.__pb_purify__) return;
  window.__pb_purify__ = true;

  var SELECTORS = [
    'ins.adsbygoogle',
    'iframe[src*="doubleclick.net"]',
    'iframe[src*="googlesyndication"]',
    'iframe[id^="google_ads_iframe"]',
    'iframe[src*="cpro.baidu.com"]',
    'iframe[src*="pos.baidu.com"]',
    '[data-ad]',
    '[aria-label="广告"]',
    '.ad', '.ads', '.advert', '.advertisement', '.ad-banner', '.ad-box', '.ad-container',
    '.adsense', '.adslot',
    '#ads', '#ad', '#adContainer',
    '[class*="popup-overlay"]',
    '[class*="floating-ad"]'
  ];

  function removeAds(root) {
    var list;
    try {
      list = (root || document).querySelectorAll(SELECTORS.join(','));
    } catch (e) {
      return 0;
    }
    var removed = 0;
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (!el || !el.parentNode) continue;
      // 含较多文字的容器跳过，防止误伤正文/导航
      if (el.textContent && el.textContent.replace(/\s/g, '').length > 300) continue;
      el.parentNode.removeChild(el);
      removed++;
    }
    return removed;
  }

  removeAds(document);

  // 动态内容监听（防抖 400ms）
  var timer = null;
  var observer = new MutationObserver(function () {
    if (timer) return;
    timer = setTimeout(function () {
      timer = null;
      removeAds(document);
    }, 400);
  });
  if (document.documentElement) {
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }
})();
