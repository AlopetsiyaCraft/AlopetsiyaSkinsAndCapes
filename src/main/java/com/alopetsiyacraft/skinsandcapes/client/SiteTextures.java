package com.alopetsiyacraft.skinsandcapes.client;

import com.alopetsiyacraft.skinsandcapes.Config;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Внешность игроков из профилей сайта Alopetsiyacraft: скин, плащ и
 * модель персонажа (Стив wide / Алекс slim).
 *
 * На крякнутом (offline) сервере ванильный клиент не получает скины
 * Mojang — все выглядят как Стив. Mojang с версии ~1.20.2 требует, чтобы
 * чужие текстуры были подписаны его ключом, поэтому «серверный» вариант
 * (переслать URL скина в пакете player info, как SkinRestorer) на ванильных
 * клиентах показывает внешность только самому игроку. Единственный надёжный
 * путь — клиентский мод, который сам решает, что рисовать.
 *
 * Один запрос к /api/chat/head?nickname=&lt;ник&gt; (в offline режиме ник в
 * игре = ник сайта) отдаёт сразу всё: skinUrl, capeUrl и skinModel.
 * Готовый {@link PlayerSkin} подставляется миксином в
 * {@code PlayerInfo.getSkin()}: один миксин покрывает и модель игрока
 * (slim/wide), и текстуру, и портреты в табе (Tab), и плащ, и руку от
 * первого лица — всё, что рисует внешность игрока.
 *
 * Модель задаётся явно на сайте: PNG скина 64x64 сам по себе не
 * сообщает, тонкая модель или широкая, поэтому size-эвристика (64x128)
 * осталась лишь как запасной вариант для старых ответов без skinModel.
 *
 * Если у игрока на сайте нет ни скина, ни плаща или сайт недоступен —
 * возвращается null (миксин оставляет ванильное поведение: Стив).
 * Неудачные попытки повторяются не чаще раза в минуту. Внешность
 * кешируется на сессию (перезаход обновит её).
 *
 * Активируется только с клиента: класс подключается из миксина
 * PlayerInfoMixin (секция "client" в alopetsiyaskinsandcapes.mixins.json).
 */
@OnlyIn(Dist.CLIENT)
public final class SiteTextures {

    public static final SiteTextures INSTANCE = new SiteTextures();

    private static final String NAMESPACE = "alopetsiyaskinsandcapes";
    /** Скин не меньше стандартных 64x64. */
    private static final int MIN_SKIN = 64;
    /** Ванильный плащ — 64x32 (UV-развёртка CapeLayer). */
    private static final int CAPE_WIDTH = 64;
    private static final int CAPE_HEIGHT = 32;

    /** UUID -> готовая внешность с сайта. */
    private final ConcurrentMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    /** UUID -> загрузка уже запущена. */
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    /** UUID -> время, раньше которого неудачную попытку не повторяем. */
    private final ConcurrentMap<UUID, Long> retryAfter = new ConcurrentHashMap<>();

    private SiteTextures() {
    }

    /**
     * Готовый результат загрузки. Скин и плащ независимы: можно иметь
     * только плащ — тогда тело останется дефолтным (берётся по UUID).
     */
    private record Entry(
        ResourceLocation skin,
        ResourceLocation cape,
        PlayerSkin.Model model,
        String skinUrl,
        String capeUrl
    ) {
    }

    /**
     * Внешность с сайта для профиля игрока, или null если её (пока) нет.
     * Вызывается с render-потока для каждого игрока каждый кадр, поэтому
     * здесь только быстрые чтения кеша; медленная загрузка — в фоновом
     * потоке, готовый результат приходит через {@code Minecraft.execute}.
     */
    public PlayerSkin getSkin(GameProfile profile) {
        Entry entry = entries.get(profile.getId());
        if (entry != null) {
            ResourceLocation texture = entry.skin() != null
                ? entry.skin()
                : DefaultPlayerSkin.get(profile).texture();
            // secure=true: подмена идёт напрямую, минуя ванильную проверку
            // подписи Mojang у чужих игроков (см. PlayerInfo.createSkinLookup).
            return new PlayerSkin(texture, entry.skinUrl(), entry.cape(), null, entry.model(), true);
        }
        Long retry = retryAfter.get(profile.getId());
        if (retry != null && System.currentTimeMillis() < retry) {
            return null;
        }
        if (loading.add(profile.getId())) {
            Thread.ofVirtual().start(() -> load(profile));
        }
        return null;
    }

