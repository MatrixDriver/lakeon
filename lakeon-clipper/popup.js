// Element references
const loginView = document.getElementById('login-view')
const mainView = document.getElementById('main-view')
const footer = document.getElementById('footer')
const loginBtn = document.getElementById('login-btn')
const logoutBtn = document.getElementById('logout-btn')
const saveBtn = document.getElementById('save-btn')
const currentUrlEl = document.getElementById('current-url')
const kbSelect = document.getElementById('kb-select')
const statusEl = document.getElementById('status')

// State
let currentApiKey = null
let currentTabUrl = ''

// Init
document.addEventListener('DOMContentLoaded', init)

async function init() {
  // Get current tab URL
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true })
  currentTabUrl = tabs[0]?.url || ''
  currentUrlEl.textContent = currentTabUrl

  // Check auth
  const { apiKey } = await sendMessage({ type: 'getApiKey' })
  if (apiKey) {
    currentApiKey = apiKey
    showMainView()
    await loadKbList()
  } else {
    showLoginView()
  }
}

// Login flow
loginBtn.addEventListener('click', async () => {
  loginBtn.disabled = true
  loginBtn.textContent = '登录中...'
  try {
    const result = await sendMessage({ type: 'login' })
    if (result.ok) {
      const { apiKey } = await sendMessage({ type: 'getApiKey' })
      currentApiKey = apiKey
      showMainView()
      await loadKbList()
    } else {
      showStatus('error', result.error || '登录失败，请重试')
    }
  } catch (err) {
    showStatus('error', err.message || '登录失败，请重试')
  } finally {
    loginBtn.disabled = false
    loginBtn.textContent = '登录 DBay'
  }
})

// Logout flow
logoutBtn.addEventListener('click', async () => {
  await sendMessage({ type: 'logout' })
  currentApiKey = null
  currentTabUrl = ''
  showLoginView()
})

// KB select change — persist selection
kbSelect.addEventListener('change', () => {
  const kbId = kbSelect.value
  if (kbId) {
    chrome.storage.local.set({ lastKbId: kbId })
  }
})

// Save URL flow
saveBtn.addEventListener('click', async () => {
  const kbId = kbSelect.value
  if (!kbId) {
    showStatus('error', '请先选择知识库')
    return
  }

  saveBtn.disabled = true
  showStatus('loading', '正在保存...')

  try {
    const result = await sendMessage({
      type: 'saveUrl',
      apiKey: currentApiKey,
      kbId,
      url: currentTabUrl,
    })

    if (result.ok) {
      // Remember KB selection
      chrome.storage.local.set({ lastKbId: kbId })
      showStatus('success', '已成功保存到知识库')
    } else {
      if (result.error === 'unauthorized') {
        await sendMessage({ type: 'logout' })
        currentApiKey = null
        showLoginView()
        showStatus('error', '登录已过期，请重新登录')
      } else {
        showStatus('error', result.error || '保存失败，请重试')
      }
    }
  } catch (err) {
    showStatus('error', err.message || '保存失败，请重试')
  } finally {
    saveBtn.disabled = false
  }
})

async function loadKbList() {
  kbSelect.disabled = true
  saveBtn.disabled = true
  kbSelect.innerHTML = '<option value="">加载中...</option>'

  const result = await sendMessage({ type: 'fetchKbList', apiKey: currentApiKey })

  if (!result.ok) {
    if (result.error === 'unauthorized') {
      await sendMessage({ type: 'logout' })
      currentApiKey = null
      showLoginView()
      showStatus('error', '登录已过期，请重新登录')
      return
    }
    kbSelect.innerHTML = '<option value="">加载失败</option>'
    showStatus('error', result.error || '无法加载知识库列表')
    return
  }

  const kbs = result.kbs || []
  if (kbs.length === 0) {
    kbSelect.innerHTML = '<option value="">暂无可用知识库</option>'
    showStatus('error', '没有找到状态为 READY 的文档知识库')
    return
  }

  kbSelect.innerHTML = kbs
    .map(kb => `<option value="${escapeHtml(String(kb.id))}">${escapeHtml(kb.name)}</option>`)
    .join('')

  // Restore last selected KB
  const { lastKbId } = await chrome.storage.local.get('lastKbId')
  if (lastKbId) {
    const option = kbSelect.querySelector(`option[value="${escapeHtml(String(lastKbId))}"]`)
    if (option) {
      kbSelect.value = lastKbId
    }
  }

  kbSelect.disabled = false
  saveBtn.disabled = false
  hideStatus()
}

// Helper: show status
function showStatus(type, text) {
  statusEl.className = `status ${type}`
  statusEl.textContent = text
}

// Helper: hide status
function hideStatus() {
  statusEl.className = ''
  statusEl.textContent = ''
}

// Helper: send message to background
function sendMessage(msg) {
  return new Promise((resolve, reject) => {
    chrome.runtime.sendMessage(msg, (response) => {
      if (chrome.runtime.lastError) {
        reject(new Error(chrome.runtime.lastError.message))
      } else {
        resolve(response)
      }
    })
  })
}

// Helper: show login view
function showLoginView() {
  loginView.classList.remove('hidden')
  mainView.classList.add('hidden')
  footer.classList.add('hidden')
  hideStatus()
}

// Helper: show main view
function showMainView() {
  loginView.classList.add('hidden')
  mainView.classList.remove('hidden')
  footer.classList.remove('hidden')
  hideStatus()
}

// Helper: escape HTML for option values/text
function escapeHtml(str) {
  return str
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}
