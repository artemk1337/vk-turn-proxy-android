const response = await fetch('./strings.json');
if (!response.ok) throw new Error('Could not load demo translations.');
const strings = await response.json();
const $ = (id) => document.getElementById(id);
const language = new URLSearchParams(location.search).get('lang') === 'en' ? 'en' : 'ru';
const preferences = { language, theme: language === 'en' ? 'light' : 'dark', autostart: false, autoUpdate: true, logLimit: 1000 };
const sample = '[Interface]\nPrivateKey = DEMO_PRIVATE_KEY\nAddress = 10.10.0.2/32\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = DEMO_PUBLIC_KEY\nAllowedIPs = 0.0.0.0/0\nEndpoint = vpn.example.com:51820';
const profiles = [{
  id: 'demo', name: language === 'ru' ? 'Личный профиль' : 'Personal profile',
  mode: 'wireguard', source: sample, vkLink: 'https://vk.com/call/demo', peer: '', transport: 'srtp',
  connections: 30, mtu: 1280, turnTransport: '', dns: '1.1.1.1', keepalive: 25,
  turnServer: '', uplink: 0, cooldown: 150, vkAuth: '', legacyCaptcha: '',
  routing: { mode: '', geoip: [], geosite: [], custom: '', localDirect: true },
}];
profiles.push({ ...structuredClone(profiles[0]), id: 'demo-work', name: language === 'ru' ? 'Рабочий профиль' : 'Work profile' });
let activeID = 'demo';
let connection = 'disconnected';
let connectionTimer;
let page = 'home';
let returnPage = 'home';
let editorID = '';
let draft;
let sourceDrafts;
let catalogType = 'geoip';
let catalogSelection = new Set();
let pendingImportSource = '';
const logs = ['[demo] Android interface ready. Connection activity is simulated.'];
const catalog = {
  geoip: ['RU', 'US', 'DE', 'NL', 'FI', 'GB', 'FR', 'JP'],
  geosite: ['YouTube', 'Telegram', 'Discord', 'GitHub', 'Google', 'Netflix', 'Spotify'],
};
const t = (key, ...values) => (strings[preferences.language][key] || key)
  .replace(/%(\d+)\$[sd]/g, (_, index) => values[Number(index) - 1] ?? '');
const localized = (ru, en) => preferences.language === 'ru' ? ru : en;

function translate() {
  document.documentElement.lang = preferences.language;
  document.documentElement.dataset.theme = preferences.theme;
  document.querySelectorAll('[data-i18n]').forEach((element) => {
    element.textContent = t(element.dataset.i18n);
  });
  document.querySelectorAll('[data-i18n-aria]').forEach((element) => {
    const text = t(element.dataset.i18nAria);
    element.setAttribute('aria-label', text);
    element.title = text;
  });
  document.querySelector('label[for="log-limit"]').textContent = t('log_limit', 100, 10000);
  $('demo-banner').textContent = localized(
    'Демонстрация Android · Подключение и данные смоделированы',
    'Android demo · Connection and data are simulated',
  );
}

function renderHome() {
  const active = connection !== 'disconnected';
  $('connection-state').textContent = t(connection);
  $('traffic').hidden = connection !== 'connected';
  $('traffic').textContent = t('traffic_summary', '12.4 MB', '2.1 MB', 30);
  const action = t(active ? 'disconnect' : 'connect');
  $('connect').setAttribute('aria-label', action);
  $('connect').dataset.state = connection;
  $('connect').disabled = connection === 'stopping' || (!profiles.length && !active);
  $('selected-profile-name').textContent = profiles.find((profile) => profile.id === activeID)?.name || t('no_profiles');
  $('profile-select').hidden = !profiles.length;
  for (const id of ['import-source', 'paste-import', 'continue-import', 'scan-qr', 'import-from-file']) $(id).disabled = active;
  $('home-add-profile').hidden = profiles.length > 0;
  renderProfiles();
}

