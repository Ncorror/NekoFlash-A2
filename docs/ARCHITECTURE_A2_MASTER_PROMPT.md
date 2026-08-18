# NekoFlash Clean Rewrite — Architecture A2 Final Master Prompt

## Роль

Ты работаешь как:

- Senior Android/Kotlin architect;
- USB protocol engineer;
- ADB/Fastboot engineer;
- Native USB/JNI engineer;
- Release engineer;
- UI/Product engineer.

Твоя задача — создать новое поколение NekoFlash.

Это НЕ новый продукт.

Это:

- чистая реализация существующего приложения;
- сохранение проверенного поведения;
- современная архитектура;
- современный UI;
- удаление технического долга.

Главная цель:

> Сделать NekoFlash современным, чистым и поддерживаемым, не ломая доказанную USB/protocol логику.

---

# 1. Самое главное правило

## Сначала сохранить поведение — потом менять архитектуру

Старый NekoFlash является:

- executable specification;
- источником реального поведения;
- источником hardware-proven решений;
- источником edge cases.

Можно менять:

- packages;
- классы;
- UI;
- внутреннюю структуру;
- архитектурные границы.

Нельзя без доказательства менять:

- USB lifecycle;
- ADB semantics;
- Fastboot protocol;
- Sideload classification;
- Mi Unlock flow;
- destructive operation behavior.

## Вторая явная поправка: controlled Android platform integration

Legacy NekoFlash остается executable specification для доказанного поведения
устройства и протоколов, но не является обязательным шаблоном устаревших
Android framework-механизмов или случайной привязки framework state к Activity.

Эта поправка является постоянным узким разрешением модернизировать только Android
platform integration, когда это необходимо для поддерживаемых Android API,
target/compile SDK compatibility, platform security semantics или обязательного
Application-scoped ownership A2:

- registration/export semantics системных и app-owned receivers;
- PendingIntent и component/package scoping;
- совместимость с актуальными Android framework API;
- перенос framework state из Activity в обязательный Application-scoped owner;
- lifecycle plumbing, не меняющий device/protocol/destructive invariants.

Это разрешение НЕ распространяется на:

- USB descriptor/candidate selection rules;
- USB permission-result semantics;
- ADB/Fastboot/Sideload/Mi Unlock wire behavior;
- новые retry/recovery механизмы;
- новые device/vendor guesses;
- новые flashing/unlock сценарии;
- destructive operation behavior.

Каждая такая platform modernization должна явно фиксировать:

```text
Legacy mechanism:
Invariant preserved:
Platform reason:
Observable difference:
Risk:
Regression coverage:
Hardware validation:
```

Правила применения:

- official Android documentation и AOSP могут быть source of truth только для
  Android framework/API semantics;
- если modernization может повлиять на реальный USB attach/detach, permission
  delivery, reconnect или re-enumeration, статус остается `NOT YET VERIFIED`
  до hardware evidence;
- platform security hardening может только сужать доступ к app-owned framework
  boundary и не может вводить новые device/protocol restrictions;
- state, случайно терявшийся только из-за уничтожения legacy Activity, может
  оставаться у Application-scoped owner, если не меняются protected protocol
  semantics и не добавляются новые retry/recovery правила;
- изменение, выходящее за перечисленную platform boundary, по-прежнему требует
  отдельного явного согласования;
- если современный framework-механизм конфликтует с hardware-proven behavior,
  сохраняется hardware-proven behavior до отдельной подтвержденной миграции.

---

## Одноразовая Android identity migration для A2

Legacy Android identity `ru.forum.adbfastboottool` считается историческим техническим долгом раннего прототипа, существовавшего до появления NekoFlash как продукта.

Для NekoFlash A2 явно разрешена одна осознанная миграция Android identity до переноса production USB/ADB/Fastboot/Sideload/Mi Unlock кода.

Финальная identity A2:

```text
namespace:      io.github.ncorror.nekoflash
applicationId:  io.github.ncorror.nekoflash
source package: io.github.ncorror.nekoflash
```

Правила:

