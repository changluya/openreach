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


  function legacyCopyText(text) {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.setAttribute('readonly', '');
    textarea.setAttribute('aria-hidden', 'true');
    textarea.style.position = 'fixed';
    textarea.style.left = '-9999px';
    textarea.style.top = '0';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);

    const selection = document.getSelection ? document.getSelection() : null;
    const savedRanges = [];
    if (selection) {
      for (let i = 0; i < selection.rangeCount; i += 1) {
        savedRanges.push(selection.getRangeAt(i));
      }
    }

    try {
      textarea.focus({ preventScroll: true });
    } catch (e) {
      textarea.focus();
    }
    textarea.select();
    textarea.setSelectionRange(0, textarea.value.length);

    let copied = false;
    try {
      copied = document.execCommand('copy');
    } catch (e) {
      copied = false;
    }

    document.body.removeChild(textarea);
    if (selection) {
      selection.removeAllRanges();
      savedRanges.forEach((range) => selection.addRange(range));
    }
    return copied;
  }

  async function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      try {
        await navigator.clipboard.writeText(text);
        return true;
      } catch (e) {
        // Clipboard API may still be rejected by browser permissions/policy.
        // Fall through to the legacy selection-based copy path.
      }
    }
    return legacyCopyText(text);
  }

  function showCopyResult(button, success) {
    const original = button.dataset.copyLabel || button.textContent || '复制';
    button.dataset.copyLabel = original;
    button.textContent = success ? '已复制' : '复制失败';
    button.classList.toggle('is-success', success);
    button.classList.toggle('is-error', !success);
    button.setAttribute('aria-live', 'polite');
    button.setAttribute('aria-label', success ? '复制成功' : '复制失败，请手动选择文本复制');

    window.setTimeout(() => {
      button.textContent = original;
      button.classList.remove('is-success', 'is-error');
      button.setAttribute('aria-label', original);
    }, success ? 1400 : 2200);
  }

  async function copyFromButton(button) {
    const selector = button.dataset.copy;
    if (!selector) {
      showCopyResult(button, false);
      return;
    }

    let target = null;
    try {
      target = document.querySelector(selector);
    } catch (e) {
      target = null;
    }
    if (!target) {
      showCopyResult(button, false);
      return;
    }

    const text = target.textContent || '';
    const success = await copyText(text);
    showCopyResult(button, success);
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
    copyFromButton(button);
  });

  document.querySelectorAll('[data-doc-select]').forEach((select) => {
    select.addEventListener('change', () => { window.location.href = select.value; });
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closeQrPreview();
  });

  syncThemeControls();
})();
