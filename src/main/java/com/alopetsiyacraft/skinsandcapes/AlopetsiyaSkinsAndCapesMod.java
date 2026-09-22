package com.alopetsiyacraft.skinsandcapes;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Клиентский мод: рисует игроков в скинах, плащах и модели (Стив/Алекс),
 * загруженных на сайт Alopetsiyacraft (вместо Стива, который показывает
 * offline-режим).
 *
 * Всё качается с сайта по нику (ник в игре = ник сайта), подмена идёт
 * миксином в PlayerInfo.getSkin (см. mixin.PlayerInfoMixin и
 * alopetsiyaskinsandcapes.mixins.json, секция "client").
 *
 * Мод чисто клиентский: устанавливается только в mods клиента; на
 * выделенном сервере миксин не применяется.
 */
@Mod(AlopetsiyaSkinsAndCapesMod.MODID)
public class AlopetsiyaSkinsAndCapesMod {
    public static final String MODID = "alopetsiyaskinsandcapes";
    private static final Logger LOGGER = LogUtils.getLogger();

    public AlopetsiyaSkinsAndCapesMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        LOGGER.info("AlopetsiyaSkinsAndCapes mod loaded");
    }
}
