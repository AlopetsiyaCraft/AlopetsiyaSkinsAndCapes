package com.alopetsiyacraft.skinsandcapes;

import com.alopetsiyacraft.skinsandcapes.client.SiteTextures;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Клиентский мод: рисует игроков в скинах, плащах и модели (Стив/Алекс),
 * загруженных на сайт Alopetsiyacraft (вместо Стива, который показывает
 * offline-режим).
 *
 * Всё качается с сайта по нику (ник в игре = ник сайта), подмена идёт
 * миксином в PlayerInfo.getSkin (см. mixin.PlayerInfoMixin и
 * alopetsiyaskinsandcapes.mixins.json, секция "client").
 *
 * Обновление без перезапуска: пока игрок в онлайне, внешность по
 * расписанию перечитывается с сайта (SiteTextures.REFRESH_MS), а при
 * выходе с сервера кеш очищается, чтобы следующий заход сразу тянул
 * свежую внешность.
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

        // Выход с сервера -> полная очистка кеша внешности, чтобы перезаход
        // сразу показал свежий скин/плащ/модель с сайта.
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> SiteTextures.INSTANCE.clear());

        LOGGER.info("AlopetsiyaSkinsAndCapes mod loaded");
    }
}