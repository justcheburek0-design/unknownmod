package com.unknownmod.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public class UnknownConfig {
    public AnonymousSettings anonymous = new AnonymousSettings();
    public MessagesSettings messages = new MessagesSettings();

    @ConfigSerializable
    public static class AnonymousSettings {
        public String name = "JustPlayer";
        public SkinSettings skin = new SkinSettings();
    }

    @ConfigSerializable
    public static class SkinSettings {
        public String type = "texture";
        public String texture = "";
        public String signature = "";
        public String nickname = "";
    }

    @ConfigSerializable
    public static class MessagesSettings {
        public String eliminated = "&c%player% &fбыл убит игроком &c%killer% &fи становится наблюдателем навсегда!";
        public String joined = "&e%player% вошел в игру";
        public String left = "&e%player% покинул игру";
    }
}
