package com.unknownmod.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.unknownmod.config.ConfigManager;
import com.unknownmod.config.UnknownConfig;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(ServerLoginNetworkHandler.class)
public abstract class ServerLoginNetworkHandlerMixin {

    @Shadow
    private GameProfile profile;

    @Unique
    private boolean unknownmod$patched;

    @Inject(method = "tick", at = @At("HEAD"))
    private void unknownmod$patchProfile(CallbackInfo ci) {
        if (unknownmod$patched || profile == null) return;

        UnknownConfig config = ConfigManager.getConfig();
        String nickname = config.anonymous.skin.nickname;
        String texture = config.anonymous.skin.texture;
        String signature = config.anonymous.skin.signature;

        boolean hasNickname = nickname != null && !nickname.isEmpty();
        boolean hasSkin = texture != null && !texture.isEmpty()
                && signature != null && !signature.isEmpty();

        if (!hasNickname && !hasSkin) return;

        String finalName = hasNickname ? nickname : profile.name();
        UUID finalUuid = profile.id();

        GameProfile newProfile = new GameProfile(finalUuid, finalName);

        PropertyMap props = newProfile.properties();

        if (hasSkin) {
            props.put("textures", new Property("textures", texture, signature));
        }

        this.profile = newProfile;
        this.unknownmod$patched = true;
    }
}