    /**
     * Фоновая загрузка: сайт (skinUrl + capeUrl + skinModel) -> PNG ->
     * NativeImage -> регистрация текстур на render-потоке.
     */
    private void load(GameProfile profile) {
        HttpURLConnection lookupConn = null;
        HttpURLConnection skinConn = null;
        HttpURLConnection capeConn = null;
        try {
            String base = Config.INSTANCE.websiteUrl.get().replaceAll("/+$", "");
            String nick = profile.getName();
            String nickKey = sanitize(nick.toLowerCase(Locale.ROOT));

            URL lookup = new URL(base + "/api/chat/head?nickname=" + URLEncoder.encode(nick, StandardCharsets.UTF_8));
            lookupConn = open(lookup);
            JsonObject info = JsonParser.parseString(readFully(lookupConn.getInputStream())).getAsJsonObject();

            String skinUrl = optString(info, "skinUrl");
            String capeUrl = optString(info, "capeUrl");
            if (skinUrl == null && capeUrl == null) {
                scheduleRetry(profile.getId()); // на сайте пока нет ни скина, ни плаща
                return;
            }

            // Модель (Стив/Алекс) выбирается игроком на сайте.
            PlayerSkin.Model model = siteModel(info);

            ResourceLocation skinId = null;
            DynamicTexture skinTex = null;
            if (skinUrl != null) {
                skinConn = open(new URL(abs(base, skinUrl)));
                BufferedImage skin = ImageIO.read(skinConn.getInputStream());
                if (skin == null || skin.getWidth() < MIN_SKIN || skin.getHeight() < MIN_SKIN) {
                    scheduleRetry(profile.getId()); // файл скина битый — ждём, заменим
                    return;
                }
                if (model == null) {
                    // Старый ответ без skin_model: 64x128 — легаси-slim, иначе широкая.
                    model = (skin.getWidth() == 64 && skin.getHeight() == 128)
                        ? PlayerSkin.Model.SLIM
                        : PlayerSkin.Model.WIDE;
                }
                skinId = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "site_skin/" + nickKey);
                skinTex = new DynamicTexture(toNative(skin));
            }

            ResourceLocation capeId = null;
            DynamicTexture capeTex = null;
            if (capeUrl != null) {
                capeConn = open(new URL(abs(base, capeUrl)));
                BufferedImage cape = ImageIO.read(capeConn.getInputStream());
                if (cape == null) {
                    scheduleRetry(profile.getId()); // файл плаща битый
                    return;
                }
                if (cape.getWidth() != CAPE_WIDTH || cape.getHeight() != CAPE_HEIGHT) {
                    // Сайт принимает также 22x17 и 46x22 — приводим к ванильному 64x32.
                    cape = scaleToCape(cape);
                }
                capeId = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "site_cape/" + nickKey);
                capeTex = new DynamicTexture(toNative(cape));
            }

            if (skinId == null && capeId == null) {
                scheduleRetry(profile.getId());
                return;
            }
            if (model == null) {
                model = PlayerSkin.Model.WIDE; // плащ без скина: тело дефолтное
            }

            final ResourceLocation finalSkinId = skinId;
            final ResourceLocation finalCapeId = capeId;
            final DynamicTexture finalSkinTex = skinTex;
            final DynamicTexture finalCapeTex = capeTex;
            final PlayerSkin.Model finalModel = model;

            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                try {
                    if (finalSkinId != null) {
                        mc.getTextureManager().register(finalSkinId, finalSkinTex);
                    }
                    if (finalCapeId != null) {
                        mc.getTextureManager().register(finalCapeId, finalCapeTex);
                    }
                    entries.put(profile.getId(), new Entry(finalSkinId, finalCapeId, finalModel, skinUrl, capeUrl));
                    retryAfter.remove(profile.getId());
                } catch (Exception e) {
                    scheduleRetry(profile.getId());
                } finally {
                    loading.remove(profile.getId());
                }
            });
        } catch (Exception e) {
            // Сайт недоступен / сеть упала — повторим через минуту.
            scheduleRetry(profile.getId());
            loading.remove(profile.getId());
        } finally {
            if (lookupConn != null) lookupConn.disconnect();
            if (skinConn != null) skinConn.disconnect();
            if (capeConn != null) capeConn.disconnect();
        }
    }

    /** Модель с сайта: "slim" -> SLIM, "wide" -> WIDE, иначе null (эвристика). */
    private static PlayerSkin.Model siteModel(JsonObject info) {
        String raw = optString(info, "skinModel");
        if ("slim".equalsIgnoreCase(raw)) return PlayerSkin.Model.SLIM;
        if ("wide".equalsIgnoreCase(raw)) return PlayerSkin.Model.WIDE;
        return null;
    }

    /** Плащ нестандартного размера -> ванильные 64x32 (nearest, без сглаживания). */
    private static BufferedImage scaleToCape(BufferedImage src) {
        BufferedImage out = new BufferedImage(CAPE_WIDTH, CAPE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, CAPE_WIDTH, CAPE_HEIGHT, null);
        g.dispose();
        return out;
    }

    /** BufferedImage (ARGB, из файла сайта) -> NativeImage (ABGR). */
    private static NativeImage toNative(BufferedImage img) {
        NativeImage out = new NativeImage(NativeImage.Format.RGBA, img.getWidth(), img.getHeight(), false);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                // NativeImage хранит пиксель как ABGR-инт (в памяти — RGBA).
                out.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return out;
    }

    /** Значение строки из JSON или null, если поля нет / оно пустое. */
    private static String optString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        String s = el.getAsString();
        return s.isEmpty() ? null : s;
    }

    /** Относительный путь сайта ("/uploads/..") -> абсолютный URL. */
    private static String abs(String base, String path) {
        return path.startsWith("/") ? base + path : base + "/" + path;
    }

    private void scheduleRetry(UUID id) {
        retryAfter.put(id, System.currentTimeMillis() + 60_000L);
    }

    private static HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(8000);
        if (conn.getResponseCode() != 200) {
            throw new IOException("HTTP " + conn.getResponseCode() + " from " + url);
        }
        return conn;
    }

    private static String readFully(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** Ник -> безопасный ресурсный путь. */
    private static String sanitize(String key) {
        StringBuilder sb = new StringBuilder();
        for (char c : key.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }
}