function renderProfiles() {
  const list = $('profile-list');
  const active = connection !== 'disconnected';
  list.replaceChildren();
  for (const profile of profiles) {
    const row = document.createElement('div');
    row.className = 'profile-sheet-row';
    const choose = document.createElement('button');
    choose.type = 'button';
    choose.className = 'profile-choice';
    choose.disabled = active;
    choose.setAttribute('role', 'radio');
    choose.setAttribute('aria-checked', String(profile.id === activeID));
    choose.tabIndex = profile.id === activeID ? 0 : -1;
    const marker = document.createElement('span');
    marker.className = 'profile-marker';
    marker.setAttribute('aria-hidden', 'true');
    const text = document.createElement('span');
    const name = document.createElement('strong');
    name.textContent = profile.name;
    const detail = document.createElement('small');
    detail.textContent = `${profile.mode === 'wireguard' ? 'WireGuard' : t('mode_link')} · MTU ${profile.mtu}`;
    text.append(name, detail);
    choose.append(marker, text);
    choose.addEventListener('click', () => {
      activeID = profile.id;
      $('profile-picker').close();
      renderHome();
      $('profile-select').focus({ preventScroll: true });
    });
    const edit = document.createElement('button');
    edit.type = 'button';
    edit.className = 'icon-button profile-settings';
    edit.setAttribute('aria-label', `${t('profile_settings')} · ${profile.name}`);
    edit.title = t('profile_settings');
    edit.disabled = active;
    edit.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10.781,2.075 L13.219,2.075 L13.935,4.238 L16.120,5.143 L18.157,4.120 L19.880,5.843 L18.857,7.880 L19.762,10.065 L21.925,10.781 L21.925,13.219 L19.762,13.935 L18.857,16.120 L19.880,18.157 L18.157,19.880 L16.120,18.857 L13.935,19.762 L13.219,21.925 L10.781,21.925 L10.065,19.762 L7.880,18.857 L5.843,19.880 L4.120,18.157 L5.143,16.120 L4.238,13.935 L2.075,13.219 L2.075,10.781 L4.238,10.065 L5.143,7.880 L4.120,5.843 L5.843,4.120 L7.880,5.143 L10.065,4.238 Z"/><circle cx="12" cy="12" r="3"/></svg>';
    edit.addEventListener('click', () => {
      $('profile-picker').close();
      openEditor(profile);
    });
    row.append(choose, edit);
    list.append(row);
  }
}

function showPage(next) {
  page = next;
  for (const name of ['home', 'settings', 'add']) {
    $(name === 'home' ? 'home' : `${name}-page`).hidden = name !== page;
  }
  document.querySelectorAll('[data-page]').forEach((button) => {
    if (button.dataset.page === page) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });
}

function fillSettings() {
  $('language').value = preferences.language;
  $('theme').value = preferences.theme;
  $('autostart').checked = preferences.autostart;
  $('auto-update').checked = preferences.autoUpdate;
  $('log-limit').value = preferences.logLimit;
}

function log(message) {
  logs.push(`[${new Date().toLocaleTimeString('en-GB')}] [demo] ${message}`);
  if (logs.length > preferences.logLimit) logs.splice(0, logs.length - preferences.logLimit);
}

function message(text) {
  $('message-text').textContent = text;
  $('message-dialog').showModal();
}

function demoOnly() {
  message(localized(
    'Вход в VK, cookies и установка обновлений доступны в установленном приложении. Данные демо хранятся только в этой вкладке.',
    'VK sign-in, cookies and update installation are available in the installed app. Demo data lives only in this tab.',
  ));
}

$('connect').addEventListener('click', () => {
  clearTimeout(connectionTimer);
  if (connection === 'disconnected') {
    connection = 'connecting';
    log('Connecting to simulated TURN relay.');
    connectionTimer = setTimeout(() => {
      connection = 'connected';
      log('Simulated tunnel connected.');
      renderHome();
    }, 2000);
  } else {
    connection = 'stopping';
    log('Stopping simulated tunnel.');
    connectionTimer = setTimeout(() => {
      connection = 'disconnected';
      log('Simulated tunnel disconnected.');
      renderHome();
    }, 400);
  }
  renderHome();
});
$('profile-select').addEventListener('click', () => {
  renderProfiles();
  $('profile-picker').showModal();
  $('profile-list').querySelector('[aria-checked="true"]')?.focus();
});
$('profile-list').addEventListener('keydown', (event) => {
  if (!['ArrowDown', 'ArrowRight', 'ArrowUp', 'ArrowLeft'].includes(event.key)) return;
  const choices = [...$('profile-list').querySelectorAll('[role="radio"]:not(:disabled)')];
  const index = choices.indexOf(document.activeElement);
  if (index < 0) return;
  event.preventDefault();
  const step = ['ArrowDown', 'ArrowRight'].includes(event.key) ? 1 : -1;
  choices[(index + step + choices.length) % choices.length].click();
});
$('profile-picker').addEventListener('click', (event) => {
  if (event.target !== $('profile-picker')) return;
  const bounds = $('profile-picker').getBoundingClientRect();
  if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) $('profile-picker').close();
});
document.querySelectorAll('[data-page]').forEach((button) => button.addEventListener('click', () => showPage(button.dataset.page)));

