const API_BASE = 'https://api.dbay.cloud:8443/api/v1'
const AUTH_URL = 'https://dbay.cloud/ext-login'

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
  // Use chrome.tabs approach instead of chrome.identity.launchWebAuthFlow
  // More reliable for unpacked/dev-mode extensions
  const callbackUrl = chrome.runtime.getURL('callback.html')
  const authUrl = `${AUTH_URL}?redirect_uri=${encodeURIComponent(callbackUrl)}`

  return new Promise((resolve) => {
    chrome.tabs.create({ url: authUrl }, (tab) => {
      const tabId = tab.id

      function onUpdated(updatedTabId, changeInfo) {
        if (updatedTabId !== tabId || !changeInfo.url) return
        const url = changeInfo.url

        // Check if the tab navigated to our callback URL
        if (url.startsWith(callbackUrl)) {
          chrome.tabs.onUpdated.removeListener(onUpdated)
          chrome.tabs.onRemoved.removeListener(onRemoved)

          // Extract key from hash
          try {
            const hash = new URL(url).hash
            const params = new URLSearchParams(hash.startsWith('#') ? hash.slice(1) : hash)
            const key = params.get('key')

            if (key) {
              chrome.storage.local.set({ apiKey: key }, () => {
                chrome.tabs.remove(tabId)
                resolve({ ok: true })
              })
            } else {
              chrome.tabs.remove(tabId)
              resolve({ ok: false, error: '未获取到 API Key' })
            }
          } catch (err) {
            chrome.tabs.remove(tabId)
            resolve({ ok: false, error: '解析认证结果失败' })
          }
        }
      }

      function onRemoved(removedTabId) {
        if (removedTabId !== tabId) return
        chrome.tabs.onUpdated.removeListener(onUpdated)
        chrome.tabs.onRemoved.removeListener(onRemoved)
        resolve({ ok: false, error: '登录窗口已关闭' })
      }

      chrome.tabs.onUpdated.addListener(onUpdated)
      chrome.tabs.onRemoved.addListener(onRemoved)
    })
  })
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
