const API_BASE = 'https://api.dbay.cloud:8443/api/v1'
const AUTH_URL = 'https://console.dbay.cloud/ext-login'

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.type === 'login') {
    handleLogin()
      .then(result => sendResponse(result))
      .catch(err => sendResponse({ ok: false, error: err.message }))
    return true
  }

  if (msg.type === 'logout') {
    chrome.storage.local.remove(['apiKey', 'lastKbId'], () => {
      sendResponse({ ok: true })
    })
    return true
  }

  if (msg.type === 'getApiKey') {
    chrome.storage.local.get('apiKey', (data) => {
      sendResponse({ apiKey: data.apiKey || null })
    })
    return true
  }

  if (msg.type === 'fetchKbList') {
    fetchKbList(msg.apiKey)
      .then(result => sendResponse(result))
      .catch(err => sendResponse({ ok: false, error: err.message }))
    return true
  }

  if (msg.type === 'saveUrl') {
    saveUrl(msg.apiKey, msg.kbId, msg.url)
      .then(result => sendResponse(result))
      .catch(err => sendResponse({ ok: false, error: err.message }))
    return true
  }
})

async function handleLogin() {
  const redirectUri = chrome.identity.getRedirectURL()
  const authUrl = `${AUTH_URL}?redirect_uri=${encodeURIComponent(redirectUri)}`

  let responseUrl
  try {
    responseUrl = await chrome.identity.launchWebAuthFlow({
      url: authUrl,
      interactive: true,
    })
  } catch (err) {
    return { ok: false, error: err.message || 'Auth flow cancelled' }
  }

  if (!responseUrl) {
    return { ok: false, error: 'No response URL from auth flow' }
  }

  let key
  try {
    const hash = new URL(responseUrl).hash
    const params = new URLSearchParams(hash.startsWith('#') ? hash.slice(1) : hash)
    key = params.get('key')
  } catch (err) {
    return { ok: false, error: 'Failed to parse auth response URL' }
  }

  if (!key) {
    return { ok: false, error: 'No API key found in auth response' }
  }

  await chrome.storage.local.set({ apiKey: key })
  return { ok: true }
}

async function fetchKbList(apiKey) {
  let response
  try {
    response = await fetch(`${API_BASE}/knowledge/bases`, {
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
    })
  } catch (err) {
    return { ok: false, error: err.message }
  }

  if (response.status === 401) {
    return { ok: false, error: 'unauthorized' }
  }

  if (!response.ok) {
    return { ok: false, error: `Server error: ${response.status}` }
  }

  let data
  try {
    data = await response.json()
  } catch (err) {
    return { ok: false, error: 'Failed to parse response' }
  }

  const items = Array.isArray(data) ? data : (data.data || data.items || [])
  const kbs = items
    .filter(kb => kb.type === 'DOCUMENT' && kb.status === 'READY')
    .map(kb => ({ id: kb.id, name: kb.name }))

  return { ok: true, kbs }
}

async function saveUrl(apiKey, kbId, url) {
  let response
  try {
    response = await fetch(`${API_BASE}/knowledge/wiki/ingest-url`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ kb_id: kbId, url }),
    })
  } catch (err) {
    return { ok: false, error: err.message }
  }

  if (!response.ok) {
    let errMsg = `Server error: ${response.status}`
    try {
      const errData = await response.json()
      if (errData.message) errMsg = errData.message
    } catch (_) {}
    return { ok: false, error: errMsg }
  }

  let data
  try {
    data = await response.json()
  } catch (_) {
    data = null
  }

  return { ok: true, data }
}
