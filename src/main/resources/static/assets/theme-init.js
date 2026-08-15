(function () {
  'use strict';
  try {
    var saved = localStorage.getItem('openreach-theme');
    var theme = saved === 'dark' || saved === 'light' ? saved : 'light';
    document.documentElement.dataset.theme = theme;
  } catch (e) {
    document.documentElement.dataset.theme = 'light';
  }
})();
