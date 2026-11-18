// Fills the PITC search form like a person would: choose "Reference No" (refno) or
// "Customer ID" (appno), type the number, press Search. Called as (<this>)(value, mode).
(function (value, mode) {
  function fire(el, type) {
    try { el.dispatchEvent(new Event(type, { bubbles: true })); } catch (e) { /* old WebView */ }
  }
  function visible(el) {
    return !!(el && (el.offsetWidth || el.offsetHeight || el.getClientRects().length));
  }
  var input = document.querySelector('input[name="searchTextBox"]') || document.getElementById('searchTextBox');
  if (!input) {
    var all = document.querySelectorAll('input[type="text"], input[type="number"], input[type="tel"], input:not([type])');
    for (var i = 0; i < all.length; i++) {
      if (visible(all[i])) { input = all[i]; break; }
    }
  }
  if (!input) return 'no-input';

  var radio = document.querySelector('input[name="rbSearchByList"][value="' + mode + '"]');
  if (radio && !radio.checked) {
    radio.click(); // a real click, so the page's own handlers run exactly once
    if (!radio.checked) radio.checked = true;
  }

  // Give the page a moment to react to the radio button before typing and submitting.
  setTimeout(function () {
    try { input.focus(); } catch (e) { /* ignore */ }
    input.value = value;
    fire(input, 'input');
    fire(input, 'change');
    fire(input, 'keyup');
    var form = input.form || document.getElementById('SubmitForm') || document.querySelector('form');
    var button = document.querySelector('input[name="btnSearch"], #btnSearch, button[name="btnSearch"]');
    if (!button && form) {
      button = form.querySelector('input[type="submit"], button[type="submit"], button:not([type])');
    }
    setTimeout(function () {
      if (button) {
        button.click();
      } else if (form) {
        if (form.requestSubmit) form.requestSubmit(); else form.submit();
      }
    }, 150);
  }, 350);

  return radio ? 'ok' : 'ok-no-radio';
})
