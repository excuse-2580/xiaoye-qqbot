/* 小夜 QQBot 官网交互 —— 主题切换、滚动效果、FAB */

(function () {
  'use strict';

  /* ---------- 主题：跟随系统，可手动覆盖，记在本地 ---------- */
  var THEME_KEY = 'xiaoye-theme';

  function applyTheme(t) {
    document.documentElement.setAttribute('data-theme', t);
    var icon = document.getElementById('themeIcon');
    if (icon) icon.textContent = t === 'dark' ? 'light_mode' : 'dark_mode';
  }

  function systemTheme() {
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
      ? 'dark' : 'light';
  }

  var saved = null;
  try { saved = localStorage.getItem(THEME_KEY); } catch (e) {}
  applyTheme(saved || systemTheme());

  window.toggleTheme = function () {
    var now = document.documentElement.getAttribute('data-theme');
    var next = now === 'dark' ? 'light' : 'dark';
    applyTheme(next);
    try { localStorage.setItem(THEME_KEY, next); } catch (e) {}
  };

  // 没手动选过的话，跟着系统变
  if (!saved && window.matchMedia) {
    window.matchMedia('(prefers-color-scheme: dark)')
      .addEventListener('change', function (e) {
        applyTheme(e.matches ? 'dark' : 'light');
      });
  }

  /* ---------- 滚动：应用栏投影 + 返回顶部 ---------- */
  var bar = document.getElementById('appBar');
  var fab = document.getElementById('fab');

  function onScroll() {
    var y = window.scrollY || document.documentElement.scrollTop;
    if (bar) bar.classList.toggle('scrolled', y > 8);
    if (fab) fab.classList.toggle('show', y > 600);
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  /* ---------- 平滑滚动 ---------- */
  document.querySelectorAll('a[href^="#"]').forEach(function (a) {
    a.addEventListener('click', function (e) {
      var id = a.getAttribute('href');
      if (!id || id === '#') return;
      var el = document.querySelector(id);
      if (!el) return;
      e.preventDefault();
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  });
})();
