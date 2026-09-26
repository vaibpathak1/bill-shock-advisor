// Bill Shock Advisor demo chat page (actions.md §8.1). No framework, no external scripts.
// Every server string is inserted with textContent, never as HTML (untrusted descriptions, security.md §5).
// Credentials stay in memory only (A-117); X-Requested-With avoids the browser's Basic-auth pop-up.
'use strict';

const state = { user: null, password: null, conversationId: null, busy: false };

const $ = (id) => document.getElementById(id);

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}

function headers(extra) {
  return Object.assign({
    'Authorization': 'Basic ' + btoa(state.user + ':' + state.password),
    'X-Requested-With': 'XMLHttpRequest'
  }, extra || {});
}

function newKey() {
  return (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + '-' + Math.random().toString(16).slice(2))
    .replace(/[^A-Za-z0-9_-]/g, '');
}

// ---------------------------------------------------------------- banner

async function loadMeta() {
  try {
    const res = await fetch('/api/v1/meta', { headers: { 'X-Requested-With': 'XMLHttpRequest' } });
    if (!res.ok) return;
    const meta = await res.json();
    if (meta.demoMode && meta.banner) {
      $('banner').textContent = meta.banner;
      $('banner').hidden = false;
    }
  } catch (e) {
    // The banner is informational; the page works without it.
  }
}

// ---------------------------------------------------------------- sign in

async function signIn(event) {
  event.preventDefault();
  state.user = $('user').value;
  state.password = $('password').value;
  $('login-error').hidden = true;
  const res = await fetch('/api/v1/actions?size=1', { headers: headers() });
  if (res.status === 401) {
    $('login-error').textContent = 'Wrong password for this demo customer.';
    $('login-error').hidden = false;
    state.password = null;
    return;
  }
  $('password').value = '';
  $('login').hidden = true;
  $('chat').hidden = false;
  $('who').textContent = 'Signed in as ' + state.user;
  newChat();
  $('message').focus();
}

function signOut() {
  state.user = null;
  state.password = null;
  state.conversationId = null;
  $('messages').replaceChildren();
  $('action-list').replaceChildren();
  $('my-actions').hidden = true;
  $('chat').hidden = true;
  $('login').hidden = false;
  $('who').textContent = '';
}

function newChat() {
  state.conversationId = null;
  $('messages').replaceChildren();
  $('message').value = 'Why is my bill so high?';
}

// ---------------------------------------------------------------- chat over SSE

async function send(event) {
  event.preventDefault();
  const text = $('message').value.trim();
  if (!text || state.busy) return;
  state.busy = true;
  $('send').disabled = true;
  $('message').value = '';
  $('messages').append(el('div', 'msg me', text));
  const answer = el('div', 'msg');
  let answerShown = false;
  const showAnswer = () => {
    if (!answerShown) {
      $('messages').append(answer);
      answerShown = true;
    }
  };

  const body = { message: text };
  if (state.conversationId) body.conversationId = state.conversationId;
  try {
    const res = await fetch('/api/v1/chat', {
      method: 'POST',
      headers: headers({ 'Content-Type': 'application/json', 'Accept': 'text/event-stream' }),
      body: JSON.stringify(body)
    });
    if (!res.ok) {
      const problem = await res.json().catch(() => ({}));
      $('messages').append(el('div', 'msg fallback', problem.detail || 'The request failed (' + res.status + ').'));
      return;
    }
    await readEvents(res, (name, data) => {
      switch (name) {
        case 'summary':
          state.conversationId = data.conversationId;
          $('messages').append(el('div', 'msg summary', data.text));
          break;
        case 'progress':
          $('progress').textContent = data.text + '…';
          $('progress').hidden = false;
          break;
        case 'token':
          showAnswer();
          answer.textContent += data.text;
          break;
        case 'reset':
          answer.textContent = '';
          break;
        case 'fallback':
          showAnswer();
          answer.className = 'msg fallback';
          answer.textContent = data.text;
          answer.append(el('span', 'note', 'Answer from the standard explanation (' + data.reason + ').'));
          break;
        case 'action':
          $('messages').append(actionCard(data));
          break;
        case 'error':
          $('messages').append(el('div', 'msg fallback', 'Something went wrong (' + data.code + '). Please try again.'));
          break;
        case 'done':
          state.conversationId = data.conversationId;
          break;
        default:
          break;
      }
    });
  } catch (e) {
    $('messages').append(el('div', 'msg fallback', 'The connection was interrupted. Please try again.'));
  } finally {
    $('progress').hidden = true;
    state.busy = false;
    $('send').disabled = false;
    $('message').focus();
  }
}