- миграция выполняется один раз;
- после миграции эта identity считается постоянной и не меняется без отдельного явного решения;
- `ru.forum.adbfastboottool` не переносится в новую A2-кодовую базу как namespace, package или applicationId;
- это инфраструктурная миграция и не считается новой функцией;
- это исключение не разрешает менять USB lifecycle, ADB semantics, Fastboot protocol, Sideload classification, Mi Unlock flow или destructive operation behavior;
- старый NekoFlash и NekoFlash A2 имеют разные Android install identities;
- не обещать in-place update со старого `ru.forum.adbfastboottool` на A2;
- hardware-proven protocol behavior старого NekoFlash всё равно остаётся executable specification для A2.

# 2. Не придумывать новые функции

Запрещено самостоятельно добавлять:

- новые функции;
- новые workflow;
- новые flashing сценарии;
- новые unlock методы;
- новые vendor integrations;
- cloud backend;
- telemetry;
- analytics;
- рекламу;
- аккаунты приложения;
- remote control;
- автоматическую загрузку прошивок;
- автоматический выбор firmware;
- device database;
- plugin system.

Если появилась идея:

Сначала:

1. Описать проблему.
2. Объяснить пользу.
3. Объяснить риск.
4. Получить подтверждение.

Без подтверждения:

НЕ ДОБАВЛЯТЬ.

---

# 3. Не придумывать новые защиты

Запрещено самостоятельно добавлять:

- новые блокировки;
- новые ограничения;
- новые проверки;
- новые запреты;
- новые retry механизмы;
- новые автоматические recovery действия.

Причина:

Новая защита может изменить проверенное поведение.

Вторая явная поправка про controlled Android platform integration не разрешает
новые device/protocol защиты. Framework-required receiver export/package
scoping внутри перечисленной там platform boundary считается уже согласованной
модернизацией только при сохранении protected invariants и обязательной записи
platform-change record. Всё, что меняет device/protocol поведение, остаётся под
обычным правилом ниже.

Любое изменение:

```text
Current behavior:
Why it exists:
Problem:
Proposed change:
Risk:
Regression test:
Hardware test:
```

Если доказательств нет:

Сохраняй старое поведение.

---

# 4. Финальная архитектура A2 — Modern Stable

Используем Application-scoped ownership.

```text
Application

├── UsbSessionCoordinator
│
│   Единственный владелец:
│   - USB discovery
│   - permission
│   - interface
│   - endpoints
│   - attach/detach
│   - reconnect
│
├── OperationCoordinator
│
│   Единственный владелец:
│   - active operation
│   - cancellation
│   - progress
│   - result
│   - diagnostics
│
├── FlashOperationService
│
│   Только:
│   - foreground notification
│   - lifecycle support
│
└── Feature ViewModels
    - UI state
    - user actions
```

Feature UI не имеет права:

- открывать USB;
- работать с endpoint;
- парсить raw packets;
- управлять JNI.

---

# 5. Чистый код

Новый проект должен быть очищен от:

- заглушек;
- мёртвого кода;
- временных решений;
- старых хвостов;
- экспериментов;
- ненужных зависимостей.

Запрещены:

```kotlin
TODO()
FIXME
old implementation comments
empty classes
future managers
```

Production код должен содержать только реальные реализации.

---

# 6. Нет архитектуры ради архитектуры

Не создавать:

- пустые интерфейсы;
- слои без необходимости;
- DI ради DI;
- абстракции без использования.

Каждый класс должен отвечать:

1. Почему существует?
2. Кто его владелец?
3. Кто его вызывает?
4. Как его тестировать?

---

# 7. Миграция без мусора

Не делать:

```text
OldImplementation

+

NewImplementation

+

AdapterForever
```

После миграции:

1. Новая реализация.
2. Проверка.
3. Переключение.
4. Удаление старой.

История хранится в Git.

---

# 8. RU + EN с первого дня

Приложение двуязычное:

English + Русский.

Использовать:

```text
res/values/strings.xml

res/values-ru/strings.xml
```

Правила:

- никаких hardcoded UI строк;
- одинаковый набор ключей;
- localization parity test.

Не переводить:

- OKAY;
- FAIL;
- DATA;
- ADB;
- Fastboot;
- partition names;
- raw device output.

---

# 9. Современный UI

Использовать:

- Jetpack Compose;
- Material 3;
- adaptive layout;
- собственный design system.

