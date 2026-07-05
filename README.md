# Unknown Mod

Fabric-мод для режима социальной дедукции: игроки скрыты под общей личностью, а reveal временно раскрывает выбранного игрока.

## Цель мода

- До reveal игроки не должны знать реальные ники и скины друг друга.
- Каждый игрок на своём клиенте должен видеть **себя** настоящим: свой ник и свой скин.
- Остальные игроки должны отображаться как аноним из конфига.
- Reveal должен работать **сразу**, без перезахода.
- Tab должен быть скрыт для нераскрытых игроков.
- Красная подсветка раскрытого игрока должна оставаться красной, включая после перезахода.

## Версии

- Minecraft: `26.1.2`
- Fabric Loader: `0.19.3`
- Fabric API: `0.151.0+26.1.2`
- Java: `25`
- Mod version: `4.2`

## Главные правила отображения

| Ситуация | Что видит игрок |
|---|---|
| Игрок смотрит на себя | Реальный ник + реальный скин |
| Игрок смотрит на другого нераскрытого | `anonymous.name` + `anonymous.skin` |
| Игрок смотрит на раскрытого | Реальный ник + реальный скин |
| Не раскрытый игрок в Tab | Скрыт (`listed=false`) |
| Раскрытый игрок в Tab | Видим |
| Командные подсказки игроков | Реальные ники заменяются на anonymous name |

## Anonymous config

Файл: `config/unknown/config.yml`

```yaml
anonymous:
  name: JustPlayer
  skin:
    texture: "base64 texture value"
    signature: "mojang signature"
```

- `texture` — base64 JSON из Mojang session server.
- `signature` — подпись Mojang для texture.
- Если `texture/signature` пустые, мод пытается получить скин по `anonymous.name` и сохранить его в конфиг.
- Если получить не получилось, используется fallback Steve.
- Для стабильного анонимного скина лучше всегда задавать `texture/signature` явно.

## Identity model

Мод хранит оригинальный профиль игрока в `IdentityStore`:

- UUID
- реальный ник
- texture/signature реального скина, если удалось получить

Оригинальный профиль нужен для:

- отображения самого себя настоящим;
- мгновенного reveal;
- восстановления после reveal cancel;
- корректного скина у лицензированных игроков на offline-mode сервере.

## Packet-level masking

Основная логика работает через outgoing packets, а не через постоянное изменение серверного профиля.

Ключевые файлы:

- `ProfileApplier.java` — собирает оригинальный/анонимный профиль для packet entry.
- `TabListFilterMixin.java` — фильтрует outgoing `ClientboundPlayerInfoUpdatePacket`.
- `ServerGamePacketListenerAccessor.java` — достаёт viewer player для per-viewer логики.
- `AppearanceSyncManager.java` — рассылает remove + initialize packets при смене состояния.
- `RevealGlowManager.java` — управляет scoreboard teams и glow.

## Per-viewer логика

Для каждого outgoing `ClientboundPlayerInfoUpdatePacket` мод знает viewer UUID.

Правило выбора профиля:

1. Если entry — раскрытый игрок → оригинальный профиль.
2. Если entry — сам viewer → оригинальный профиль.
3. Иначе → анонимный профиль из конфига.

Это важно: нельзя просто менять общий серверный GameProfile, иначе все клиенты будут видеть одно и то же.

## Reveal

Команды:

```text
/unknown reveal random
/unknown reveal <player>
/unknown reveal cancel
/unknown reveal cancel <player>
/unknown reveal status
```

При reveal:

1. UUID игрока попадает в `RevelationStateManager`.
2. `ProfileApplier.refreshAllOnline(server)` пересылает player info всем клиентам.
3. Для раскрытого игрока отправляется оригинальный профиль.
4. `RevealGlowManager` переводит игрока в team `unk_revealed`.
5. Игрок получает glow и красную подсветку.

Reveal не должен требовать перезахода.

## Glow / teams

Teams:

- `unk_hidden` — обычные скрытые игроки.
- `unk_revealed` — раскрытые игроки.

Раскрытый игрок:

