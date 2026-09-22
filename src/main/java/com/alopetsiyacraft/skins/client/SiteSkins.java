package com.alopetsiyacraft.skins.client;

import com.alopetsiyacraft.skins.Config;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.imageio.ImageIO;
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
 * Скины игроков из профилей сайта Alopetsiyacraft.
 *
 * На крякнутом (offline) сервере ванильный клиент не получает скины
 * Mojang — все выглядят как Стив. Mojang с версии ~1.20.2 требует, чтобы
 * чужие текстуры были подписаны его ключом, поэтому «серверный» вариант
 * (переслать URL скина в пакете player info, как SkinRestorer) на ванильных
 * клиентах показывает скин только самому игроку. Единственный надёжный
 * путь — клиентский мод, который сам решает, какой скин рисовать.
 *
 * Этот класс скачивает полный PNG скина с сайта по нику игрока (в offline
 * режиме ник в игре = ник сайта) и отдаёт готовый {@link PlayerSkin} для
 * подстановки в {@code PlayerInfo.getSkin()}: один миксин покрывает и модель
 * игрока (slim/wide), и текстуру, и иконки в табе (Tab), и руку от первого
 * лица, и всё остальное, что рисует скин игрока.
 *
 * Если у игрока нет скина на сайте или сайт недоступен — возвращается null
 * (миксин оставляет ванильное поведение: Стив). Неудачные попытки повторяются
 * не чаще раза в минуту. Скин кешируется на сессию (перезаход обновит его).
 *
 * Активируется только с клиента: класс подключается из миксина
 * PlayerInfoMixin (секция "client" в alopetsiyaskins.mixins.json).
 */
@OnlyIn(Dist.CLIENT)
public final class SiteSkins {

    public static final SiteSkins INSTANCE = new SiteSkins();

    /** Стандартные размеры современного скина. */
    private static final int MIN_WIDTH = 64;
    private static final int MIN_HEIGHT = 64;

    /** UUID -> готовый скин с сайта. */
    private final ConcurrentMap<UUID, Entry> skins = new ConcurrentHashMap<>();
    /** UUID -> загрузка уже запущена. */
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    /** UUID -> время, раньше которого неудачную попытку не повторяем. */
    private final ConcurrentMap<UUID, Long> retryAfter = new ConcurrentHashMap<>();

    private SiteSkins() {
    }

    /** Готовый результат загрузки скина. */
    private record Entry(ResourceLocation texture, PlayerSkin.Model model, String url) {
    }

    /**
     * Скин с сайта для профиля игрока, или null если его (пока) нет.
     * Вызывается с render-потока для каждого игрока каждый кадр, поэтому
     * здесь только быстрые чтения кеша; медленная загрузка — в фоновом
     * потоке, готовый результат приходит через {@code Minecraft.execute}.
     */
    public PlayerSkin getSkin(GameProfile profile) {
        Entry entry = skins.get(profile.getId());
        if (entry != null) {
            // secure=true: это уже не важно — мы подменяем скин напрямую,
            // минуя ванильную проверку подписи Mojang у чужих игроков.
            return new PlayerSkin(entry.texture(), entry.url(), null, null, entry.model(), true);
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
     * Фоновая загрузка: сайт -> URL скина -> PNG -> NativeImage ->
     * регистрация текстуры на render-потоке.
     */
    private void load(GameProfile profile) {
        HttpURLConnection jsonConn = null;
        HttpURLConnection pngConn = null;
        try {
            String base = Config.INSTANCE.websiteUrl.get().replaceAll("/+$", "");
            String nick = profile.getName();
            String nickEnc = URLEncoder.encode(nick, StandardCharsets.UTF_8);

            URL lookup = new URL(base + "/api/chat/head?nickname=" + nickEnc);
            jsonConn = open(lookup, "GET");
            String json = readFully(jsonConn.getInputStream());

            JsonElement skin = JsonParser.parseString(json).getAsJsonObject().get("skinUrl");
            if (skin == null || skin.isJsonNull() || skin.getAsString().isEmpty()) {
                scheduleRetry(profile.getId()); // скин появится позже — переспросим
                return;
            }
            String skinUrl = skin.getAsString();

            URL png = new URL(base + (skinUrl.startsWith("/") ? skinUrl : "/" + skinUrl));
            pngConn = open(png, "GET");
            BufferedImage img = ImageIO.read(pngConn.getInputStream());
            if (img == null || img.getWidth() < MIN_WIDTH || img.getHeight() < MIN_HEIGHT) {
                scheduleRetry(profile.getId());
                return;
            }

            // 64x128 — модель Alex (slim), 64x64 — широкая. По размеру PNG.
            PlayerSkin.Model model = (img.getWidth() == MIN_WIDTH && img.getHeight() == MIN_HEIGHT * 2)
                ? PlayerSkin.Model.SLIM
                : PlayerSkin.Model.WIDE;

            NativeImage nativeImg = toNative(img);
            DynamicTexture texture = new DynamicTexture(nativeImg);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                "alopetsiyaskins",
                "site_skin/" + sanitize(nick.toLowerCase(Locale.ROOT))
            );

            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                try {
                    mc.getTextureManager().register(id, texture);
                    skins.put(profile.getId(), new Entry(id, model, skinUrl));
                    retryAfter.remove(profile.getId());
                } catch (Exception ignored) {
                    scheduleRetry(profile.getId());
                } finally {
                    loading.remove(profile.getId());
                }
            });
        } catch (Exception e) {
            scheduleRetry(profile.getId());
            loading.remove(profile.getId());
        } finally {
            if (jsonConn != null) jsonConn.disconnect();
            if (pngConn != null) pngConn.disconnect();
        }
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

    private void scheduleRetry(UUID id) {
        retryAfter.put(id, System.currentTimeMillis() + 60_000L);
    }

    private static HttpURLConnection open(URL url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
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