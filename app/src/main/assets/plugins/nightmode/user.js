/*
 * 内置插件：网页夜间模式
 * 演示：注入 CSS、使用 PB.storage 记录启用次数
 */
(function () {
  if (document.getElementById('pb-night-style')) return;

  var css = [
    'html { background-color:#ffffff !important; }',
    'html { filter: invert(1) hue-rotate(180deg) !important; }',
    'img, picture, video, canvas, svg, embed, object,',
    '[style*="background-image"] { filter: invert(1) hue-rotate(180deg) !important; }'
  ].join('\n');

  var style = document.createElement('style');
  style.id = 'pb-night-style';
  style.textContent = css;
  (document.head || document.documentElement).appendChild(style);

  if (window.PB && PB.storage) {
    PB.storage.get('enable_count', function (v) {
      var n = (v && v.enable_count) || 0;
      PB.storage.set('enable_count', { enable_count: n + 1 });
      PB.log('夜间模式第', n + 1, '次启用');
    });
  }
})();