- `player.setGlowingTag(true)`
- team color: `DARK_RED`
- name tag visibility: `ALWAYS`

При join мод синхронизирует reveal teams заново, чтобы игрок после перезахода видел текущие подсветки правильно.

## Tab

Tab скрывается через `listed=false` в `ClientboundPlayerInfoUpdatePacket.Entry`.

- Не раскрытые игроки скрыты.
- Раскрытые игроки остаются видимыми.
- Entry не удаляется полностью, потому что удаление ломает skin/profile cache на клиенте.

## Командные подсказки

`ClientboundCommandSuggestionsPacket` фильтруется:

- реальные ники нераскрытых игроков заменяются на `anonymous.name`;
- дубликаты anonymous name схлопываются;
- раскрытые игроки могут показываться реальным ником.

## Сообщения

```yaml
messages:
  eliminated: "<red>%player%</red> был убит игроком <red>%killer%</red> <white>и становится наблюдателем навсегда!</white>"
  weapon-eliminated: "<dark_red>☠</dark_red> <red>%player%</red> <white>выбыл из игры из-за <red>%killer%</red>!</white>"
  joined: "<yellow>%player%</yellow> вошёл в игру"
  left: "<yellow>%player%</yellow> покинул игру"
```

MiniMessage поддерживается.

## Revelation config

```yaml
revelation:
  enabled: true
  interval-hours: 2
  duration-minutes: 15
  min-players: 3
  warning-minutes: 5
```

- `interval-hours` — период автоматического reveal.
- `duration-minutes` — сколько длится раскрытие.
- `min-players` — минимум живых игроков для авто-reveal.
- `warning-minutes` — предупреждение до авто-reveal.

## Players command

```text
/players
```

Показывает реальные ники игроков, замеченных за последние `playerList.days` дней.

```yaml
playerList:
  days: 2
```

## Ghost / elimination

После смерти игрок может стать ghost/observer в зависимости от логики режима.

Reveal и ghost связаны:

- ghost не выбирается для нового reveal;
- если раскрытый игрок eliminated, reveal очищается;
- сообщения используют реальные имена только там, где это задумано механикой.

## Debug

```yaml
debug:
  enabled: false
```

При включении пишет подробные логи:

- patch login profile;
- resolved textures;
- packet profile changes;
- reveal state changes.

## Known pitfalls

- Не удалять полностью `PlayerInfo` entries ради скрытия Tab: это ломает скины.
- Не полагаться только на login GameProfile: reveal должен работать packet-level.
- Не делать глобальную замену профиля без viewer UUID: каждый должен видеть себя настоящим.
- Offline-mode серверу часто нужно fetch textures по нику через Mojang API.
- Два mixin-а на один `send()` HEAD могут конфликтовать; профиль и tab фильтруются в одном месте.

## Build

```bash
export JAVA_HOME=/opt/jdk-25.0.3
./gradlew clean build
```

Jar:

```text
build/libs/unknownmod-4.2.jar
```

## Deploy на текущий сервер

```bash
scp -i /root/.ssh/unknownmod_ci build/libs/unknownmod-4.2.jar \
  minecraft@212.80.7.86:/home/minecraft/server/mods/unknownmod-4.2.jar
```

Перезапуск без sudo:

```bash
ssh -i /root/.ssh/unknownmod_ci minecraft@212.80.7.86 \
  'pid=$(systemctl show minecraft.service -p MainPID --value); kill -TERM "$pid"'
```

Systemd сам поднимет сервер из-за `Restart=always`.

## Проверка после деплоя

```bash
systemctl is-active minecraft.service
journalctl -u minecraft.service -n 100 --no-pager | grep -i unknownmod
```

В игре проверить:

1. До reveal: другие игроки = JustPlayer + anonymous skin.
2. До reveal: сам игрок = свой ник + свой скин.
3. `/unknown reveal <player>` работает сразу.
4. После reveal: ник/скин раскрытого меняются без перезахода.
5. Glow раскрытого красный.
6. После перезахода glow остаётся красным.
7. Tab скрывает нераскрытых и показывает раскрытых.