const fields = {
  'profile-name': 'name', 'vk-link': 'vkLink', peer: 'peer', transport: 'transport',
  connections: 'connections', mtu: 'mtu', 'turn-transport': 'turnTransport', dns: 'dns', keepalive: 'keepalive',
  'turn-server': 'turnServer', uplink: 'uplink', cooldown: 'cooldown', 'vk-auth': 'vkAuth', 'legacy-captcha': 'legacyCaptcha',
};

function openEditor(profile, source = "") {
  editorID = profile?.id || '';
  draft = structuredClone(profile || {
    name: '', mode: /^\s*\[Interface\]/i.test(source) ? 'wireguard' : 'link', source, vkLink: '', peer: '', transport: 'srtp',
    connections: 30, mtu: 1280, turnTransport: '', dns: '', keepalive: 25,
    turnServer: '', uplink: 0, cooldown: 150, vkAuth: '', legacyCaptcha: '',
    routing: { mode: 'profile', geoip: [], geosite: [], custom: '', localDirect: true },
  });
  sourceDrafts = { link: '', wireguard: '' };
  sourceDrafts[draft.mode] = draft.source;
  $('profile-form').reset();
  $('source-mode').value = draft.mode;
  $('profile-source').value = draft.source;
  for (const [id, key] of Object.entries(fields)) $(id).value = draft[key];
  $('routing-mode').value = draft.routing.mode;
  $('routing-custom').value = draft.routing.custom;
  $('local-direct').checked = draft.routing.localDirect;
  $('editor-title').textContent = t(editorID ? 'edit_profile' : 'add_profile');
  $('delete-profile').hidden = !editorID;
  returnPage = page;
  document.querySelectorAll('.main-screen').forEach((screen) => { screen.hidden = true; });
  $('bottom-navigation').hidden = true;
  $('editor').hidden = false;
  $('editor').scrollTop = 0;
  document.querySelectorAll('#editor details').forEach((details) => { details.open = false; });
  renderSource();
  renderRouting();
  document.querySelector(`[data-mode="${draft.mode}"]`).focus({ preventScroll: true });
}

function closeEditor() {
  $('editor').hidden = true;
  $('bottom-navigation').hidden = false;
  showPage(returnPage);
  $(returnPage === 'add' ? 'import-source' : (profiles.length ? 'profile-select' : 'home-add-profile')).focus({ preventScroll: true });
}

function renderSource() {
  $('wg-fields').hidden = draft.mode !== 'wireguard';
  $('vk-link').required = draft.mode === 'wireguard';
  document.querySelectorAll('[data-mode]').forEach((button) => {
    button.setAttribute('aria-pressed', String(button.dataset.mode === draft.mode));
  });
  const label = document.querySelector('label[for="profile-source"]');
  label.textContent = t(draft.mode === 'wireguard' ? 'wg_config' : 'link_json_label');
  $('profile-source').placeholder = t(draft.mode === 'wireguard' ? 'wg_config_hint' : 'link_json_hint');
}

$('source-mode').addEventListener('change', () => {
  sourceDrafts[draft.mode] = $('profile-source').value;
  draft.mode = $('source-mode').value;
  $('profile-source').value = sourceDrafts[draft.mode];
  renderSource();
});
document.querySelectorAll('[data-mode]').forEach((button) => button.addEventListener('click', () => {
  $('source-mode').value = button.dataset.mode;
  $('source-mode').dispatchEvent(new Event('change'));
}));
$('paste-source').addEventListener('click', async () => {
  try {
    const text = await navigator.clipboard.readText();
    if (new TextEncoder().encode(text).length > 4 * 1024 * 1024) throw new Error('File too large');
    $('profile-source').value = text;
  } catch (_) {
    message(localized('Вставьте текст в поле профиля вручную.', 'Paste the text into the profile field.'));
  }
});
$('open-source').addEventListener('click', () => $('source-file').click());
async function readSourceFile(input, accept) {
  const file = input.files[0];
  if (!file) return;
  try {
    if (file.size > 4 * 1024 * 1024) throw new Error('File too large');
    accept(await file.text());
  } catch (_) {
    message(localized('Не удалось прочитать файл. Допустимый размер до 4 МиБ.', 'Could not read the file. The limit is 4 MiB.'));
  } finally {
    input.value = '';
  }
}
$('source-file').addEventListener('change', () => readSourceFile($('source-file'), (source) => { $('profile-source').value = source; }));
$('import-file').addEventListener('change', () => readSourceFile($('import-file'), (source) => openImportedSource(source)));
$('home-add-profile').addEventListener('click', () => showPage('add'));
$('delete-profile').addEventListener('click', () => {
  if (!editorID || !confirm(t('delete_title'))) return;
  profiles.splice(profiles.findIndex((profile) => profile.id === editorID), 1);
  if (activeID === editorID) activeID = profiles[0]?.id || '';
  renderHome();
  closeEditor();
});

