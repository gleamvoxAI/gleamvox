# Словари (play-2, 2026-10-17)

Файлы лежат вне git в `~/.local/ai-keyboard-tools/dictionaries/`; контрольные суммы SHA-256 — `docs/DICTIONARIES.sha256`.
Сборки personal и play проверяют их и падают при несовпадении (одни и те же файлы, отдельного флага personal нет).

| Файл | Куда в APK | Источник | Лицензия |
|---|---|---|---|
| main_ru__experimental.dict | assets/dicts/main_ru.dict | codeberg.org/Helium314/aosp-dictionaries, wordlists/main_ru__experimental.source | списки слов Leipzig Corpora Collection (Wortschatz) — CC BY 4.0 |
| main_en_us__experimental.dict | assets/dicts/main_en-US.dict | то же, wordlists/main_en_US__experimental.source | CC BY 4.0 (Wortschatz) |
| emoji_ru.dict, emoji_en.dict | assets/dicts/emoji_*.dict | emoji_cldr_signal_wordlists/emoji_cldr_signal.source | Unicode License v3 (CLDR) + AGPL-3.0 (Signal emoji-search-index) |

Вывод: распространять в Play вместе с GPL-3.0 приложением можно. CC BY 4.0 → GPL-3.0 допускается, условие — указать
авторство (сделано в NOTICE.md и «Настройки → О приложении → Словари»). AGPL-3.0 и GPL-3.0 по §13 можно объединять; это данные,
исходники приложения публикуются. Риск: Google не проверяет лицензии словарей, но владельцу нужно держать ссылку на исходники
и этот файл. Не юридическая консультация.
