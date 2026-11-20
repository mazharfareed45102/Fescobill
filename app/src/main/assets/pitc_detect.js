// Tells the app what the PITC page currently shows. Returns a JSON string:
// {bill: true when an electricity bill is on screen, form: true when the search form is present,
//  message: any error/validation text the site shows}
(function () {
  try {
    var body = document.body;
    var text = ((body && body.innerText) || '').toUpperCase().replace(/\s+/g, ' ');
    var markers = ['CONSUMER ID', 'REFERENCE NO', 'BILL MONTH', 'DUE DATE', 'ISSUE DATE',
      'READING DATE', 'TARIFF', 'PAYABLE WITHIN DUE DATE', 'PAYABLE AFTER DUE DATE',
      'UNITS CONSUMED', 'METER NO', 'GRAND TOTAL', 'CONSUMER DETAIL', 'CURRENT BILL', 'ARREARS',
      'FEEDER', 'SANCTIONED LOAD', 'SAN LOAD', 'ENERGY CHARGES', 'LP SURCHARGE', 'L.P SURCHARGE'];
    var found = 0;
    for (var i = 0; i < markers.length; i++) {
      if (text.indexOf(markers[i]) >= 0) found++;
    }
    var path = (location.pathname + location.search).toLowerCase();
    var billUrl = /\/(general|industrial)\b/.test(path) && /(refno|appno)=/.test(path);
    var form = !!document.querySelector('input[name="searchTextBox"], #searchTextBox');
    if (!form) {
      // Fallback if the site renames its fields: a visible text box inside a form with a submit button.
      var forms = document.querySelectorAll('form');
      for (var f = 0; f < forms.length && !form; f++) {
        var box = forms[f].querySelector('input[type="text"], input[type="number"], input[type="tel"], input:not([type])');
        var submit = forms[f].querySelector('input[type="submit"], button[type="submit"], button:not([type])');
        form = !!(box && submit && (box.offsetWidth || box.offsetHeight));
      }
    }
    var message = '';
    var selectors = ['.validation-summary-errors', '.field-validation-error', '.alert-danger',
      '.alert-warning', '.text-danger', '#lblMessage', '.error-message', '.error'];
    for (var j = 0; j < selectors.length && !message; j++) {
      var els = document.querySelectorAll(selectors[j]);
      for (var k = 0; k < els.length; k++) {
        var t = (els[k].innerText || '').replace(/\s+/g, ' ').trim();
        if (t) { message = t.substring(0, 200); break; }
      }
    }
    var bill = found >= 6 || (billUrl && found >= 3);
    // Does the page ask for a code (captcha)? Then a person has to type it.
    var captcha = false;
    try {
      captcha = !!document.querySelector('img[src*="aptcha" i], img[id*="aptcha" i], img[class*="aptcha" i], ' +
        'img[alt*="aptcha" i], input[name*="aptcha" i], input[id*="aptcha" i], canvas[id*="aptcha" i], ' +
        'canvas[class*="aptcha" i], .g-recaptcha, .h-captcha, .cf-turnstile, iframe[src*="recaptcha"], ' +
        'iframe[src*="hcaptcha"], iframe[src*="turnstile"]');
    } catch (e) {
      captcha = /captcha/i.test((document.documentElement.innerHTML || '').substring(0, 300000));
    }
    // Bill month (e.g. "OCT 26"): the first month token after the "BILL MONTH" label. Same as PitcHtml.billMonth.
    var month = '';
    var at = text.indexOf('BILL MONTH');
    if (bill && at >= 0) {
      var m = /(?:^|[^A-Z0-9])(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[ -]?(\d{2})(?![0-9])/
        .exec(text.substring(at + 10, at + 410));
      if (m) month = m[1] + ' ' + m[2];
    }
    return JSON.stringify({ bill: bill, form: form, captcha: captcha && !bill, markers: found, billUrl: billUrl,
      message: message, month: month, url: location.href });
  } catch (e) {
    return JSON.stringify({ bill: false, form: false, markers: 0, message: '', error: String(e) });
  }
})()
