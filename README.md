# Unknown Mod

Серверный Fabric-мод для режима социальной дедукции. Он скрывает личности игроков, умеет переводить их в `ghost`-состояние, запускает `reveal` и отправляет кастомные сообщения о важных событиях.

## Возможности

- Подменяет отображаемое имя игрока на анонимное.
- Может подменять скин анонимного имени через SkinRestorer.
- Поддерживает ручной перевод игрока в `ghost` и обратное восстановление.
- Запускает автоматический и ручной `reveal` с таймером, предупреждением и отменой.
- Отправляет кастомные сообщения о входе, выходе, смерти и раскрытии личности.
- Имеет режим `debug` для диагностики и более понятных логов.
- Отдельно настраивает worldgen-override для новых чанков.

## Требования

- Minecraft `1.21.11`
- Java `21`
- Fabric Loader `0.16.5+`
- Fabric API
- Polymer Core
- SkinRestorer

Часть библиотек уже лежит в jar, но Fabric, Polymer Core и SkinRestorer должны быть доступны на сервере.

## Установка

1. Соберите мод командой `./gradlew build`.
2. Возьмите jar из `build/libs/`.
3. Положите jar в папку `mods` на сервере Fabric.
4. Убедитесь, что рядом установлены Fabric API, Polymer Core и SkinRestorer.
5. Запустите сервер один раз, чтобы создалась папка `config/unknown/`.

## Конфиг

Основной конфиг создается автоматически в `config/unknown/config.yml`.

Основные разделы:

- `anonymous` - имя и скин, которые будут показываться вместо реального профиля.
- `messages` - шаблоны сообщений о входе, выходе, убийстве и раскрытии личности.
- `revelation` - настройки таймера, длительности и минимального порога игроков.
- `debug` - включает или выключает режим отладки.

Пример:

```yaml
anonymous:
  name: JustPlayer
  skin:
    type: texture
    texture: ""
    signature: ""
    nickname: ""

messages:
  eliminated: "<red>%player%</red> был убит игроком <red>%killer%</red> и становится наблюдателем навсегда!"
  weaponEliminated: "<dark_red>☠</dark_red> <red>%player%</red> выбывает из игры из-за <red>%killer%</red>!"
  joined: "<yellow>%player%</yellow> вошёл в игру"
  left: "<yellow>%player%</yellow> покинул игру"

revelation:
  enabled: true
  interval-hours: 2
  duration-minutes: 15
  min-players: 3
  warning-minutes: 5

debug:
  enabled: false
```

Поддерживаемые плейсхолдеры в сообщениях:

- `%player%`
- `%killer%`
- `%minutes%`
- `%hours%`
- `%n%`
- `%time%`

Важно:

- `interval-hours` и `duration-minutes` не опускаются ниже `1`.
- `warning-minutes` может быть `0`, если предупреждение не нужно.

## Команды

Все команды требуют права оператора или аналогичные права `gamemaster`.

| Команда | Что делает |
| --- | --- |
| `/unknown reload` | Перечитывает `config/unknown/config.yml` и применяет изменения без рестарта. |
| `/unknown debug` | Переключает режим отладки. |
| `/unknown nickname <value>` | Меняет анонимное имя игроков. |
| `/unknown skin <value>` | Если указать `texture`, применяет сохранённый `texture/signature`. Иначе пытается найти скин по никнейму. |
| `/unknown ghost <player>` | Переключает игрока в `ghost` или возвращает его обратно. |
| `/unknown reveal player` | Запускает `reveal` на случайном подходящем игроке. |
| `/unknown reveal player <player>` | Запускает `reveal` на конкретном игроке. |
| `/unknown reveal cancel` | Отменяет активный `reveal`. |
| `/unknown reveal interval <hours>` | Меняет интервал между событиями `reveal`. |
| `/unknown reveal duration <minutes>` | Меняет длительность `reveal`. |
| `/unknown reveal minplayers <n>` | Меняет минимальный порог игроков для запуска `reveal`. |
| `/unknown reveal status` | Показывает текущий статус таймера или активного `reveal`. |

Примечание по `minplayers`: `reveal` стартует только когда онлайн игроков больше, чем указанное значение. Например, при `minplayers = 3` нужен минимум `4` игрока онлайн.

## Worldgen

Worldgen использует отдельный файл `config/unknown/worldgen.yml`.

Пример:

```yaml
chance: 100
partial-chance: 100
partial-exclusions:
  - air
  - grass
  - allow:grass_block
  - flower
overrides:
  air:
    type: full
    weight: 5
    blocks: {}
  stone:
    type: all
    weight: 1
  pack_ores:
    type: part
    weight: 2
    blocks:
      diamond_ore: 1
      iron_ore: 9
      save: 20
```

Что важно знать:

- `chance` - шанс для режима `full`.
- `partial-chance` - шанс для режима `part`.
- Эти шансы независимы. Если оба не сработали, чанк не меняется.
- Если для одного чанка подошли оба режима, сначала выбирается `full`.
- `type: full` значит, что override используется только в полном режиме.
- `type: part` значит, что override используется только в частичном режиме.
- `type: all` значит, что override используется и в полном, и в частичном режиме.
- Если `type` не указан, он тоже считается `all`.
- `partial-exclusions` задает список блоков или частей id, которые не трогаются в `part`-режиме.
- Если нужен точный запрет только для одного блока, добавь `=` в начале, например `=grass` или `=minecraft:grass`.
- Если нужно вернуть отдельный блок обратно в обработку, добавь `allow:` в начале, например `allow:grass_block`. Это полезно, когда общее правило слишком широкое.
- В `blocks` можно использовать специальный ключ `save`, чтобы оставить текущий блок как есть.
- Override без `blocks` использует имя записи как id блока.
- Override с `blocks` работает как взвешенный набор блоков.
- Изменения применяются только к новым чанкам в Overworld.
- Перезагрузка доступна через `/unknown reload` или `/unknown worldgen reload`.

## Сборка

```bash
./gradlew build
```

Готовый jar появится в `build/libs/`.

## Тестирование

Подробный план проверки перед публикацией вынесен в [TESTING.md](./TESTING.md).

## Лицензия

Проект распространяется под `CC0-1.0`.
