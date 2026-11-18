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
    return JSON.stringify({ bill: bill, form: form, markers: found, billUrl: billUrl,
      message: message, url: location.href });
  } catch (e) {
    return JSON.stringify({ bill: false, form: false, markers: 0, message: '', error: String(e) });
  }
})()
