package com.unknownmod.mixin;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
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
        // Use configured anonymous display name, not skin nickname
        String displayName = config.anonymous != null ? config.anonymous.name : null;

        UnknownConfig.SkinSettings skin = config.anonymous != null ? config.anonymous.skin : null;

        // Build textures property according to config; if not resolvable, keep player's original skin
        Property texturesProp = null;
        if (skin != null) {
            String type = skin.type != null ? skin.type : "";
            if ("texture".equalsIgnoreCase(type)) {
                String texture = skin.texture;
                String signature = skin.signature;
                if (texture != null && !texture.isEmpty() && signature != null && !signature.isEmpty()) {
                    texturesProp = new Property("textures", texture, signature);
                }
            } else if ("nickname".equalsIgnoreCase(type)) {
                String nick = skin.nickname;
                if (nick != null && !nick.isEmpty()) {
                    // Resolve skin from nickname via Mojang session service
                    texturesProp = com.unknownmod.util.SkinFetcher.fetchTexturesByNickname(nick);
                }
            }
        }

        // If nothing to change, exit early
        boolean changeName = displayName != null && !displayName.isEmpty() && !displayName.equals(profile.name());
        boolean changeSkin = texturesProp != null;
        if (!changeName && !changeSkin) return;

        String finalName = changeName ? displayName : profile.name();
        UUID finalUuid = profile.id();
        GameProfile newProfile = new GameProfile(finalUuid, finalName);

        // Start with original properties (to avoid clearing skin -> Alex/Steve)
        Multimap<String, Property> multimap = HashMultimap.create();
        for (var entry : profile.getProperties().entries()) {
            if (!"textures".equals(entry.getKey())) {
                multimap.put(entry.getKey(), entry.getValue());
            }
        }
        if (texturesProp != null) {
            multimap.put("textures", texturesProp);
        } else {
            // Keep original textures if we didn't resolve another
            for (var entry : profile.getProperties().entries()) {
                if ("textures".equals(entry.getKey())) {
                    multimap.put(entry.getKey(), entry.getValue());
                }
            }
        }

        PropertyMap newProps = new PropertyMap(multimap);
        ((GameProfileAccessor) (Object) newProfile).setProperties(newProps);

        this.profile = newProfile;
        this.unknownmod$patched = true;
    }
}
