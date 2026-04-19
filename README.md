# Unknown Mod

Мод для режима социальной дедукции.

## Что делает

- Прячет личность игрока под анонимным именем.
- Использует анонимный скин из `texture/signature`.
- Если скин задаётся по нику, мод один раз резолвит его в `texture/signature` и сохраняет результат в конфиг.
- Если `texture/signature` не задан, анонимный профиль остаётся без оригинального скина игрока.
- Поддерживает ручной перевод игрока в `ghost` и обратное восстановление.
- Запускает автоматический и ручной `reveal` с таймером, предупреждением и отменой.
- Отправляет кастомные сообщения о входе, выходе, смерти и раскрытии личности.
- Имеет режим `debug` для диагностики.
- Настраивает worldgen override для новых чанков.

## Клиентская часть

В этот же jar входит и клиентский entrypoint.

- Он обновляет профиль игрока и скин на клиенте.
- Он чинит красную обводку для `reveal` вместо белой.
- Ставить этот же мод на клиент нужно, если нужны визуальные фиксы.

## Требования

- Minecraft `1.21.11`
- Java `21`
- Fabric Loader `0.16.5+`
- Fabric API

## Установка

1. Собери мод командой `./gradlew build`.
2. Возьми jar из `build/libs/`.
3. Положи jar в папку `mods` на сервере Fabric.
4. Убедись, что рядом стоит Fabric API.
5. Если нужны визуальные правки на клиенте, поставь этот же jar и на клиент.
6. Запусти сервер один раз, чтобы создалась папка `config/unknown/`.

## Конфиг

Основной конфиг создаётся автоматически в `config/unknown/config.yml`.

Дополнительный worldgen-конфиг создаётся в `config/unknown/worldgen.yml`.

Главные разделы:

- `anonymous` - имя и скин, которые показываются вместо реального профиля.
- `messages` - шаблоны сообщений о входе, выходе, убийстве и раскрытии личности.
- `revelation` - настройки таймера, длительности и минимального числа игроков.
- `debug` - включает или выключает режим отладки.
- `worldgen` - правила для замены блоков в новых чанках.

Пример:

```yaml
anonymous:
  name: JustPlayer
  skin:
    texture: ""
    signature: ""

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

## Players command

- `/players` shows original player nicknames seen within the last `playerList.days` days.

```yaml
playerList:
  days: 2
```
