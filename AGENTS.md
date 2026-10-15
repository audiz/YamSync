# Workspace Rules & Agent Guidelines

Этот файл автоматически обнаруживается Antigravity / Gemini CLI при каждом запуске и сессии pair programming.
Все правила из директории `.agents/rules/` являются строго обязательными к исполнению.

---

## 1. Git Workflow ([.agents/rules/git-workflow.md](.agents/rules/git-workflow.md))
- **Обязательные локальные коммиты (`git commit`)**:
  - После успешного выполнения логически завершённой задачи, исправления бага, рефакторинга или реализации фичи **обязательно** фиксировать изменения в локальный Git.
  - Сообщения коммитов оформлять по стандарту Conventional Commits (`feat: ...`, `fix: ...`, `refactor: ...`, `style: ...`, `docs: ...`).
  - Чистота коммитов: не коммитить временные файлы, логи, артефакты сборки (`build/`, `.gradle/`).
- **Строгий запрет на `git push`**:
  - **Никогда не выполнять команду `git push`**.
  - Все коммиты должны оставаться строго локальными. Решение об отправке в удалённый репозиторий принимает исключительно пользователь вручную.

---

## 2. Adaptive & Compact UI Guidelines ([.agents/rules/ui-layout.md](.agents/rules/ui-layout.md))
- **Адаптивность (Desktop + Mobile)**:
  - Использовать `BoxWithConstraints` для динамической адаптации размеров и отступов под ширину экрана/окна.
  - Группы элементов оборачивать в `FlowRow` с автоматическим переносом, исключая обрезку за правым краем.
  - Для кликабельных элементов на Desktop указывать `.pointerHoverIcon(PointerIcon.Hand)`.
  - Избегать жестких фиксированных ширин экрана; использовать `weight(...)`, `fillMaxWidth()`, `widthIn`.
- **Компактность и визуальный стиль**:
  - Придерживаться существующего стиля (`HomeHub.kt`, `PlayerBar.kt`, `MobilePlayer.kt`).
  - Всегда ограничивать длинные строки `maxLines = 1` и `overflow = TextOverflow.Ellipsis`.

---

## 3. Audio Streaming & External Download ([.agents/rules/audio-download.md](.agents/rules/audio-download.md))
- **Range Requests & Chunked Streaming**:
  - Воспроизведение треков из внешних источников (API, CDN) должно выполняться через чанки/диапазоны байт (`Range: bytes=start-end`), включая `LocalStreamProxy`.
- **Быстрый старт и мгновенная перемотка (Seek-ahead)**:
  - Плеер не должен ждать полной загрузки аудиофайла перед стартом.
  - Перемотка на незагруженный участок должна мгновенно перенаправлять запрос на целевое смещение, прерывая или откладывая старый чанк.
- **Потоковая передача**:
  - Запрещено вычитывать аудиофайлы целиком в память (`readBytes()`). Использовать потоки фиксированными буферами (64–256 КБ).

---

## 4. Performance & Architecture ([.agents/rules/performance-and-architecture.md](.agents/rules/performance-and-architecture.md))
- **Защита от N+1**: батчинг сетевых запросов (чанками по 30–50) и разовое сканирование файловой структуры с кешированием в `Set<String>`.
- **Алгоритмическая сложность $O(1)$ / $O(N)$**: сопоставление через хеш-таблицы и множества вместо вложенных линейных поисков $O(N \times M)$.
- **Корутины и UI Main-Thread**:
  - Обязательно пробрасывать `CancellationException` в блоках `catch`.
  - Никакого блокирующего I/O или парсинга в UI/Main потоке — только через `withContext(DispatcherIO)`.
  - Запрещён busy-wait polling (`while { delay; check }`); использовать реактивные `StateFlow`, `SharedFlow`, `Channel`.
