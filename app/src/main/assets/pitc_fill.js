// Fills the PITC search form like a person would: choose "Reference No" (refno) or "Customer ID"
// (appno) and type the number. With submit = true it also presses Search.
// When the page asks for a code (captcha), call it with submit = false: the number is filled in and
// the cursor is put in the code box. The person types the code and presses Search themselves.
// Called as (<this>)(value, mode, submit).
(function (value, mode, submit) {
  function fire(el, type) {
    try { el.dispatchEvent(new Event(type, { bubbles: true })); } catch (e) { /* old WebView */ }
  }
  function visible(el) {
    return !!(el && (el.offsetWidth || el.offsetHeight || el.getClientRects().length));
  }
  function textInputs() {
    var all = document.querySelectorAll('input[type="text"], input[type="number"], input[type="tel"], input:not([type])');
    var out = [];
    for (var i = 0; i < all.length; i++) if (visible(all[i])) out.push(all[i]);
    return out;
  }
  function isCodeBox(el) {
    var s = ((el.name || '') + ' ' + (el.id || '') + ' ' + (el.getAttribute('placeholder') || '')).toLowerCase();
    return s.indexOf('captcha') >= 0;
  }
  var codeBox = null;
  var inputs = textInputs();
  for (var c = 0; c < inputs.length; c++) if (isCodeBox(inputs[c])) { codeBox = inputs[c]; break; }

  var input = document.querySelector('input[name="searchTextBox"]') || document.getElementById('searchTextBox');
  if (!input) {
    for (var i = 0; i < inputs.length; i++) {
      if (inputs[i] !== codeBox && !/rucode/i.test(inputs[i].name || '')) { input = inputs[i]; break; }
    }
  }
  if (!codeBox && submit === false) {
    // A code box without "captcha" in its name: the visible, empty text box that is not the number box.
    for (var k = 0; k < inputs.length; k++) {
      if (inputs[k] !== input && !inputs[k].value && !/rucode/i.test(inputs[k].name || '')) { codeBox = inputs[k]; break; }
    }
  }
  function focusCode() {
    if (!codeBox) return;
    try { codeBox.scrollIntoView({ block: 'center' }); } catch (e) { codeBox.scrollIntoView(); }
    try { codeBox.focus(); } catch (e) { /* ignore */ }
  }
  if (!input) {
    focusCode();
    return codeBox ? 'code-only' : 'no-input';
  }

  var radio = document.querySelector('input[name="rbSearchByList"][value="' + mode + '"]');
  if (radio && !radio.checked) {
    radio.click(); // a real click, so the page's own handlers run exactly once
    if (!radio.checked) radio.checked = true;
  }

  // Give the page a moment to react to the radio button before typing (and submitting).
  setTimeout(function () {
    if (input.value !== value) {
      try { input.focus(); } catch (e) { /* ignore */ }
      input.value = value;
      fire(input, 'input');
      fire(input, 'change');
      fire(input, 'keyup');
    }
    if (submit === false) {
      focusCode();
      return;
    }
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
