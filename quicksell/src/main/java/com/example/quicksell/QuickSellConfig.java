package com.example.quicksell;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * config/quicksell.json dosyasından okunan ayarlar.
 * Oyun içinden değiştirmek için bu dosyayı düzenleyip oyunu yeniden başlat.
 */
public class QuickSellConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("quicksell.json");

    /** /sellgui komutunun ismi (başındaki "/" olmadan) */
    public String sellCommand = "sellgui";

    /** Açılan GUI'nin başlığında geçmesi gereken metin (bu şekilde doğru menüyü tanır) */
    public String guiTitleContains = "Satış Rehberi";

    /** Tuşa basıldıktan sonra komut gönderilir; GUI açılana kadar bekleme (tick, 20 tick = 1 saniye) */
    public int openDelayTicks = 4;

    /** Her shift-click arasındaki bekleme (tick). Çok düşük olursa sunucu paketleri kaçırabilir. */
    public int clickDelayTicks = 2;

    /** Son itemi koyduktan sonra menüyü kapatmadan önceki bekleme (tick) */
    public int closeDelayTicks = 6;

    /** Otomatik toplama hangi yarıçapta (blok) item arasın */
    public double collectRadius = 12.0;

    /** Yakında hiç hedef item bulunamazsa, elimizde satılacak bir şey varsa kaç tick sonra satışa geçilsin */
    public int noItemTimeoutTicks = 100;

    /** Otomatik satılacak/GUI'ye konulacak eşyaların id listesi */
    public List<String> sellItems = List.of(
            "minecraft:prismarine_shard",
            "minecraft:cooked_chicken",
            "minecraft:feather"
    );

    private static QuickSellConfig INSTANCE = new QuickSellConfig();

    public static QuickSellConfig get() {
        return INSTANCE;
    }

    public static void load() {
        try {
            if (Files.exists(PATH)) {
                String json = Files.readString(PATH);
                QuickSellConfig loaded = GSON.fromJson(json, QuickSellConfig.class);
                if (loaded != null) {
                    INSTANCE = loaded;
                }
            } else {
                save();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(INSTANCE));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Set<Identifier> sellItemIds() {
        Set<Identifier> set = new HashSet<>();
        for (String s : sellItems) {
            Identifier id = Identifier.tryParse(s);
            if (id != null) {
                set.add(id);
            }
        }
        return set;
    }
}
