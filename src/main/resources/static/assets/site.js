(function () {
  const root = document.documentElement;
  const THEME_KEY = 'openreach-theme';

  function currentTheme() {
    return root.dataset.theme === 'dark' ? 'dark' : 'light';
  }

  function syncThemeControls() {
    const dark = currentTheme() === 'dark';
    document.querySelectorAll('[data-theme-toggle]').forEach((button) => {
      button.setAttribute('aria-checked', dark ? 'true' : 'false');
      button.setAttribute('aria-label', dark ? '切换浅色主题' : '切换深色主题');
      button.title = dark ? '切换到浅色主题' : '切换到深色主题';
    });
  }

  function setTheme(theme) {
    root.dataset.theme = theme;
    try { localStorage.setItem(THEME_KEY, theme); } catch (e) {}
    syncThemeControls();
  }

  let qrModal = null;
  let qrLastTrigger = null;

  function ensureQrModal() {
    if (qrModal) return qrModal;
    qrModal = document.createElement('div');
    qrModal.className = 'qr-modal';
    qrModal.setAttribute('role', 'dialog');
    qrModal.setAttribute('aria-modal', 'true');
    qrModal.setAttribute('aria-label', 'OpenReach 微信交流群二维码预览');
    qrModal.innerHTML = `
      <div class="qr-modal-panel" role="document">
        <button class="qr-modal-close" type="button" data-qr-close aria-label="关闭二维码预览">×</button>
        <img class="qr-modal-image" alt="OpenReach 微信交流群二维码放大预览" />
        <h3 class="qr-modal-title">OpenReach 微信交流群</h3>
        <p class="qr-modal-tip">使用微信扫码加入交流群 · 点击遮罩或按 Esc 关闭</p>
      </div>`;
    document.body.appendChild(qrModal);
    return qrModal;
  }

  function openQrPreview(trigger) {
    const modal = ensureQrModal();
    const image = modal.querySelector('.qr-modal-image');
    image.src = trigger.dataset.qrPreview;
    qrLastTrigger = trigger;
    document.body.classList.add('qr-modal-open');
    requestAnimationFrame(() => modal.classList.add('is-open'));
    modal.querySelector('[data-qr-close]').focus();
  }

  function closeQrPreview() {
    if (!qrModal || !qrModal.classList.contains('is-open')) return;
    qrModal.classList.remove('is-open');
    document.body.classList.remove('qr-modal-open');
    if (qrLastTrigger) qrLastTrigger.focus();
  }

  document.addEventListener('click', (event) => {
    const qrTrigger = event.target.closest('[data-qr-preview]');
    if (qrTrigger) {
      openQrPreview(qrTrigger);
      return;
    }

    if (event.target.closest('[data-qr-close]') || (qrModal && event.target === qrModal)) {
      closeQrPreview();
      return;
    }

    const themeButton = event.target.closest('[data-theme-toggle]');
    if (themeButton) {
      setTheme(currentTheme() === 'dark' ? 'light' : 'dark');
      return;
    }

    const button = event.target.closest('[data-copy]');
    if (!button) return;
    const target = document.querySelector(button.dataset.copy);
    if (!target) return;
    const text = target.innerText;
    if (!navigator.clipboard) return;
    navigator.clipboard.writeText(text).then(() => {
      const old = button.textContent;
      button.textContent = '已复制';
      setTimeout(() => button.textContent = old, 1200);
    });
  });

  document.querySelectorAll('[data-doc-select]').forEach((select) => {
    select.addEventListener('change', () => { window.location.href = select.value; });
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closeQrPreview();
  });

  syncThemeControls();
})();