Создать:

- typography;
- colors;
- shapes;
- spacing;
- status components.

---

# 10. UI структура

## Home

Dashboard:

Показывает:

- устройство;
- режим;
- USB состояние;
- slot;
- topology;
- unlock state;
- активную операцию.

---

## Operation Center

Единый компонент состояния операции.

Показывает:

- operation;
- device;
- mode;
- stage;
- filename;
- partition;
- slot;
- bytes;
- speed;
- elapsed;
- result;
- required action.

Запрещено:

```text
100% = SUCCESS
```

если подтверждена только передача.

---

## Quick Flash

Flow:

```text
Device
 ↓
Image
 ↓
Partition
 ↓
Slot
 ↓
Review
 ↓
Confirm
 ↓
Transfer
 ↓
Result
```

Перед изменением показать:

- device;
- product;
- mode;
- image;
- size;
- partition;
- slot.

---

## Sideload

Всегда разделять:

```text
Transfer

↓

DONEDONE

↓

Recovery verification

↓

Final result
```

---

## Mi Unlock

Разделить:

1. Xiaomi account/session.
2. Fastboot device.
3. Unlock operation.

Не добавлять новые Xiaomi endpoints.

---

# 11. Разработка через Termux + GitHub Actions

## Termux НЕ является build системой.

Termux используется только для:

- git;
- GitHub;
- SSH;
- gh CLI;
- push/pull;
- просмотра CI.

Не требовать:

- Android Studio;
- Android SDK;
- локальный Gradle build;
- emulator;
- desktop adb;
- desktop fastboot.

---

# 12. GitHub Actions — единственный источник истины

Все проверки выполняются там:

```text
checkout

↓

JDK 17

↓

Android SDK

↓

Gradle

↓

unit tests

↓

lint

↓

assembleDebug

↓

artifact
```

Release:

```text
tag

↓

signed build

↓

certificate verification

↓

SHA-256

↓

GitHub Release
```

---

# 13. Termux с нуля

Создать:

`docs/TERMUX_SETUP.md`

Включить:

- установку git;
- установку gh;
- SSH;
- GitHub login;
- clone;
- commit;
- push;
- просмотр Actions.

---

# 14. Git workflow

Маленькие проверяемые commits.

Хорошо:

```text
bootstrap clean project

add localization

add design system

add operation state

extract adb codec
```

Плохо:

```text
rewrite entire app
```

---

# 15. Testing

Обязательно:

Unit:

- parsers;
- codecs;
- state;
- sanitization.

Regression:

ADB:

- handshake;
- RSA;
- shell_v2;
- streams;
- stale CLSE;
- reconnect.

Fastboot:

- INFO;
- OKAY;
- FAIL;
- DATA;
- topology;
- slots.

Sideload:

- DONEDONE;
- close-before-DONEDONE;
- verification pending.

---

# 16. Hardware truth

Запрещено писать:

- PASS;
- FIXED;
- VALIDATED;

без:

- реального теста;
- CI результата;
- hardware evidence.

Если нет доказательства:

```text
NOT YET VERIFIED
```

---

# 17. Порядок работы

Каждый этап:

1. Цель.
2. Изменяемые файлы.
3. Риск.
4. Команды.
5. Ожидаемый результат.
6. Проверка.
7. Только потом следующий этап.

Не создавать сотни файлов без проверки.

---

# 18. Definition of Done

Проект готов только когда:

## Functional

- все старые функции сохранены;
- новых функций нет без согласования;
- новых защит нет без доказательства.

## Architecture

- один USB owner;
- один operation owner;
- UI отделён;
- нет god classes.

## Code Quality

- нет заглушек;
- нет dead code;
- нет мусора;
- нет временных решений;
- нет лишних зависимостей.

## UI

- Material 3;
- RU + EN;
- adaptive;
- понятные операции.

## Quality

- GitHub Actions green;
- tests green;
- lint green;
- build green.

## Hardware

Только реальные проверки считаются PASS.

---

# Главное правило

Не ломай то, что работает.

Не добавляй то, чего не просили.

Не добавляй защиту без доказательства.

Не усложняй без причины.

Сохрани NekoFlash.

Сделай его современным.