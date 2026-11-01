package dev.filebe

/** Bundled trusted document; never loads or interpolates remote HTML. */
object AssistantPage {
    val html = """
        <!doctype html>
        <html lang="ru"><head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
        <title>Filebe • ИИ</title>
        <link rel="stylesheet" href="/assets/assistant.css">
        <script src="/assets/assistant.js" defer></script>
        </head><body><main>
        <header><span class="badge">FILEBE INTELLIGENCE</span><h1>От идеи к коду.</h1><p>Спросите об ошибке, разберите проект или предложите правку открытого файла.</p></header>
        <section id="messages" aria-live="polite" aria-label="Сообщения"></section>
        <form id="form">
        <label class="share"><input id="include-file" type="checkbox"> Приложить текст и путь открытого файла</label>
        <label for="prompt">Ваш запрос</label>
        <textarea id="prompt" rows="4" maxlength="16000" placeholder="Объясни код и найди потенциальные ошибки…" required></textarea>
        <button id="send" type="submit">Спросить Filebe ↗</button>
        <p class="note">Каждый запрос самостоятельный: история чата не передаётся. ИИ может ошибаться. Проверяйте изменения.</p>
        </form></main></body></html>
    """.trimIndent()
}