/** Reads a text/event-stream response body and calls onEvent(name, parsedData) per event. */
async function readEvents(res, onEvent) {
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
    let cut;
    while ((cut = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, cut);
      buffer = buffer.slice(cut + 2);
      let name = 'message';
      const data = [];
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) name = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5));
      }
      if (data.length) {
        try {
          onEvent(name, JSON.parse(data.join('\n')));
        } catch (e) {
          // Ignore a malformed event; the stream continues.
        }
      }
    }
  }
}

// ---------------------------------------------------------------- proposed actions

/** A card for one action; Confirm/Reject only while it waits for the customer. */
function actionCard(action) {
  const card = el('div', 'action');
  card.dataset.actionId = action.actionId;
  render(card, action);
  return card;
}

function render(card, a) {
  card.replaceChildren();
  card.append(el('div', 'status ' + a.status, a.status.replace(/_/g, ' ')));
  card.append(el('div', 'title', a.summary));
  const amount = typeof a.amount === 'string' ? a.amount : (a.amount && a.amount.display);
  if (amount) card.append(el('div', 'amount', amount));
  if (a.message) card.append(el('div', 'message', a.message));
  if (a.reference) card.append(el('div', 'message', 'Reference: ' + a.reference));
  if (a.status === 'PENDING_CONFIRMATION') {
    const buttons = el('div', 'buttons');
    const confirm = el('button', '', 'Confirm');
    const reject = el('button', 'secondary', 'Reject');
    // One key per card and button, reused on retry: a double click or a retry is a replay, never a second effect.
    confirm.dataset.key = card.dataset.confirmKey || (card.dataset.confirmKey = newKey());
    reject.dataset.key = card.dataset.rejectKey || (card.dataset.rejectKey = newKey());
    confirm.addEventListener('click', () => decide(card, 'confirm', confirm.dataset.key, [confirm, reject]));
    reject.addEventListener('click', () => decide(card, 'reject', reject.dataset.key, [confirm, reject]));
    buttons.append(confirm, reject);
    card.append(buttons);
  }
}

async function decide(card, verb, key, buttons) {
  buttons.forEach((b) => { b.disabled = true; });
  try {
    const res = await fetch('/api/v1/actions/' + card.dataset.actionId + '/' + verb, {
      method: 'POST',
      headers: headers({ 'Idempotency-Key': key })
    });
    const body = await res.json();
    if (body.actionId) {
      render(card, body);
    } else if (body.action) {
      render(card, body.action); // e.g. ACTION_NO_LONGER_VALID carries the updated action
    } else {
      card.append(el('div', 'error', body.detail || 'The request failed (' + res.status + ').'));
      buttons.forEach((b) => { b.disabled = false; });
    }
  } catch (e) {
    card.append(el('div', 'error', 'The connection was interrupted. Try again: it is safe to click again.'));
    buttons.forEach((b) => { b.disabled = false; });
  }
}

async function loadActions() {
  const res = await fetch('/api/v1/actions?size=20', { headers: headers() });
  const list = $('action-list');
  list.replaceChildren();
  if (!res.ok) {
    list.append(el('p', 'error', 'Could not load your requests.'));
  } else {
    const page = await res.json();
    if (!page.items.length) list.append(el('p', 'message', 'No requests yet.'));
    page.items.forEach((a) => list.append(actionCard(a)));
  }
  $('my-actions').hidden = false;
}

document.addEventListener('DOMContentLoaded', () => {
  loadMeta();
  $('login-form').addEventListener('submit', signIn);
  $('chat-form').addEventListener('submit', send);
  $('new-chat').addEventListener('click', newChat);
  $('refresh-actions').addEventListener('click', loadActions);
  $('logout').addEventListener('click', signOut);
});
