# Словари (play-2, 2026-10-17)

Файлы лежат вне git в `~/.local/ai-keyboard-tools/dictionaries/`; контрольные суммы SHA-256 — `docs/DICTIONARIES.sha256`.
Сборки personal и play проверяют их и падают при несовпадении (одни и те же файлы, отдельного флага personal нет).

| Файл | Куда в APK | Источник | Лицензия |
|---|---|---|---|
| main_ru__experimental.dict | assets/dicts/main_ru.dict | codeberg.org/Helium314/aosp-dictionaries, wordlists/main_ru__experimental.source | списки слов Leipzig Corpora Collection (Wortschatz) — CC BY 4.0 |
| main_en_us__experimental.dict | assets/dicts/main_en-US.dict | то же, wordlists/main_en_US__experimental.source | CC BY 4.0 (Wortschatz) |
| main_uk__experimental.dict (12,7 МБ) | assets/dicts/main_uk.dict | aosp-dictionaries, wordlists/main_uk__experimental.source | CC BY 4.0 (Wortschatz) |
| main_kk__experimental.dict (11,3 МБ) | assets/dicts/main_kk.dict | то же, main_kk__experimental | CC BY 4.0 (Wortschatz) |
| main_ar__experimental.dict (4,9 МБ) | assets/dicts/main_ar.dict | то же, main_ar__experimental | CC BY 4.0 (Wortschatz) |
| main_id__experimental.dict (1,7 МБ) | assets/dicts/main_in.dict | то же, main_id__experimental | CC BY 4.0 (Wortschatz) |
| main_cs__experimental.dict (16,5 МБ) | assets/dicts/main_cs.dict | то же, main_cs__experimental | CC BY 4.0 (Wortschatz) |
| main_sk__experimental.dict (4,5 МБ) | assets/dicts/main_sk.dict | то же, main_sk__experimental | CC BY 4.0 (FrequencyWords, Hermit Dave) |
| main_hr__experimental.dict (5,8 МБ) | assets/dicts/main_hr.dict | то же, main_hr__experimental | CC BY 4.0 (Wortschatz) |
| main_fi__experimental.dict (2,8 МБ) | assets/dicts/main_fi.dict | то же, main_fi__experimental | CC BY 4.0 (Wortschatz) |
| main_da__experimental.dict (9,4 МБ) | assets/dicts/main_da.dict | то же, main_da__experimental | CC BY 4.0 (Wortschatz) |
| main_nb__experimental.dict (7,6 МБ) | assets/dicts/main_nb.dict | то же, main_nb__experimental | CC BY 4.0 (Wortschatz) |
| main_he__experimental.dict (4,8 МБ) | assets/dicts/main_iw.dict | то же, main_he__experimental | CC BY-SA 4.0 (Wortschatz + FrequencyWords) |
| main_fa_ir__experimental.dict (12,7 МБ) | assets/dicts/main_fa.dict | то же, main_fa_IR__experimental | CC BY 4.0 (Wortschatz) |
| main_az.dict (0,3 МБ) | assets/dicts/main_az.dict | то же, main_az (github.com/subhangadirli/aosp-dict-az) | CC BY-SA 4.0 + Leipzig Corpora |
| emoji_{uk,kk,ar,id,cs,sk,hr,fi,da,nb,he,fa,az}.dict | assets/dicts/emoji_<язык>.dict (id → in, he → iw) | emoji_cldr_signal_dictionaries | Unicode License v3 (CLDR) + AGPL-3.0 (Signal) |
| emoji_ru.dict, emoji_en.dict | assets/dicts/emoji_*.dict | emoji_cldr_signal_wordlists/emoji_cldr_signal.source | Unicode License v3 (CLDR) + AGPL-3.0 (Signal emoji-search-index) |

Вывод: распространять в Play вместе с GPL-3.0 приложением можно. CC BY 4.0 → GPL-3.0 допускается, условие — указать
авторство (сделано в NOTICE.md и «Настройки → О приложении → Словари»). AGPL-3.0 и GPL-3.0 по §13 можно объединять; это данные,
исходники приложения публикуются. Риск: Google не проверяет лицензии словарей, но владельцу нужно держать ссылку на исходники
и этот файл. Не юридическая консультация.

## Lang-1 (2026-10-20): ещё 13 языков
Добавлены украинский, казахский, арабский, индонезийский, чешский, словацкий, хорватский, финский, датский, норвежский (букмол),
иврит, персидский, азербайджанский (основной словарь + эмодзи). APK вырос примерно на 32 МБ (словари в APK сжаты; на телефоне
словарь распаковывается только для включённого языка).
Не взяты: узбекский, малайский (нет словаря), хинди (только словарь под GPLv2 без «или новее» — несовместим с GPL-3.0),
сербский, грузинский, армянский (лицензия источника не указана), вьетнамский (словарь на 11 755 слов, нет набора Telex),
словацкий `main_sk` (CC BY-NC 4.0, запрет коммерческого использования; взят `main_sk__experimental`).
Проверка «3 частых слова»: `python3 -I tools/aikb/dict_check.py` (по исходным спискам слов, см. STATUS.md).
