package com.alopetsiyacraft.skinsandcapes.mixin;

import com.alopetsiyacraft.skinsandcapes.client.SiteTextures;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Подставляет внешность (скин + плащ + модель) с сайта Alopetsiyacraft
 * вместо ванильной.
 *
 * Все пути, которые рисуют скин игрока, сходятся в одном методе
 * {@link PlayerInfo#getSkin()} (его зовут PlayerRenderer — текстура модели
 * и slim/wide, PlayerTabOverlay — иконки в табе, CapeLayer — плащ, сам
 * игрок — вид от первого лица и т.д.). Поэтому один миксин перехватывает
 * всё: если для профиля есть данные на сайте — возвращаем их, иначе
 * оставляем ванильное поведение (в offline-режиме это Стив).
 *
 * Клиентский миксин: лежит в секции "client" файла
 * alopetsiyaskinsandcapes.mixins.json и на выделенном сервере не применяется.
 */
@Mixin(value = PlayerInfo.class, remap = false)
public class PlayerInfoMixin {

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true, remap = false)
    private void alopetsiyaskinsandcapes$siteSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerInfo self = (PlayerInfo) (Object) this;
        PlayerSkin siteSkin = SiteTextures.INSTANCE.getSkin(self.getProfile());
        if (siteSkin != null) {
            cir.setReturnValue(siteSkin);
        }
    }
}
