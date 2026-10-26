/*
 * PB Bridge —— 插件浏览器 JS API 垫片（由原生侧注入到每个页面）
 *
 * 提供的 API：
 *   PB.storage.get/set/remove/keys   插件级持久化存储（按插件 id 隔离）
 *   PB.http(opts, cb)                跨域网络请求（无视页面 CORS 限制）
 *   PB.clipboard.write(text, cb)     写剪贴板
 *   PB.notify(title, body, cb)       发送系统通知
 *   PB.tabs.open(url, cb)            新开标签页
 *   PB.page.url()/title()            当前页面信息
 *   PB.log(...)                      控制台日志（带 [PB] 前缀）
 *
 * 兼容别名（注意为异步回调风格，回调在末尾）：
 *   GM_setValue(key, value, cb) / GM_getValue(key, cb)
 *   GM_deleteValue(key, cb) / GM_listValues(cb)
 *   GM_xmlhttpRequest(opts, cb)      opts: {url, method, headers, data, onload, onerror}
 *   GM_setClipboard(text) / GM_notification(opts)
 * 同步可用（无需回调）：
 *   GM_info                          { scriptHandler, version, script: {name, version, author, icon} }
 *   GM_getResourceText(name)         manifest.resources 中声明的命名资源文本
 *   GM_registerMenuCommand(name,fn)  注册脚本菜单（当前版本仅记录日志，UI 菜单尚未开放）
 *   GM_openInTab(url, opts, cb)      新开标签页
 */
(function () {
  if (window.__PB_BRIDGE_READY__) return;
  window.__PB_BRIDGE_READY__ = true;

  var pending = {};
  var seq = 0;
  var PLUGIN_ID = null; // 当前正在执行的插件 id，由原生侧在调度每个插件前注入

  function post(method, params, cb) {
    var id = ++seq;
    if (cb) pending[id] = cb;
    if (PLUGIN_ID) params.__plugin = PLUGIN_ID;
    if (window.PBAndroid && window.PBAndroid.postMessage) {
      window.PBAndroid.postMessage(JSON.stringify({ id: id, method: method, params: params || {} }));
    } else if (cb) {
      cb({ error: 'bridge-unavailable' });
    }
  }

  // 原生侧通过该函数回传异步结果
  window.__PB_REPLY__ = function (json) {
    try {
      var r = JSON.parse(json);
      var cb = pending[r.id];
      if (cb) {
        delete pending[r.id];
        cb(r.result);
      }
    } catch (e) {}
  };

  var PB = {
    version: '1.0.0',

    /** GM_registerMenuCommand 注册的菜单（当前仅记录） */
    _menuCommands: [],

    /** 由原生插件调度器调用：标记当前执行的插件 */
    setPluginId: function (id) { PLUGIN_ID = id; },

    storage: {
      get: function (key, cb) { post('storage.get', { key: key }, cb); },
      set: function (key, value, cb) { post('storage.set', { key: key, value: value }, cb); },
      remove: function (key, cb) { post('storage.remove', { key: key }, cb); },
      keys: function (cb) { post('storage.keys', {}, cb); }
    },

    /** opts: {url, method, headers, data} */
    http: function (opts, cb) {
      post('http.request', {
        url: opts.url,
        method: opts.method || 'GET',
        headers: opts.headers || {},
        data: opts.data || null
      }, cb);
    },

    clipboard: {
      write: function (text, cb) { post('clipboard.write', { text: text }, cb); }
    },

    notify: function (title, body, cb) {
      post('notify', { title: title, body: body || '' }, cb);
    },

    tabs: {
      open: function (url, cb) { post('tabs.open', { url: url }, cb); },
      currentUrl: function () { return location.href; }
    },

    page: {
      url: function () { return location.href; },
      title: function () { return document.title; }
    },

    log: function () {
      var args = Array.prototype.slice.call(arguments);
      console.log.apply(console, ['[PB]'].concat(args));
    }
  };

  /* ---------- Tampermonkey 风格别名 ---------- */
  window.GM_setValue = function (key, value, cb) { PB.storage.set(key, value, cb); };
  window.GM_getValue = function (key, cb) { PB.storage.get(key, cb); };
  window.GM_deleteValue = function (key, cb) { PB.storage.remove(key, cb); };
  window.GM_listValues = function (cb) { PB.storage.keys(cb); };
  window.GM_xmlhttpRequest = function (opts, cb) {
    if (typeof opts === 'string') opts = { url: opts };
    PB.http(opts, function (res) {
      if (res && res.body !== undefined) {
        if (typeof opts.onload === 'function') {
          opts.onload({ status: res.status, responseText: res.body, responseHeaders: res.headers || {} });
        }
      } else if (typeof opts.onerror === 'function') {
        opts.onerror(res || { error: 'failed' });
      }
      if (cb) cb(res);
    });
  };
  window.GM_setClipboard = function (text, cb) { PB.clipboard.write(text, cb); };
  window.GM_notification = function (opts) {
    if (typeof opts === 'string') PB.notify(opts, '');
    else PB.notify(opts.title || '', opts.text || '');
  };

  /* ---------- 同步 API（依赖原生侧预注入的 window.__PB_META__ / window.__PB_RESOURCES__） ---------- */
  window.GM_info = {
    scriptHandler: 'PluginBrowser',
    version: '1.0.0',
    script: (typeof window.__PB_META__ !== 'undefined' && window.__PB_META__) || {}
  };
  window.GM_getResourceText = function (name) {
    var r = (typeof window.__PB_RESOURCES__ !== 'undefined' && window.__PB_RESOURCES__) || {};
    var t = r[name];
    return (typeof t === 'string') ? t : '';
  };
  window.GM_registerMenuCommand = function (name, fn) {
    PB._menuCommands = PB._menuCommands || [];
    PB._menuCommands.push({ name: name, fn: fn });
    PB.log('[menu-command]', name, '（当前版本仅记录，UI 菜单未开放）');
  };
  window.GM_openInTab = function (url, opts, cb) {
    if (typeof opts === 'function') { cb = opts; opts = {}; }
    PB.tabs.open(url, cb);
  };

  window.PB = PB;
})();