function openImportedSource(source) {
  if (!source.trim()) return message(t('paste_empty'));
  if (new TextEncoder().encode(source).length > 4 * 1024 * 1024) {
    return message(localized('Размер профиля превышает 4 МиБ.', 'The profile exceeds 4 MiB.'));
  }
  openEditor(undefined, source);
}
$('import-source').placeholder = t('import_source');
$('import-source').addEventListener('input', () => { pendingImportSource = $('import-source').value; $('continue-import').hidden = !pendingImportSource.trim(); });
$('import-form').addEventListener('submit', (event) => {
  event.preventDefault();
  openImportedSource(pendingImportSource || $('import-source').value);
});
$('paste-import').addEventListener('click', async () => {
  try {
    const source = await navigator.clipboard.readText();
    if (new TextEncoder().encode(source).length > 4 * 1024 * 1024) throw new Error('Profile too large');
    pendingImportSource = source;
    $('import-source').value = source;
    $('continue-import').hidden = !source.trim();
    $('import-source').focus();
  } catch (_) {
    message(localized('Вставьте ссылку или конфигурацию в строку вручную.', 'Paste the link or configuration into the field.'));
  }
});
$('import-from-file').addEventListener('click', () => $('import-file').click());

let qrStream;
let qrFrame;
let qrRun = 0;
function stopQr() {
  qrRun++;
  cancelAnimationFrame(qrFrame);
  qrStream?.getTracks().forEach((track) => track.stop());
  qrStream = undefined;
  $('qr-video').srcObject = null;
}
$('qr-dialog').addEventListener('close', stopQr);
$('scan-qr').addEventListener('click', async () => {
  if (!('BarcodeDetector' in window) || !navigator.mediaDevices?.getUserMedia) {
    return message(localized('Этот браузер не поддерживает сканирование QR. Откройте установленное приложение или импортируйте ссылку либо файл.', 'This browser cannot scan QR codes. Use the installed app, or import a link or file.'));
  }
  const run = ++qrRun;
  try {
    const detector = new BarcodeDetector({ formats: ['qr_code'] });
    $('qr-status').textContent = localized('Наведите камеру на QR-код профиля.', 'Point the camera at the profile QR code.');
    $('qr-dialog').showModal();
    const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } }, audio: false });
    if (run !== qrRun || !$('qr-dialog').open) { stream.getTracks().forEach((track) => track.stop()); return; }
    qrStream = stream;
    $('qr-video').srcObject = stream;
    await $('qr-video').play();
    const scan = async () => {
      if (run !== qrRun || !$('qr-dialog').open) return;
      try {
        if ($('qr-video').readyState >= 2) {
          const codes = await detector.detect($('qr-video'));
          if (run !== qrRun || !$('qr-dialog').open) return;
          const source = codes.find((code) => code.rawValue)?.rawValue;
          if (source) { $('qr-dialog').close(); openImportedSource(source); return; }
        }
      } catch (_) {
        $('qr-status').textContent = localized('QR-код пока не распознан. Держите его в кадре.', 'QR code not recognized yet. Keep it in view.');
      }
      qrFrame = requestAnimationFrame(scan);
    };
    qrFrame = requestAnimationFrame(scan);
  } catch (_) {
    if (run === qrRun) {
      if ($('qr-dialog').open) $('qr-dialog').close();
      else stopQr();
      message(localized('Не удалось открыть камеру. Разрешите доступ к камере или импортируйте ссылку либо файл.', 'Could not open the camera. Allow camera access, or import a link or file.'));
    }
  }
});
document.addEventListener('visibilitychange', () => { if (document.hidden && $('qr-dialog').open) $('qr-dialog').close(); });
document.querySelectorAll('[data-editor-cancel]').forEach((button) => button.addEventListener('click', closeEditor));
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && !$('editor').hidden && !document.querySelector('dialog[open]')) closeEditor();
});
$('profile-form').addEventListener('submit', (event) => {
  event.preventDefault();
  if (!$('profile-source').value.trim()) return message(t('paste_empty'));
  for (const [id, key] of Object.entries(fields)) draft[key] = $(id).value;
  draft.connections = Number(draft.connections);
  draft.mtu = Number(draft.mtu);
  draft.name = draft.name.trim() || localized('Новый профиль', 'New profile');
  draft.source = $('profile-source').value;
  draft.routing.mode = $('routing-mode').value;
  draft.routing.custom = $('routing-custom').value;
  draft.routing.localDirect = $('local-direct').checked;
  draft.id = editorID || crypto.randomUUID();
  const index = profiles.findIndex((profile) => profile.id === draft.id);
  if (index < 0) profiles.push(draft);
  else profiles[index] = draft;
  activeID = draft.id;
  log('Demo profile saved for this tab.');
  renderHome();
  closeEditor();
});

