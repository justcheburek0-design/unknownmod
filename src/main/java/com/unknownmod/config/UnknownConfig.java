package com.unknownmod.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

@ConfigSerializable
public class UnknownConfig {
    public AnonymousSettings anonymous = new AnonymousSettings();
    public MessagesSettings messages = new MessagesSettings();
    public RevelationSettings revelation = new RevelationSettings();
    public PlayerListSettings playerList = new PlayerListSettings();
    public DebugSettings debug = new DebugSettings();

    @ConfigSerializable
    public static class AnonymousSettings {
        public String name = "JustPlayer";
        public SkinSettings skin = new SkinSettings();
    }

    @ConfigSerializable
    public static class SkinSettings {
        public String texture = "";
        public String signature = "";
    }

    @ConfigSerializable
    public static class MessagesSettings {
        public String eliminated = "<red>%player%</red> был убит игроком <red>%killer%</red> <white>и становится наблюдателем навсегда!</white>";
        public String weaponEliminated = "<dark_red>☠</dark_red> <red>%player%</red> <white>выбыл из игры из-за <red>%killer%</red>!</white>";
        public String joined = "<yellow>%player%</yellow> вошёл в игру";
        public String left = "<yellow>%player%</yellow> покинул игру";
    }

    @ConfigSerializable
    public static class RevelationSettings {
        @Setting("enabled")
        public boolean enabled = true;

        @Setting("interval-hours")
        public int intervalHours = 2;

        @Setting("duration-minutes")
        public int durationMinutes = 15;

        @Setting("min-players")
        public int minPlayers = 3;

        @Setting("warning-minutes")
        public int warningMinutes = 5;

        public RevelationMessages messages = new RevelationMessages();
    }

    @ConfigSerializable
    public static class DebugSettings {
        @Setting("enabled")
        public boolean enabled = false;
    }

    @ConfigSerializable
    public static class PlayerListSettings {
        @Setting("days")
        public int days = 2;
    }

    @ConfigSerializable
    public static class RevelationMessages {
        public String warning = "<yellow>⚠ Внимание!</yellow> Через <yellow>%minutes%</yellow> мин. личность одного из игроков будет раскрыта...";

        @Setting("reveal-title")
        public String revealTitle = "<red><bold>☠ ОХОТА НАЧАЛАСЬ</bold></red>";

        @Setting("reveal-subtitle")
        public String revealSubtitle = "<white>%player% раскрыт!</white>";

        @Setting("reveal-chat")
        public String revealChat = "<red><bold>☠</bold></red> <red>%player%</red> <white>раскрыт! Его личность теперь известна всем - охота началась!</white>";

        @Setting("cancel-title")
        public String cancelTitle = "<green><bold>✔ ОХОТА ЗАВЕРШЕНА</bold></green>";

        @Setting("cancel-subtitle")
        public String cancelSubtitle = "<gray>Разоблачённый снова в тени</gray>";

        @Setting("cancel-chat")
        public String cancelChat = "<green>%player%</green> <white>вернулся в тень. Охота закончилась.</white>";

        public String eliminated = "<dark_red>☠</dark_red> <red>%player%</red> <white>убит и вылетает из эксперимента навсегда!</white>";

        @Setting("not-enough-players")
        public String notEnoughPlayers = "<gray>[Разоблачение] Недостаточно игроков для старта события.</gray>";

        @Setting("no-active")
        public String noActive = "<gray>Сейчас никто не раскрыт.</gray>";

        @Setting("status-revealed")
        public String statusRevealed = "<yellow>Сейчас раскрыт:</yellow> <red>%player%</red> <gray>(осталось: <yellow>%time%</yellow>)</gray>";

        @Setting("status-timer")
        public String statusTimer = "<gray>До следующего разоблачения: <yellow>%time%</yellow></gray>";

        @Setting("interval-set")
        public String intervalSet = "<green>Интервал разоблачения установлен: <yellow>%hours% ч.</yellow></green>";

        @Setting("duration-set")
        public String durationSet = "<green>Длительность охоты установлена: <yellow>%minutes% мин.</yellow></green>";

        @Setting("minplayers-set")
        public String minPlayersSet = "<green>Минимальное кол-во игроков установлено: <yellow>%n%</yellow></green>";

        @Setting("victim-countdown")
        public String victimCountdown = "<gray>Тебя ищут... <red>%time%</red> <gray>осталось</gray>";
    }
}
