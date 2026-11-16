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
let activeID = 'demo';
let connection = 'disconnected';
let connectionTimer;
let editorID = '';
let draft;
let sourceDrafts;
let catalogType = 'geoip';
let catalogSelection = new Set();
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
  $('connect').textContent = t(active ? 'disconnect' : 'connect');
  $('connect').hidden = !profiles.length && !active;
  $('connect').disabled = connection === 'stopping';
  $('profile-select').replaceChildren(...profiles.map((profile) => new Option(profile.name, profile.id)));
  if (!profiles.length) $('profile-select').add(new Option(t('no_profiles'), ''));
  $('profile-select').value = activeID;
  $('profile-select').disabled = active || !profiles.length;
  for (const id of ['edit-profile', 'delete-profile']) {
    $(id).hidden = !profiles.length;
    $(id).disabled = active;
  }
  $('add-profile').disabled = active;
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
$('profile-select').addEventListener('change', () => { activeID = $('profile-select').value; });

const fields = {
  'profile-name': 'name', 'vk-link': 'vkLink', peer: 'peer', transport: 'transport',
  connections: 'connections', mtu: 'mtu', 'turn-transport': 'turnTransport', dns: 'dns', keepalive: 'keepalive',
  'turn-server': 'turnServer', uplink: 'uplink', cooldown: 'cooldown', 'vk-auth': 'vkAuth', 'legacy-captcha': 'legacyCaptcha',
};

function openEditor(profile) {
  editorID = profile?.id || '';
  draft = structuredClone(profile || {
    name: '', mode: 'link', source: '', vkLink: '', peer: '', transport: 'srtp',
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
  $('home').hidden = true;
  $('editor').hidden = false;
  $('editor').scrollTop = 0;
  document.querySelectorAll('#editor details').forEach((details) => { details.open = false; });
  renderSource();
  renderRouting();
  document.querySelector(`[data-mode="${draft.mode}"]`).focus({ preventScroll: true });
}

function closeEditor() {
  $('editor').hidden = true;
  $('home').hidden = false;
  $('edit-profile').focus({ preventScroll: true });
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
$('source-file').addEventListener('change', async () => {
  const file = $('source-file').files[0];
  if (!file) return;
  if (file.size > 4 * 1024 * 1024) {
    message(localized('Размер файла превышает 4 МиБ.', 'The file exceeds 4 MiB.'));
  } else {
    $('profile-source').value = await file.text();
  }
  $('source-file').value = '';
});
$('add-profile').addEventListener('click', () => openEditor());
$('edit-profile').addEventListener('click', () => openEditor(profiles.find((profile) => profile.id === activeID)));
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
$('delete-profile').addEventListener('click', () => {
  if (!confirm(t('delete_title'))) return;
  profiles.splice(profiles.findIndex((profile) => profile.id === activeID), 1);
  activeID = profiles[0]?.id || '';
  renderHome();
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
  if (name === 'settings') {
    $('language').value = preferences.language;
    $('theme').value = preferences.theme;
    $('autostart').checked = preferences.autostart;
    $('auto-update').checked = preferences.autoUpdate;
    $('log-limit').value = preferences.logLimit;
  }
  if (name === 'logs') $('log-lines').textContent = logs.join('\n');
  $(`${name}-dialog`).showModal();
}));
$('settings-form').addEventListener('submit', (event) => {
  event.preventDefault();
  Object.assign(preferences, {
    language: $('language').value, theme: $('theme').value, autostart: $('autostart').checked,
    autoUpdate: $('auto-update').checked, logLimit: Number($('log-limit').value),
  });
  $('settings-dialog').close();
  translate();
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

translate();
renderHome();