function renderRouting() {
  const mode = $('routing-mode').value;
  for (const id of ['geoip', 'geosite', 'routing-custom']) $(id).disabled = !['include', 'exclude'].includes(mode);
  $('local-direct').disabled = mode === 'profile';
  for (const type of ['geoip', 'geosite']) $(type).textContent = `${t(type)} · ${t('selected_count', draft.routing[type].length)}`;
}
$('routing-mode').addEventListener('change', renderRouting);

function renderCatalog() {
  const query = $('catalog-search').value.trim().toLocaleLowerCase();
  const list = $('catalog-list');
  list.replaceChildren();
  for (const id of catalog[catalogType]) {
    const name = catalogType === 'geoip'
      ? new Intl.DisplayNames([preferences.language], { type: 'region' }).of(id) : id;
    if (!`${id} ${name}`.toLocaleLowerCase().includes(query)) continue;
    const label = document.createElement('label');
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = catalogSelection.has(id);
    checkbox.addEventListener('change', () => {
      if (checkbox.checked) catalogSelection.add(id);
      else catalogSelection.delete(id);
    });
    label.append(checkbox, document.createTextNode(name));
    list.append(label);
  }
}
for (const type of ['geoip', 'geosite']) $(type).addEventListener('click', () => {
  catalogType = type;
  catalogSelection = new Set(draft.routing[type]);
  $('catalog-title').textContent = t(type);
  $('catalog-search').value = '';
  $('catalog-search').placeholder = t('search');
  renderCatalog();
  $('catalog-dialog').showModal();
});
$('catalog-search').addEventListener('input', renderCatalog);
$('clear-catalog').addEventListener('click', () => {
  catalogSelection.clear();
  renderCatalog();
});
$('catalog-form').addEventListener('submit', (event) => {
  event.preventDefault();
  draft.routing[catalogType] = [...catalogSelection];
  $('catalog-dialog').close();
  renderRouting();
});
$('update-catalogs').addEventListener('click', () => message(`${t('catalog_updated')} · ${localized('демо', 'demo')}`));

document.querySelectorAll('[data-open]').forEach((button) => button.addEventListener('click', () => {
  const name = button.dataset.open;
  if (name === 'logs') $('log-lines').textContent = logs.join('\n');
  $(`${name}-dialog`).showModal();
}));
$('settings-form').addEventListener('submit', (event) => {
  event.preventDefault();
  Object.assign(preferences, {
    language: $('language').value, theme: $('theme').value, autostart: $('autostart').checked,
    autoUpdate: $('auto-update').checked, logLimit: Number($('log-limit').value),
  });
  translate();
  $('import-source').placeholder = t('import_source');
  renderHome();
});
document.querySelectorAll('[data-close]').forEach((button) => button.addEventListener('click', () => button.closest('dialog').close()));
document.querySelectorAll('[data-demo-only]').forEach((button) => button.addEventListener('click', demoOnly));
$('app-updates').addEventListener('click', demoOnly);
$('copy-logs').addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText(logs.join('\n'));
  } catch (_) {
    message(localized('Выделите и скопируйте текст журнала.', 'Select and copy the log text.'));
  }
});

fillSettings();
translate();
renderHome();
