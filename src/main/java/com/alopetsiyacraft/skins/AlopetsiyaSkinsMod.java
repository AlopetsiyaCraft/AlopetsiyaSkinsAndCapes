package com.alopetsiyacraft.skins;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Клиентский мод: рисует игроков в скинах, загруженных на сайт
 * Alopetsiyacraft (вместо Стива, который показывает offline-режим).
 *
 * Скин качается с сайта по нику (ник в игре = ник сайта), подмена идёт
 * миксином в PlayerInfo.getSkin (см. mixin.PlayerInfoMixin и
 * alopetsiyaskins.mixins.json, секция "client").
 *
 * Мод чисто клиентский: устанавливается только в mods клиента; на
 * выделенном сервере миксин не применяется.
 */
@Mod(AlopetsiyaSkinsMod.MODID)
public class AlopetsiyaSkinsMod {
    public static final String MODID = "alopetsiyaskins";
    private static final Logger LOGGER = LogUtils.getLogger();

    public AlopetsiyaSkinsMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        LOGGER.info("AlopetsiyaSkins mod loaded");
    }
}