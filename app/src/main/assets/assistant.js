'use strict';
const messages = document.getElementById('messages');
const form = document.getElementById('form');
const promptBox = document.getElementById('prompt');
const send = document.getElementById('send');
let pending = null;
function addMessage(role, text) {
  const bubble = document.createElement('article');
  bubble.className = 'message ' + role;
  const heading = document.createElement('strong');
  heading.textContent = role === 'user' ? 'ВЫ' : 'FILEBE';
  bubble.append(heading, document.createTextNode(text));
  messages.appendChild(bubble);
  bubble.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}
if (window.FilebeBridge) {
  window.FilebeBridge.onmessage = event => {
    try {
      const response = JSON.parse(event.data);
      if (response.id !== pending && response.id !== 'error') return;
      addMessage('assistant', String(response.message));
    } catch (error) {
      addMessage('assistant', 'Ошибка чтения ответа: ' + error.message);
    } finally {
      pending = null;
      send.disabled = false;
      send.textContent = 'Спросить Filebe ↗';
    }
  };
} else {
  addMessage('assistant', 'Безопасный мост недоступен. Обновите Android System WebView и перезапустите приложение.');
  send.disabled = true;
}
form.addEventListener('submit', event => {
  event.preventDefault();
  const text = promptBox.value.trim();
  if (!text || pending || !window.FilebeBridge) return;
  pending = String(Date.now()) + '-' + Math.random().toString(36).slice(2);
  const includeFile = document.getElementById('include-file').checked;
  addMessage('user', text + (includeFile ? '\n[с открытым файлом после подтверждения]' : ''));
  send.disabled = true;
  send.textContent = 'Ожидаем ответ…';
  try {
    window.FilebeBridge.postMessage(JSON.stringify({ type:'ask', id:pending, prompt:text, includeFile }));
    promptBox.value = '';
  } catch (error) {
    pending = null;
    send.disabled = false;
    send.textContent = 'Спросить Filebe ↗';
    addMessage('assistant', 'Не удалось отправить запрос: ' + error.message);
  }
});
