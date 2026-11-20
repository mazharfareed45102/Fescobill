// Every 10-digit number shown on the bill page (the Consumer ID is one of them). Returns a JSON array.
(function () {
  try {
    var text = (document.body && document.body.innerText) || '';
    var out = [];
    var re = /(^|[^0-9])([0-9]{10})(?![0-9])/g;
    var m;
    while ((m = re.exec(text)) !== null) {
      if (out.indexOf(m[2]) < 0) out.push(m[2]);
      re.lastIndex = m.index + m[0].length;
    }
    return JSON.stringify(out);
  } catch (e) {
    return '[]';
  }
})()
