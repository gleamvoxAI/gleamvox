# NOTICE: сторонние компоненты и лицензии

AI Keyboard построена на основе HeliBoard. Этот файл перечисляет сторонние компоненты. Тексты лицензий лежат в каталоге проекта (`LICENSE`, `LICENSE-Apache-2.0` и заголовки файлов).

## Входит во все сборки (personal, release, магазинная play); словари из таблицы ниже — в personal и play

| Компонент | Лицензия | Примечание |
|---|---|---|
| HeliBoard (https://github.com/Helium314/HeliBoard) | GPL-3.0 | Основа проекта. Исходный код производной работы нужно давать всем, кому передаётся APK. Уведомления лицензии сохранены в файлах. |
| Android Open Source Project (AOSP LatinIME) | Apache 2.0 | Код клавиатуры, от которого произошёл HeliBoard. Заголовки файлов сохранены. |
| Встроенные словари HeliBoard (assets/dicts) | см. https://codeberg.org/Helium314/aosp-dictionaries | Словари проекта aosp-dictionaries; лицензии по языкам указаны в этом репозитории. |
| Тема «AI Keyboard» (цвета, формы) | GPL-3.0 (как весь проект) | Нарисована с нуля. Код и графика FUTO Keyboard не используются. |
| Детектор речи WebRTC VAD (common_audio/vad и нужные функции common_audio/signal_processing), перенесён на Java: `app/src/main/java/helium314/keyboard/latin/voiceapi/WebRtcVad.java` | BSD-3-Clause, © 2011 The WebRTC project authors (https://webrtc.org) | Этап 5г-6. Источник: копия в py-webrtcvad (https://github.com/wiseman/py-webrtcvad, обёртка под MIT, © 2016 John Wiseman), каталог cbits/webrtc. Текст лицензии BSD в заголовке файла. Совместима с GPL-3.0. Работает целиком на телефоне, без сети и без разрешений; перенос сверен с исходным C-кодом кадр за кадром на 8 сигналах во всех 4 режимах. |
| Русский экспериментальный словарь `main_ru__experimental` (assets/dicts/main_ru.dict) | Списки слов Leipzig Corpora Collection (Wortschatz, Universität Leipzig) под CC BY 4.0 | Play-2. Нужно указание авторства: «Источник текстов: Wortschatz, Universität Leipzig». CC BY 4.0 → GPL-3.0 допускается (односторонняя совместимость, заявлена Creative Commons). Источник: https://codeberg.org/Helium314/aosp-dictionaries (wordlists/main_ru__experimental.source). Входит в personal и в магазинную версию. |
| Английский (США) экспериментальный словарь `main_en_us__experimental` (assets/dicts/main_en-US.dict) | Списки слов Leipzig Corpora Collection (Wortschatz) под CC BY 4.0 | Play-2. Те же условия; источник проверен по wordlists/main_en_US__experimental.source (прежняя пометка «не проверено» снята). Входит в personal и в магазинную версию. |
| Словари lang-1 (assets/dicts/main_<язык>.dict): украинский, казахский, арабский, индонезийский (`in`), чешский, хорватский, финский, датский, норвежский (букмол), персидский — экспериментальные словари HeliBoard | Списки слов Leipzig Corpora Collection (Wortschatz, Universität Leipzig) под CC BY 4.0 | Lang-1. Те же условия, что у русского: указание авторства (NOTICE и «О приложении → Словари»). Источники проверены по wordlists/main_<язык>__experimental.source в https://codeberg.org/Helium314/aosp-dictionaries. Входят в personal и в магазинную версию. |
| Иврит `main_he__experimental` (assets/dicts/main_iw.dict) | Wortschatz (Leipzig) и FrequencyWords (Hermit Dave, github.com/hermitdave/FrequencyWords) под CC BY-SA 4.0 | Lang-1. CC BY-SA 4.0 → GPL-3.0 допускается (односторонняя совместимость, заявлена Creative Commons). |
| Словацкий `main_sk__experimental` (assets/dicts/main_sk.dict) | FrequencyWords (Hermit Dave), в источнике указано CC BY 4.0 (сам проект FrequencyWords — CC BY-SA 4.0) | Lang-1. Оба варианта совместимы с GPL-3.0. Словарь `main_sk` (без «experimental») НЕ взят: CC BY-NC 4.0 (запрет коммерческого использования). |
| Азербайджанский `main_az` (assets/dicts/main_az.dict) | aosp-dict-az (Subhan Gadirli, github.com/subhangadirli/aosp-dict-az), CC BY-SA 4.0, и Leipzig Corpora | Lang-1. Совместимо с GPL-3.0. |
| Словари эмодзи `emoji_ru`, `emoji_en` и (lang-1) `emoji_uk`, `kk`, `ar`, `in`, `cs`, `sk`, `hr`, `fi`, `da`, `nb`, `iw`, `fa`, `az` (emoji_cldr_signal) | Unicode License v3 (данные CLDR) и AGPL-3.0 (поисковый индекс эмодзи Signal, автор eranl) | Play-2. Данные, не код. GPL-3.0 §13 разрешает объединять работу с AGPL-3.0; исходники приложения публикуются (GPL-3.0), словари берутся из проекта aosp-dictionaries с теми же лицензиями. Источники: github.com/unicode-org/cldr-json, github.com/signalapp/emoji-search-index. Входят в personal и в магазинную версию. |

## Данные для настройки (не входят ни в одну сборку)
- Параметры открытого свайпа (`app/src/main/java/helium314/keyboard/latin/glide/GlideMatcher.kt`, `GlideParams`) подобраны на жестах набора FUTO Swipe dataset (swipe.futo.org, https://huggingface.co/datasets/futo-org/swipe.futo.org), Copyright (c) 2025 FUTO, лицензия MIT. Сам набор в проект и в сборки не входит; модели FUTO не используются.

## Только сборка personal (для телефона владельца, не распространять)

| Компонент | Лицензия | Примечание |
|---|---|---|
| Детектор речи Silero VAD, модель v6.2.3 `app/src/aikbPersonal/assets/silero_vad.onnx` | MIT, © 2020-present Silero Team (https://github.com/snakers4/silero-vad) | Этап 5г-19. Файл без изменений (SHA-256 1a153a22…8788e3). Совместима с GPL-3.0. Только в personal, где есть голос через OpenAI; работает на телефоне, без сети. |
| ONNX Runtime для Android 1.30.0 (`com.microsoft.onnxruntime:onnxruntime-android`, библиотеки для 4 ABI) | MIT, © Microsoft Corporation (https://github.com/microsoft/onnxruntime) | Этап 5г-19. Запускает модель Silero. Его провайдер телеметрии и разрешение ACCESS_NETWORK_STATE убраны из манифеста, телеметрия выключена и в коде (setTelemetry(false)). Совместима с GPL-3.0. Для тестов на компьютере — тот же выпуск для JVM (`onnxruntime`). |
| Запись речи для тестов `app/src/test/resources/voice/jfk.wav` (Дж. Ф. Кеннеди, 1961) | Общественное достояние (речь президента США); файл из whisper.cpp (MIT) | Этап 5г-19. Только для тестов, в APK не входит. |
| Библиотека жестового ввода Google `libjni_latinimegoogle.so` (arm64-v8a) | Проприетарная (Google) | Закрытая. В git не хранится. APK с ней не публиковать и никому не передавать. |

Контрольные суммы словарей: `docs/DICTIONARIES.sha256` (сборки personal и play проверяют их и падают при несовпадении).

## Правила
- Закрытые и сторонние файлы всегда лежат вне git: `/home/dev/.local/ai-keyboard-tools/`.
- Сборка release их не содержит. Сборка personal включается ключом `-PaikbPersonal=true`.
