// Makes the site's own "Print" button save the bill as a PDF through the app.
(function () {
  try {
    if (window.FescoApp && !window.__fescoHooked) {
      window.__fescoHooked = true;
      window.print = function () { window.FescoApp.print(); };
    }
  } catch (e) { /* ignore */ }
})()
