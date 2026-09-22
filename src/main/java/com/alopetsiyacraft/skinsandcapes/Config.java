package com.alopetsiyacraft.skinsandcapes;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class Config {
    public static final ModConfigSpec SPEC;
    public static final Config INSTANCE;

    static {
        Pair<Config, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Config::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    public final ModConfigSpec.ConfigValue<String> websiteUrl;

    public Config(ModConfigSpec.Builder builder) {
        builder.push("alopetsiyaskinsandcapes");
        websiteUrl = builder
            .comment("URL of the Alopetsiyacraft website (e.g. http://localhost:3000)")
            .define("websiteUrl", "http://26.80.76.48:3000");
        builder.pop();
    }
}
