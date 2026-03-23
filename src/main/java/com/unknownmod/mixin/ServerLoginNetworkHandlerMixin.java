package com.unknownmod.mixin;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.unknownmod.config.ConfigManager;
import com.unknownmod.config.UnknownConfig;
import com.unknownmod.state.RevelationManager;
import com.unknownmod.util.ProfileApplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLoginNetworkHandler.class)
public abstract class ServerLoginNetworkHandlerMixin {

    @Shadow
    private GameProfile profile;

    @Shadow
    private MinecraftServer server;

    @Unique
    private boolean unknownmod$patched;

    @Inject(method = "tick", at = @At("HEAD"))
    private void unknownmod$patchProfile(CallbackInfo ci) {
        if (unknownmod$patched || profile == null) {
            return;
        }

        ProfileApplier.rememberOriginalProfile(profile);

        UnknownConfig config = ConfigManager.getConfig();
        boolean revealed = server != null && RevelationManager.isRevealed(server, profile.id());
        if (revealed) {
            unknownmod$patched = true;
            return;
        }

        String displayName = config.anonymous != null ? config.anonymous.name : null;
        Property texturesProp = ProfileApplier.resolveTextures(config);

        boolean changeName = displayName != null && !displayName.isEmpty() && !displayName.equals(profile.name());
        boolean changeSkin = texturesProp != null;
        if (!changeName && !changeSkin) {
            return;
        }

        String finalName = changeName ? displayName : profile.name();
        GameProfile newProfile = new GameProfile(profile.id(), finalName);

        Multimap<String, Property> multimap = HashMultimap.create();
        for (var entry : profile.properties().entries()) {
            if (!"textures".equals(entry.getKey())) {
                multimap.put(entry.getKey(), entry.getValue());
            }
        }

        if (texturesProp != null) {
            multimap.put("textures", texturesProp);
        } else {
            for (var entry : profile.properties().entries()) {
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
