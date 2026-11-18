// If the bill is wider than the phone screen, lay it out at its full width so the app can zoom
// out and show the whole bill like the paper copy. Returns 'fitted' or 'ok'.
(function () {
  try {
    var doc = document.documentElement, body = document.body;
    var w = Math.max(doc.scrollWidth, body ? body.scrollWidth : 0);
    if (w <= window.innerWidth + 16) return 'ok';
    var meta = document.querySelector('meta[name="viewport"]');
    if (!meta) {
      meta = document.createElement('meta');
      meta.name = 'viewport';
      (document.head || doc).appendChild(meta);
    }
    meta.content = 'width=' + w + ', user-scalable=yes';
    return 'fitted';
  } catch (e) {
    return 'ok';
  }
})()
