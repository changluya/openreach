(() => {
  'use strict';

  const ENDPOINT_LABELS = {
    '/api/web/search': 'Web Search',
    '/api/web/image-search': 'Image Search',
    '/api/web/read': 'Web Read'
  };

  const state = {
    activePeriod: 'today',
    customRange: null,
    page: 1,
    pageSize: 20,
    records: [],
    total: 0,
    totalPages: 0,
    exportingFailures: false
  };

  const els = {
    periodDescription: document.getElementById('period-description'),
    metricTotal: document.getElementById('metric-total'),
    metricSuccess: document.getElementById('metric-success'),
    metricFailure: document.getElementById('metric-failure'),
    metricUniqueIp: document.getElementById('metric-unique-ip'),
    metricLatency: document.getElementById('metric-latency'),
    metricP95: document.getElementById('metric-p95'),
    successRate: document.getElementById('metric-success-rate'),
    totalNote: document.getElementById('metric-total-note'),
    endpointPeriodLabel: document.getElementById('endpoint-period-label'),
    trendChart: document.getElementById('trend-chart'),
    endpointList: document.getElementById('endpoint-list'),
    tableBody: document.getElementById('request-table-body'),
    recordCount: document.getElementById('record-count'),
    emptyState: document.getElementById('empty-state'),
    keyword: document.getElementById('keyword-filter'),
    status: document.getElementById('status-filter'),
    endpoint: document.getElementById('endpoint-filter'),
    reset: document.getElementById('reset-filters'),
    exportFailure: document.getElementById('export-failure-records'),
    failureCard: document.getElementById('failure-card'),
    drawer: document.getElementById('request-drawer'),
    drawerBody: document.getElementById('drawer-body'),
    drawerClose: document.getElementById('drawer-close'),
    backdrop: document.getElementById('drawer-backdrop'),
    lastRefresh: document.getElementById('last-refresh'),
    storageStatus: document.getElementById('monitor-storage-status'),
    monitorError: document.getElementById('monitor-error'),
    trendTitle: document.getElementById('trend-title'),
    trendPeriodLabel: document.getElementById('trend-period-label'),
    dateRangePicker: document.getElementById('date-range-picker'),
    dateRangeTrigger: document.getElementById('date-range-trigger'),
    dateRangeValue: document.getElementById('date-range-value'),
    dateRangePopover: document.getElementById('date-range-popover'),
    rangeStart: document.getElementById('range-start'),
    rangeEnd: document.getElementById('range-end'),
    rangeError: document.getElementById('date-range-error'),
    rangeCancel: document.getElementById('date-range-cancel'),
    rangeApply: document.getElementById('date-range-apply'),
    pageSummary: document.getElementById('page-summary'),
    pagePrev: document.getElementById('page-prev'),
    pageNext: document.getElementById('page-next')
  };

  function pad(value) { return String(value).padStart(2, '0'); }
  function dateKey(date) { return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`; }
  function formatDay(date) { return `${pad(date.getMonth() + 1)}/${pad(date.getDate())}`; }
  function formatDateTime(date) { return `${dateKey(date)} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`; }
  function formatTime(date) { return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`; }
  function startOfDay(date) { const value = new Date(date); value.setHours(0, 0, 0, 0); return value; }
  function endOfDay(date) { const value = new Date(date); value.setHours(23, 59, 59, 999); return value; }
  function parseDateInput(value) { if (!value) return null; const parts = value.split('-').map(Number); if (parts.length !== 3 || parts.some(Number.isNaN)) return null; return new Date(parts[0], parts[1] - 1, parts[2]); }
  function formatRangeLabel(start, end) { return `${dateKey(start)} ~ ${dateKey(end)}`; }
  function escapeHtml(value) { return String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#039;'); }
  function preview(value, maxLength = 70) { const text = String(value ?? ''); return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text; }
  function prettyPayload(value) {
    if (value == null || value === '') return '(payload 已过保留期或为空)';
    try { return JSON.stringify(JSON.parse(value), null, 2); } catch (_) { return String(value); }
  }

  function boundsForPeriod(period) {
    const now = new Date();
    if (period === 'today') return { start: startOfDay(now), end: now };
    if (period === '7d') { const start = startOfDay(now); start.setDate(start.getDate() - 6); return { start, end: now }; }
    if (period === 'custom' && state.customRange) {
      const start = startOfDay(parseDateInput(state.customRange.start));
      let end = endOfDay(parseDateInput(state.customRange.end));
      if (end > now) end = now;
      return { start, end };
    }
    return { start: startOfDay(now), end: now };
  }

  function periodText() {
    if (state.activePeriod === 'today') return { note: '今日全部接口请求', description: '今天 00:00 至当前时间的接口调用情况', endpoint: '今日调用构成' };
    if (state.activePeriod === '7d') return { note: '近 7 日全部接口请求', description: '包含今天在内最近 7 个自然日的接口调用情况', endpoint: '近 7 日调用构成' };
    const bounds = boundsForPeriod('custom');
    const label = formatRangeLabel(bounds.start, bounds.end);
    return { note: '自定义日期范围全部接口请求', description: `${label} 的接口调用情况`, endpoint: `${label} 调用构成` };
  }

  function apiParams(extra = {}) {
    const { start, end } = boundsForPeriod(state.activePeriod);
    return { startTimeMs: start.getTime(), endTimeMs: end.getTime(), ...extra };
  }

  function buildApiUrl(path, params = {}) {
    const url = new URL(path, window.location.origin);
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') url.searchParams.set(key, String(value));
    });
    return url;
  }

  async function apiGet(path, params = {}) {
    const url = buildApiUrl(path, params);
    const response = await fetch(url, {
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      credentials: 'same-origin'
    });
    if (response.status === 401) { window.location.href = '/monitor/login'; throw new Error('登录状态已失效'); }
    if (!response.ok) {
      let message = `HTTP ${response.status}`;
      try { const body = await response.json(); message = body.message || body.code || message; } catch (_) { }
      throw new Error(message);
    }
    return response.json();
  }

  function showError(error) {
    els.monitorError.textContent = `监控数据加载失败：${error?.message || error}`;
    els.monitorError.hidden = false;
  }
  function clearError() { els.monitorError.hidden = true; els.monitorError.textContent = ''; }

  function renderMetrics(data) {
    els.metricTotal.textContent = Number(data.total || 0).toLocaleString('zh-CN');
    els.metricSuccess.textContent = Number(data.success || 0).toLocaleString('zh-CN');
    els.metricFailure.textContent = Number(data.failure || 0).toLocaleString('zh-CN');
    els.metricUniqueIp.textContent = Number(data.uniqueIpCount || 0).toLocaleString('zh-CN');
    els.metricLatency.textContent = Number(data.avgLatencyMs || 0).toLocaleString('zh-CN');
    els.metricP95.textContent = `${Number(data.p95LatencyMs || 0).toLocaleString('zh-CN')} ms`;
    els.successRate.textContent = `成功率 ${Number(data.successRate || 0).toFixed(1)}%`;
    const text = periodText();
    els.totalNote.textContent = text.note;
    els.periodDescription.textContent = text.description;
    els.endpointPeriodLabel.textContent = text.endpoint;
  }

  function buildTrendBuckets(points) {
    const { start, end } = boundsForPeriod(state.activePeriod);
    const map = new Map();
    points.forEach(item => {
      const date = new Date(item.bucketStartMs);
      const key = state.activePeriod === 'today' ? `${dateKey(date)}-${date.getHours()}` : dateKey(date);
      map.set(key, item);
    });
    const buckets = [];
    if (state.activePeriod === 'today') {
      for (let hour = 0; hour <= end.getHours(); hour += 1) {
        const d = new Date(start); d.setHours(hour, 0, 0, 0);
        const item = map.get(`${dateKey(d)}-${hour}`) || { success: 0, failure: 0 };
        buckets.push({ label: `${pad(hour)}:00`, title: `${dateKey(d)} ${pad(hour)}:00`, success: item.success || 0, failure: item.failure || 0 });
      }
    } else {
      const cursor = startOfDay(start);
      while (cursor <= end) {
        const item = map.get(dateKey(cursor)) || { success: 0, failure: 0 };
        buckets.push({ label: formatDay(cursor), title: dateKey(cursor), success: item.success || 0, failure: item.failure || 0 });
        cursor.setDate(cursor.getDate() + 1);
      }
    }
    return buckets;
  }

  function renderTrend(points) {
    const buckets = buildTrendBuckets(points || []);
    if (state.activePeriod === 'today') {
      els.trendTitle.textContent = '今日调用趋势';
      els.trendPeriodLabel.textContent = '按小时统计成功 / 失败请求量';
    } else {
      els.trendTitle.textContent = state.activePeriod === '7d' ? '近 7 日调用趋势' : '自定义范围调用趋势';
      els.trendPeriodLabel.textContent = `按天统计成功 / 失败请求量 · 共 ${buckets.length} 天`;
    }
    const max = Math.max(...buckets.map(item => item.success + item.failure), 1);
    els.trendChart.innerHTML = buckets.map(item => {
      const successHeight = item.success ? Math.max(3, Math.round(item.success / max * 168)) : 3;
      const failureHeight = item.failure ? Math.max(5, Math.round(item.failure / max * 168)) : 3;
      return `<div class="trend-day" title="${escapeHtml(item.title)}：成功 ${item.success}，失败 ${item.failure}"><span class="trend-count">${item.success + item.failure}</span><div class="trend-bars"><i class="trend-bar success" style="height:${successHeight}px"></i><i class="trend-bar failure" style="height:${failureHeight}px"></i></div><span class="trend-label">${escapeHtml(item.label)}</span></div>`;
    }).join('');
  }

  function renderEndpointDistribution(items) {
    const byEndpoint = new Map((items || []).map(item => [item.endpoint, item]));
    const total = Math.max((items || []).reduce((sum, item) => sum + Number(item.total || 0), 0), 1);
    els.endpointList.innerHTML = Object.keys(ENDPOINT_LABELS).map(endpoint => {
      const count = Number(byEndpoint.get(endpoint)?.total || 0);
      const percent = Math.round(count / total * 100);
      return `<div class="endpoint-row"><div class="endpoint-name"><strong>${escapeHtml(endpoint)}</strong><span>${ENDPOINT_LABELS[endpoint]} · ${percent}%</span></div><div class="endpoint-count">${count}</div><div class="endpoint-progress"><i style="width:${percent}%"></i></div></div>`;
    }).join('');
  }

  function syncFailureExportButton() {
    const failureMode = els.status.value === 'failure';
    els.exportFailure.hidden = !failureMode;
    els.exportFailure.disabled = !failureMode || state.total === 0 || state.exportingFailures;
    els.exportFailure.textContent = state.exportingFailures ? '正在生成失败日志…' : '↓ 导出失败请求';
    els.exportFailure.title = failureMode
      ? (state.total > 0 ? `通过后端导出当前筛选到的 ${state.total.toLocaleString('zh-CN')} 条失败请求完整日志` : '当前筛选条件下没有失败请求')
      : '';
  }

  function downloadFilename(response) {
    const disposition = response.headers.get('Content-Disposition') || '';
    const utf8 = disposition.match(/filename\*=UTF-8''([^;]+)/i);
    if (utf8) {
      try { return decodeURIComponent(utf8[1].replace(/^"|"$/g, '')); } catch (_) { }
    }
    const plain = disposition.match(/filename="?([^";]+)"?/i);
    return plain ? plain[1] : `openreach-failed-requests-${Date.now()}.log`;
  }

  async function exportFailureRecords() {
    if (els.status.value !== 'failure' || state.total === 0 || state.exportingFailures) return;
    state.exportingFailures = true;
    syncFailureExportButton();
    clearError();
    try {
      const url = buildApiUrl('/api/monitor/records/export', apiParams({
        status: 'failure',
        endpoint: els.endpoint.value,
        keyword: els.keyword.value.trim()
      }));
      const response = await fetch(url, {
        method: 'GET',
        headers: { Accept: 'text/plain' },
        cache: 'no-store',
        credentials: 'same-origin'
      });
      if (response.status === 401) {
        window.location.href = '/monitor/login';
        return;
      }
      if (!response.ok) {
        let message = `HTTP ${response.status}`;
        try {
          const contentType = response.headers.get('Content-Type') || '';
          if (contentType.includes('application/json')) {
            const body = await response.json();
            message = body.message || body.code || message;
          } else {
            const body = (await response.text()).trim();
            if (body) message = body.slice(0, 300);
          }
        } catch (_) { }
        throw new Error(`失败请求导出失败：${message}`);
      }
      const blob = await response.blob();
      const objectUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = objectUrl;
      link.download = downloadFilename(response);
      link.hidden = true;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    } catch (error) {
      showError(error);
    } finally {
      state.exportingFailures = false;
      syncFailureExportButton();
    }
  }

  function renderTable(page) {
    state.records = page.items || [];
    state.total = Number(page.total || 0);
    state.totalPages = Number(page.totalPages || 0);
    state.page = Number(page.page || 1);
    els.recordCount.textContent = state.total.toLocaleString('zh-CN');
    els.tableBody.innerHTML = state.records.map(record => {
      const time = new Date(record.requestTimeMs);
      return `<tr>
        <td class="time-cell">${formatTime(time)}<span>${dateKey(time)}</span></td>
        <td class="ip-cell">${escapeHtml(record.clientIp || '-')}</td>
        <td><span class="endpoint-chip">${escapeHtml(record.endpoint)}</span></td>
        <td><div class="json-preview" title="${escapeHtml(record.requestPreview || '')}">${escapeHtml(preview(record.requestPreview || ''))}</div></td>
        <td><div class="json-preview" title="${escapeHtml(record.responsePreview || '')}">${escapeHtml(preview(record.responsePreview || ''))}</div></td>
        <td><span class="status-badge ${record.success ? 'success' : 'failure'}">${record.success ? `成功 · ${record.httpStatus}` : `失败 · ${record.httpStatus}`}</span></td>
        <td class="latency-cell ${record.latencyMs >= 4000 ? 'slow' : ''}">${Number(record.latencyMs).toLocaleString('zh-CN')} ms</td>
        <td class="action-col"><button class="detail-button" type="button" data-trace-id="${escapeHtml(record.traceId)}">详情</button></td>
      </tr>`;
    }).join('');
    els.emptyState.hidden = state.records.length !== 0;
    const pages = Math.max(state.totalPages, 1);
    els.pageSummary.textContent = `第 ${Math.min(state.page, pages)} / ${pages} 页`;
    els.pagePrev.disabled = state.page <= 1;
    els.pageNext.disabled = state.totalPages === 0 || state.page >= state.totalPages;
    syncFailureExportButton();
  }

  function statusBadge(record) {
    return `<span class="status-badge ${record.success ? 'success' : 'failure'}">${record.success ? `成功 · HTTP ${record.httpStatus}` : `失败 · HTTP ${record.httpStatus}`}</span>`;
  }

  function openDetail(record) {
    els.drawerBody.innerHTML = `
      <div class="detail-summary">
        <div class="detail-meta"><span>请求状态</span><strong>${statusBadge(record)}</strong></div>
        <div class="detail-meta"><span>请求耗时</span><strong>${Number(record.latencyMs).toLocaleString('zh-CN')} ms</strong></div>
        <div class="detail-meta"><span>请求时间</span><strong>${formatDateTime(new Date(record.requestTimeMs))}</strong></div>
        <div class="detail-meta"><span>客户端 IP</span><strong>${escapeHtml(record.clientIp || '-')}</strong></div>
        <div class="detail-meta"><span>请求接口</span><strong>${escapeHtml(record.method)} ${escapeHtml(record.endpoint)}</strong></div>
        <div class="detail-meta"><span>Trace ID</span><strong>${escapeHtml(record.traceId)}</strong></div>
        <div class="detail-meta"><span>Provider</span><strong>${escapeHtml(record.provider || '-')}</strong></div>
        <div class="detail-meta"><span>Payload</span><strong>${record.payloadTruncated ? '已按容量上限截断' : '完整/未超限'}</strong></div>
      </div>
      ${record.success ? '' : `<div class="detail-section"><div class="detail-section-head"><h3>失败原因</h3></div><div class="error-panel">${escapeHtml(record.errorCode || '')}${record.errorCode && record.errorMessage ? ' · ' : ''}${escapeHtml(record.errorMessage || '未记录错误信息')}</div></div>`}
      <div class="detail-section"><div class="detail-section-head"><h3>输入参数</h3><span>${Number(record.requestBytes || 0).toLocaleString('zh-CN')} bytes</span></div><pre class="code-detail">${escapeHtml(prettyPayload(record.requestPayload))}</pre></div>
      <div class="detail-section"><div class="detail-section-head"><h3>输出参数</h3><span>${Number(record.responseBytes || 0).toLocaleString('zh-CN')} bytes</span></div><pre class="code-detail">${escapeHtml(prettyPayload(record.responsePayload))}</pre></div>`;
    els.backdrop.hidden = false;
    els.drawer.classList.add('open');
    els.drawer.setAttribute('aria-hidden', 'false');
    document.body.classList.add('drawer-open');
  }

  function closeDetail() {
    els.drawer.classList.remove('open');
    els.drawer.setAttribute('aria-hidden', 'true');
    els.backdrop.hidden = true;
    document.body.classList.remove('drawer-open');
  }

  async function refreshStatus() {
    const status = await apiGet('/api/monitor/status');
    els.storageStatus.textContent = status.available ? `Authenticated · ${status.storage}` : `Storage unavailable · ${status.storage}`;
  }

  async function refreshRecords() {
    const page = await apiGet('/api/monitor/records', apiParams({
      page: state.page,
      pageSize: state.pageSize,
      status: els.status.value,
      endpoint: els.endpoint.value,
      keyword: els.keyword.value.trim()
    }));
    renderTable(page);
  }

  async function refreshAll() {
    clearError();
    const bucket = state.activePeriod === 'today' ? 'hour' : 'day';
    try {
      const [overview, trend, distribution] = await Promise.all([
        apiGet('/api/monitor/overview', apiParams()),
        apiGet('/api/monitor/trend', apiParams({ bucket, timezoneOffsetMinutes: new Date().getTimezoneOffset() })),
        apiGet('/api/monitor/distribution', apiParams())
      ]);
      renderMetrics(overview);
      renderTrend(trend);
      renderEndpointDistribution(distribution);
      await refreshRecords();
      els.lastRefresh.textContent = formatDateTime(new Date());
    } catch (error) { showError(error); }
  }

  function setQuickPeriodState(period) {
    document.querySelectorAll('[data-period]').forEach(tab => {
      const active = tab.dataset.period === period;
      tab.classList.toggle('active', active);
      tab.setAttribute('aria-selected', active ? 'true' : 'false');
    });
    els.dateRangeTrigger.classList.toggle('active', period === 'custom');
  }
  function closeRangePicker() { els.dateRangePopover.hidden = true; els.dateRangeTrigger.setAttribute('aria-expanded', 'false'); els.rangeError.hidden = true; }
  function openRangePicker() {
    const now = new Date(); const defaultStart = startOfDay(now); defaultStart.setDate(defaultStart.getDate() - 6);
    els.rangeStart.max = dateKey(now); els.rangeEnd.max = dateKey(now);
    els.rangeStart.value = state.customRange?.start || dateKey(defaultStart);
    els.rangeEnd.value = state.customRange?.end || dateKey(now);
    els.rangeError.hidden = true; els.dateRangePopover.hidden = false; els.dateRangeTrigger.setAttribute('aria-expanded', 'true');
  }
  function applyCustomRange() {
    const start = parseDateInput(els.rangeStart.value); const end = parseDateInput(els.rangeEnd.value); const today = startOfDay(new Date());
    let message = '';
    if (!start || !end) message = '请选择完整的开始日期和结束日期。';
    else if (start > end) message = '开始日期不能晚于结束日期。';
    else if (end > today) message = '结束日期不能晚于今天。';
    else if (end.getTime() - start.getTime() > 366 * 86400000) message = '单次查询日期范围不能超过 366 天。';
    if (message) { els.rangeError.textContent = message; els.rangeError.hidden = false; return; }
    state.customRange = { start: dateKey(start), end: dateKey(end) };
    state.activePeriod = 'custom'; state.page = 1;
    els.dateRangeValue.textContent = formatRangeLabel(start, end); setQuickPeriodState('custom'); closeRangePicker(); refreshAll();
  }

  let keywordTimer;
  els.keyword.addEventListener('input', () => { clearTimeout(keywordTimer); keywordTimer = setTimeout(() => { state.page = 1; refreshRecords().catch(showError); }, 300); });
  [els.status, els.endpoint].forEach(control => control.addEventListener('change', () => { state.page = 1; refreshRecords().catch(showError); }));
  els.reset.addEventListener('click', () => { els.keyword.value = ''; els.status.value = 'all'; els.endpoint.value = 'all'; state.page = 1; refreshRecords().catch(showError); });
  els.exportFailure.addEventListener('click', exportFailureRecords);
  els.failureCard.addEventListener('click', () => { els.status.value = 'failure'; state.page = 1; refreshRecords().then(() => document.getElementById('request-records').scrollIntoView({ behavior: 'smooth', block: 'start' })).catch(showError); });
  els.pagePrev.addEventListener('click', () => { if (state.page > 1) { state.page -= 1; refreshRecords().catch(showError); } });
  els.pageNext.addEventListener('click', () => { if (state.page < state.totalPages) { state.page += 1; refreshRecords().catch(showError); } });
  els.tableBody.addEventListener('click', async event => {
    const button = event.target.closest('[data-trace-id]'); if (!button) return;
    try { openDetail(await apiGet(`/api/monitor/records/${encodeURIComponent(button.dataset.traceId)}`)); } catch (error) { showError(error); }
  });
  els.drawerClose.addEventListener('click', closeDetail); els.backdrop.addEventListener('click', closeDetail); document.addEventListener('keydown', event => { if (event.key === 'Escape') closeDetail(); });
  els.dateRangeTrigger.addEventListener('click', () => { if (els.dateRangePopover.hidden) openRangePicker(); else closeRangePicker(); });
  els.rangeCancel.addEventListener('click', closeRangePicker); els.rangeApply.addEventListener('click', applyCustomRange);
  document.addEventListener('click', event => { if (!els.dateRangePopover.hidden && !els.dateRangePicker.contains(event.target)) closeRangePicker(); });
  document.querySelectorAll('[data-period]').forEach(button => button.addEventListener('click', () => { state.activePeriod = button.dataset.period; state.page = 1; setQuickPeriodState(state.activePeriod); refreshAll(); }));

  const initialBounds = boundsForPeriod('7d');
  els.dateRangeValue.textContent = formatRangeLabel(initialBounds.start, initialBounds.end);
  refreshStatus().catch(showError);
  refreshAll();
})();
