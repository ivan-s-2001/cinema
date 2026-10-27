const $ = id => document.getElementById(id);
let offset = 0, selectedId = null, original = null, creating = false, lastQuery = '';
const flash = (message, error = false) => {
  $('global-message').textContent = message;
  $('global-message').classList.toggle('error', error);
};
async function api(path, options = {}) {
  const response = await fetch('/api/admin/' + path, {
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-CinemaHall-Admin': '1' },
    ...options
  });
  const data = await response.json().catch(() => ({}));
  if (response.status === 401 && path !== 'login') {
    $('app').hidden = true;
    $('login-screen').hidden = false;
    $('logout').hidden = true;
  }
  if (!response.ok) throw Error(data.error || 'Ошибка сервера (' + response.status + ')');
  return data;
}
function showApp() {
  $('login-screen').hidden = true;
  $('app').hidden = false;
  $('logout').hidden = false;
  refreshAll();
}
async function refreshAll() {
  await Promise.allSettled([loadTitles(), loadStats(), loadSchedule(), loadJob()]);
}
$('login-form').addEventListener('submit', async event => {
  event.preventDefault();
  $('login-error').textContent = '';
  try {
    await api('login', { method: 'POST', body: JSON.stringify({ password: $('password').value }) });
    $('password').value = '';
    showApp();
  } catch (err) {
    $('login-error').textContent = err.message;
    $('login-error').classList.add('error');
  }
});
$('logout').addEventListener('click', async () => {
  await api('logout', { method: 'POST', body: '{}' }).catch(() => {});
  $('app').hidden = true;
  $('login-screen').hidden = false;
  $('logout').hidden = true;
});
document.querySelectorAll('.tab').forEach(button => {
  button.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach(x => {
      const active = x === button;
      x.classList.toggle('selected', active);
      x.setAttribute('aria-pressed', String(active));
    });
    const sync = button.dataset.tab === 'sync';
    $('catalog-section').hidden = sync;
    $('sync-section').hidden = !sync;
    if (sync) loadJob();
  });
});
$('refresh').addEventListener('click', refreshAll);
async function loadStats() {
  try {
    const data = await api('stats');
    const counts = Object.fromEntries(data.counts.map(x => [x.kind, Number(x.total)]));
    $('movies-count').textContent = (counts.movie || 0).toLocaleString('ru-RU');
    $('series-count').textContent = (counts.series || 0).toLocaleString('ru-RU');
  } catch (err) { flash(err.message, true); }
}
async function loadTitles() {
  const query = $('query').value.trim();
  if (query !== lastQuery) { offset = 0; lastQuery = query; }
  $('title-list').textContent = 'Загрузка…';
  try {
    const data = await api('titles?q=' + encodeURIComponent(query) + '&offset=' + offset);
    const list = $('title-list');
    list.replaceChildren();
    if (!data.items.length) {
      list.textContent = 'Пока ничего не найдено. Начните импорт или добавьте запись вручную.';
    }
    for (const item of data.items) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'record' + (selectedId === item.id ? ' active' : '');
      const url = safeImage(item.poster);
      if (url) {
        const image = document.createElement('img');
        image.src = url; image.alt = ''; image.loading = 'lazy';
        button.append(image);
      } else {
        const fallback = document.createElement('span');
        fallback.className = 'record-placeholder'; fallback.textContent = 'CH';
        button.append(fallback);
      }
      const text = document.createElement('span'); text.className = 'record-text';
      const heading = document.createElement('div'); heading.className = 'record-title'; heading.textContent = item.title;
      const caption = document.createElement('div'); caption.className = 'record-meta';
      caption.textContent = (item.kind === 'series' ? 'Сериал' : 'Фильм') + (item.year ? ' · ' + item.year : '');
      text.append(heading, caption);
      const arrow = document.createElement('span'); arrow.className = 'record-arrow'; arrow.setAttribute('aria-hidden', 'true'); arrow.textContent = '›';
      button.append(text, arrow);
      button.addEventListener('click', () => selectTitle(item.id));
      list.append(button);
    }
    $('page-label').textContent = 'Страница ' + (Math.floor(offset / 40) + 1);
    $('previous').disabled = offset === 0;
    $('next').disabled = data.items.length < 40;
  } catch (err) { $('title-list').textContent = err.message; }
}
let debounce;
$('query').addEventListener('input', () => { clearTimeout(debounce); debounce = setTimeout(loadTitles, 240); });
$('previous').addEventListener('click', () => { offset = Math.max(0, offset - 40); loadTitles(); });
$('next').addEventListener('click', () => { offset += 40; loadTitles(); });
function safeImage(value) {
  try { const url = new URL(value); return url.protocol === 'https:' ? url.href : ''; } catch { return ''; }
}
function preview(id, url) {
  const el = $(id); el.replaceChildren();
  const safe = safeImage(url);
  if (safe) {
    const img = document.createElement('img');
    img.src = safe; img.alt = ''; img.loading = 'lazy';
    img.style.cssText = 'height:100%;width:100%;object-fit:contain';
    img.onerror = () => { el.textContent = 'Изображение недоступно'; };
    el.append(img);
  } else el.textContent = 'Нет изображения';
}
$('poster').addEventListener('input', () => preview('poster-preview', $('poster').value));
$('backdrop').addEventListener('input', () => preview('backdrop-preview', $('backdrop').value));
function formValues() {
  let ratings;
  try { ratings = JSON.parse($('ratings').value || '{}'); } catch { throw Error('Проверьте JSON с рейтингами'); }
  if (!ratings || typeof ratings !== 'object' || Array.isArray(ratings)) throw Error('Рейтинги должны быть JSON-объектом');
  return {
    title: $('title').value.trim(), kind: $('kind').value,
    year: $('year').value ? Number($('year').value) : null,
    description: $('description').value,
    poster: $('poster').value.trim(), backdrop: $('backdrop').value.trim(),
    aliases: $('aliases').value.split('\n').map(x => x.trim()).filter(Boolean),
    ratings
  };
}
function showEditor(record) {
  $('editor-empty').hidden = true;
  $('editor').hidden = false;
  $('save-message').textContent = '';
  $('editor-type').textContent = creating ? 'НОВАЯ ЗАПИСЬ' : 'РЕДАКТОР МЕТАДАННЫХ';
  $('editor-heading').textContent = creating ? 'Новый тайтл' : record.title;
  $('editor-id').textContent = creating ? 'Новая запись · внутренний ID создаст сервер' : record.id;
  $('title').value = record.title || '';
  $('kind').value = record.kind || 'movie';
  $('kind').disabled = !creating;
  $('year').value = record.year || '';
  $('description').value = record.description || '';
  $('poster').value = record.poster || '';
  $('backdrop').value = record.backdrop || '';
  $('aliases').value = (record.payload?.aliases || []).join('\n');
  $('ratings').value = JSON.stringify(record.payload?.ratings || {}, null, 2);
  preview('poster-preview', record.poster);
  preview('backdrop-preview', record.backdrop);
  original = formValues();
}
async function selectTitle(id) {
  creating = false; selectedId = id;
  try {
    const data = await api('titles/' + encodeURIComponent(id));
    showEditor(data.title);
    document.querySelectorAll('.record').forEach(button => button.classList.remove('active'));
    if (window.innerWidth < 1000) $('editor').scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (err) { flash(err.message, true); }
}
$('create').addEventListener('click', () => {
  creating = true; selectedId = null;
  document.querySelector('[data-tab="catalog"]').click();
  showEditor({ title: '', kind: 'movie', payload: { ratings: {}, aliases: [] } });
  $('title').focus();
});
$('editor').addEventListener('submit', async event => {
  event.preventDefault();
  const message = $('save-message');
  message.classList.remove('error');
  try {
    const values = formValues();
    if (!values.title) throw Error('Укажите название');
    let id;
    if (creating) {
      const result = await api('titles', { method: 'POST', body: JSON.stringify(values) });
      id = result.id; creating = false;
    } else {
      const changes = {};
      for (const key of ['title', 'year', 'description', 'poster', 'backdrop', 'aliases', 'ratings']) {
        if (JSON.stringify(values[key]) !== JSON.stringify(original[key])) changes[key] = values[key];
      }
      if (!Object.keys(changes).length) { message.textContent = 'Нет изменений'; return; }
      await api('titles/' + encodeURIComponent(selectedId), { method: 'PATCH', body: JSON.stringify(changes) });
      id = selectedId;
    }
    message.textContent = 'Сохранено';
    await Promise.all([loadTitles(), loadStats()]);
    await selectTitle(id);
  } catch (err) {
    message.textContent = err.message;
    message.classList.add('error');
  }
});
async function startJob(input) {
  try {
    await api('job', { method: 'POST', body: JSON.stringify(input) });
    flash('Задача запущена. Прогресс отображается ниже.');
    await loadJob();
  } catch (err) { flash(err.message, true); }
}
$('bootstrap').addEventListener('click', () => startJob({ mode: 'bootstrap', pages: Number($('bootstrap-pages').value) }));
$('sync-now').addEventListener('click', () => startJob({ mode: 'daily', pages: Number($('daily-pages').value) }));
$('import-one').addEventListener('click', () => startJob({ mode: 'one', source: $('source').value, id: $('source-id').value.trim() }));
async function loadJob() {
  try {
    const data = await api('job');
    const labels = { idle: 'Ожидание', running: 'Выполняется', success: 'Завершено', failed: 'Ошибка' };
    $('sync-state').textContent = labels[data.status] || data.status;
    $('sync-time').textContent = data.startedAt ? new Date(data.startedAt).toLocaleString('ru-RU') : 'Ещё не запускалось';
    $('job-status').textContent = (labels[data.status] || data.status) +
      (data.mode ? ' · ' + data.mode : '') +
      (data.finishedAt ? ' · ' + new Date(data.finishedAt).toLocaleString('ru-RU') : '');
    $('job-logs').textContent = data.logs || 'Ожидание запуска…';
    const isBusy = data.status === 'running';
    for (const id of ['bootstrap', 'sync-now', 'import-one']) $(id).disabled = isBusy;
    if (data.status === 'success') loadStats();
  } catch (err) { $('job-status').textContent = err.message; }
}
async function loadSchedule() {
  try {
    const data = await api('schedule');
    $('schedule-enabled').checked = data.enabled;
    $('schedule-time').value = String(data.hour).padStart(2, '0') + ':' + String(data.minute).padStart(2, '0');
    $('schedule-pages').value = data.pages;
  } catch (err) { flash(err.message, true); }
}
$('save-schedule').addEventListener('click', async () => {
  const [hour, minute] = $('schedule-time').value.split(':').map(Number);
  try {
    await api('schedule', { method: 'PATCH', body: JSON.stringify({
      enabled: $('schedule-enabled').checked, hour, minute, pages: Number($('schedule-pages').value)
    }) });
    flash('Расписание сохранено. Время — московское.');
  } catch (err) { flash(err.message, true); }
});
setInterval(() => {
  if (!$('app').hidden) loadJob();
}, 6000);
api('session').then(data => { if (data.authenticated) showApp(); }).catch(() => {});
