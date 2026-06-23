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
import com.unknownmod.util.DebugMessenger;
import com.unknownmod.util.SkinFetcher;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.server.network.ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginNetworkHandlerMixin {

    @Shadow
    private GameProfile authenticatedProfile;

    @Shadow
    private MinecraftServer server;

    @Unique
    private boolean unknownmod$patched;

    @Inject(method = "tick", at = @At("HEAD"))
    private void unknownmod$patchProfile(CallbackInfo ci) {
        if (unknownmod$patched || authenticatedProfile == null) {
            return;
        }

        GameProfile originalProfile = authenticatedProfile;
        if (!ProfileApplier.hasTextures(originalProfile) && originalProfile.name() != null && !originalProfile.name().isBlank()) {
            SkinFetcher.SkinData originalTextures = SkinFetcher.fetchTexturesByNickname(originalProfile.name());
            if (originalTextures != null) {
                originalProfile = ProfileApplier.copyWithTextures(
                        originalProfile,
                        new Property("textures", originalTextures.value, originalTextures.signature)
                );
                DebugMessenger.debug(server, "[login] fetched original textures for " + authenticatedProfile.name()
                        + "; valueLen=" + originalTextures.value.length()
                        + ", signatureLen=" + originalTextures.signature.length() + ".");
            } else {
                DebugMessenger.debug(server, "[login] original textures not found for " + authenticatedProfile.name() + ".");
            }
        }

        ProfileApplier.rememberOriginalProfile(originalProfile);

        UnknownConfig config = ConfigManager.getConfig();
        originalProfile = ProfileApplier.getOriginalProfile(authenticatedProfile.id());
        if (originalProfile == null) {
            originalProfile = authenticatedProfile;
        }
        boolean revealed = server != null && RevelationManager.isRevealed(server, authenticatedProfile.id());
        if (revealed) {
            DebugMessenger.debug(server, "[login] profile " + authenticatedProfile.name() + " is revealed; skipping anonymous patch.");
            unknownmod$patched = true;
            return;
        }

        String displayName = config.anonymous != null ? config.anonymous.name : null;
        Property texturesProp = ProfileApplier.resolveTextures(config);
        Property originalTextures = ProfileApplier.getTextures(originalProfile);

        boolean changeName = displayName != null && !displayName.isEmpty() && !displayName.equals(authenticatedProfile.name());
        boolean changeSkin = texturesProp != null || originalTextures != null;
        DebugMessenger.debug(server, "[login] evaluating profile patch for " + authenticatedProfile.name()
                + "; changeName=" + changeName
                + ", changeSkin=" + changeSkin
                + ", originalTextures=" + describeTextures(originalProfile)
                + ", resolvedTextures=" + (texturesProp == null ? "absent" : "present(valueLen=" + safeLen(texturesProp.value()) + ", signatureLen=" + safeLen(texturesProp.signature()) + ")")
                + ".");
        if (!changeName && !changeSkin) {
            DebugMessenger.debug(server, "[login] no changes required for " + authenticatedProfile.name() + ".");
            return;
        }

        String finalName = changeName ? displayName : authenticatedProfile.name();
        Property finalTextures = texturesProp != null ? texturesProp : originalTextures;
        GameProfile newProfile = new GameProfile(authenticatedProfile.id(), finalName);

        Multimap<String, Property> multimap = HashMultimap.create();
        for (var entry : originalProfile.properties().entries()) {
            if (finalTextures != null && "textures".equals(entry.getKey())) {
                continue;
            }
            multimap.put(entry.getKey(), entry.getValue());
        }

        if (finalTextures != null) {
            multimap.put("textures", finalTextures);
        }

        PropertyMap newProps = new PropertyMap(multimap);
        ((GameProfileAccessor) (Object) newProfile).setProperties(newProps);

        this.authenticatedProfile = newProfile;
        this.unknownmod$patched = true;
        DebugMessenger.debug(server, "[login] profile patched for " + authenticatedProfile.name() + " -> " + finalName + "; texturesCount=" + newProps.get("textures").size() + ".");
    }

    private static String describeTextures(GameProfile profile) {
        Property textures = ProfileApplier.getTextures(profile);
        if (textures == null) {
            return "absent";
        }

        return "present(valueLen=" + safeLen(textures.value()) + ", signatureLen=" + safeLen(textures.signature()) + ")";
    }

    private static int safeLen(String value) {
        return value == null ? 0 : value.length();
    }
}
