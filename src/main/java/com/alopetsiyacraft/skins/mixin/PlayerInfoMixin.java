package com.alopetsiyacraft.skins.mixin;

import com.alopetsiyacraft.skins.client.SiteSkins;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Подставляет скин с сайта Alopetsiyacraft вместо ванильного.
 *
 * Все пути, которые рисуют скин игрока, сходятся в одном методе
 * {@link PlayerInfo#getSkin()} (его зовут PlayerRenderer — текстура модели
 * и slim/wide, PlayerTabOverlay — иконки в табе, сам игрок — вид от первого
 * лица и т.д.). Поэтому один миксин перехватывает всё: если для профиля есть
 * скин на сайте — возвращаем его, иначе оставляем ванильное поведение
 * (в offline-режиме это Стив).
 *
 * Клиентский миксин: лежит в секции "client" файла
 * alopetsiyaskins.mixins.json и на выделенном сервере не применяется.
 */
@Mixin(value = PlayerInfo.class, remap = false)
public class PlayerInfoMixin {

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true, remap = false)
    private void alopetsiyaskins$siteSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerInfo self = (PlayerInfo) (Object) this;
        PlayerSkin siteSkin = SiteSkins.INSTANCE.getSkin(self.getProfile());
        if (siteSkin != null) {
            cir.setReturnValue(siteSkin);
        }
    }
}