/*
 * 内置插件：网页净化
 * 演示：DOM 查询与移除、MutationObserver 动态监听
 * 注意：选择器偏保守，含大量文字的容器不会被删除，避免误伤正文
 *
 * 性能约束：
 *  1. 只用能走索引的选择器。`[class*="popup-overlay"]` 这种子串属性匹配没法用索引，
 *     会退化成整棵 DOM 的线性扫描，GitHub 这种上万元素的页面单次就要几百毫秒，所以不用。
 *  2. MutationObserver 只处理「新增了元素节点」的批次，纯文本/属性变动直接丢弃，
 *     防抖从 400ms 提到 1s。原版只要页面有动画就在持续全文档重扫。
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
    '#ads', '#ad', '#adContainer'
  ];
  var QUERY = SELECTORS.join(',');

  function sweep(root) {
    var list;
    try {
      list = (root || document).querySelectorAll(QUERY);
    } catch (e) {
      return 0;
    }
    var removed = 0;
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (!el || !el.parentNode) continue;
      // 含较多文字的容器基本是正文/导航，跳过（先看长度，避免每次都对长文本跑正则）
      var text = el.textContent;
      if (text && text.length > 400 && text.replace(/\s/g, '').length > 300) continue;
      el.parentNode.removeChild(el);
      removed++;
    }
    return removed;
  }

  sweep(document);

  var timer = null;
  var observer = new MutationObserver(function (records) {
    if (timer) return;
    var added = false;
    for (var i = 0; i < records.length; i++) {
      var nodes = records[i].addedNodes;
      if (nodes && nodes.length) {
        added = true;
        break;
      }
    }
    if (!added) return;
    timer = setTimeout(function () {
      timer = null;
      sweep(document);
    }, 1000);
  });
  if (document.body) {
    observer.observe(document.body, { childList: true, subtree: true });
  }
})();
