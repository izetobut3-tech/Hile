package com.example.quicksell;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.GrindstoneScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.world.World;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * QuickSellClient - artik "satis" yapmiyor.
 *
 * Gorev:
 *  1) Etraftaki (config'de belirtilen) esyalari ve yerdeki XP toplarini topla.
 *     Ayni 100 blokluk menzil icinde spawner (mob kafesi) varsa, XP topu ile
 *     spawner'dan hangisi daha yakinsa ona gidilir. Spawner'a varilinca
 *     normal XP toplama mekanizmasi (bu ayni COLLECTING fazi) calismaya
 *     devam eder; spawnerdan cikan moblarin XP toplari yine en yakin hedef
 *     olarak degerlendirilir.
 *     Kafa/kamera (yaw) HIC donmuyor, sadece govde W/A/S/D kombinasyonuyla
 *     hedefe dogru "kayarak" gidiyor.
 *  2) XP seviyesi 33'e ulasinca: envanterdeki 4 zirh parcasindan (kask,
 *     gogusluk, pantolon, bot) Koruma IV (Protection IV) eksik olan ilkini
 *     bulup en yakin buyu masasina gider.
 *  3) Buyu masasinda: parcayi + 1 lapis koyar, karsilayabildigi en guclu
 *     secenegi secip buyuler.
 *  4) Sonuc Koruma IV degilse: parcayi alip en yakin bileme tasina
 *     (grindstone) goturur, buyuyu soker, tekrar buyu masasina doner.
 *     XP yetmezse toplamaya geri doner.
 *  5) 4 parca da Koruma IV olunca normal toplamaya devam eder.
 *
 * ROTA SISTEMI: Tum hareketler (spawner, xp toplari, buyu masasi, bileme
 * tasi) artik PathFinder ile hesaplanan blok-tabanli guvenli rota uzerinden
 * yapiliyor (bkz. advanceTowardsBlock). Cikintili bloklara/tumsek-cukurlara
 * takilmadan karadan gider; su (ve lav) tamamen bir engel sayilir, yani
 * suya hic dusmeden/girmeden karadan dolanarak gider. Rota
 * bulunamazsa (cok karmasik yapi vb.) eski duz-cizgi yontemine
 * (moveTowardsPosition) otomatik olarak dusuluyor.
 *
 * UYARI: EnchantmentScreenHandler'daki 3 secenegin (enchantmentPower /
 * enchantmentId / enchantmentLevel) tam alan adlari ve ItemEnchantmentsComponent
 * okuma sekli Minecraft surumune gore degisebilir. Bu iki nokta en riskli
 * kisimlar - derlerken hata alirsan mesaji ilet, birlikte duzeltiriz.
 */
public class QuickSellClient implements ClientModInitializer {

    public static final String MOD_ID = "quicksell";
    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

    private static final int REQUIRED_XP_LEVEL = 33;
    private static final int BLOCK_SEARCH_RADIUS = 100; // yatay 100 blok
    private static final double ARRIVE_DISTANCE = 2.3;
    // Spawner tarafina varinca cok yakina sokulmaya gerek yok (moblar orada dogacak),
    // hem cakisip sikismayi hem de mobun ustune dusmeyi engellemek icin biraz mesafe birak.
    private static final double SPAWNER_ARRIVE_DISTANCE = 5.0;
    // Spawner taramasi ~848bin blok kontrolu gerektirebilir (100 blok yaricap x 21 kat).
    // Bu yuzden her tick degil, sadece belirli araliklarla (tick bazinda) taraniyor.
    private static final int SPAWNER_RESCAN_TICKS = 100; // ~5 saniye (20 tick/saniye)
    // Spawnera giderken duvar/engel yuzunden tam mesafeye inilemiyorsa, sonsuza kadar
    // takilip "mimik" (jump+geri git) yapmasin diye bu kadar tick sonra pes edip
    // "yeterince yaklastim" sayilir ve direkt XP toplamaya gecilir.
    private static final int SPAWNER_STUCK_GIVEUP_TICKS = 100; // ~5 saniye
    // Vanilla ekranlarda oyuncu envanteri hep sabit slotlardan baslar:
    private static final int ENCHANT_PLAYER_INV_OFFSET = 2;   // 0=esya,1=lapis, 2'den itibaren envanter
    private static final int GRINDSTONE_PLAYER_INV_OFFSET = 3; // 0,1=girdi,2=cikti, 3'ten itibaren envanter

    // ---- Rota (pathfinding) ayarlari ----
    // NOT: Arama alani 100 bloga cikarildigi icin bu limit de yukseltildi.
    // Cok uzak/karmasik hedeflerde tek seferlik rota hesaplamasi birkac
    // tick suren kucuk bir donma (hitch) yapabilir; oyun akiciligi bozulursa
    // bu degeri (ve PATH_SEARCH_PADDING'i) dusurmek yeterli.
    private static final int PATH_MAX_EXPANSIONS = 20000;   // performans siniri
    private static final int PATH_RECOMPUTE_TICKS = 20;    // ~1 saniyede bir rota tazelenir
    private static final double WAYPOINT_ARRIVE_DIST = 0.6;
    private static final int PATH_STUCK_LIMIT = 30;        // bu kadar tick engelliyse rota yeniden hesaplanir

    private static KeyBinding loopToggleKey; // G: dongu baslat/durdur
    private static KeyBinding stopKey;       // H: her ne olursa olsun hemen durdur

    private enum State {
        IDLE,
        COLLECTING,
        GOTO_ENCHANT_TABLE,
        AT_ENCHANT_TABLE,
        GOTO_GRINDSTONE,
        AT_GRINDSTONE
    }

    private State state = State.IDLE;
    private boolean loopActive = false;

    private int tickCounter = 0;
    private int subStep = 0;
    private int stuckTicks = 0;
    private int unstuckTicks = 0;
    private boolean unstuckMoveForward = false;
    private boolean unstuckMoveBack = false;
    private boolean unstuckMoveLeft = false;
    private boolean unstuckMoveRight = false;
    private float lastHealth = -1f;

    private BlockPos cachedTargetBlock = null;
    private int targetInventorySlot = -1;
    private int lastInsufficientXpLevel = -1; // ayni seviyede tekrar tekrar denemeyi engeller

    // Spawner cache: pahali blok taramasini her tick yapmamak icin.
    private BlockPos cachedSpawnerBlock = null;
    private int spawnerRescanCounter = 0;
    // true olunca: spawner'a "yeterince" yaklasildi demektir, artik spawner takibi
    // tamamen kapanir ve saf XP toplama moduna gecilir (yeniden buyu/bileme donusune
    // kadar spawner hic hesaba katilmaz).
    private boolean spawnerReached = false;
    private int spawnerStuckTicks = 0;

    // ---- Rota (pathfinding) durumu ----
    private final LinkedList<BlockPos> currentPath = new LinkedList<>();
    private BlockPos pathGoalBlock = null;
    private int pathRecomputeCooldown = 0;
    private int pathStuckTicks = 0;

    @Override
    public void onInitializeClient() {
        QuickSellConfig.load();

        loopToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.quicksell.loop", GLFW.GLFW_KEY_G, CATEGORY));

        stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.quicksell.stop", GLFW.GLFW_KEY_H, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient client) {
        var player = client.player;
        if (player == null) {
            resetAll(client);
            return;
        }

        tickAutoEat(client, player);

        while (stopKey.wasPressed()) {
            stopEverything(client, "Elle durduruldu.");
        }

        while (loopToggleKey.wasPressed()) {
            if (loopActive) {
                stopEverything(client, "Dongu kapatildi.");
            } else {
                loopActive = true;
                lastInsufficientXpLevel = -1;
                lastHealth = player.getHealth();
                stuckTicks = 0;
                unstuckTicks = 0;
                clearPath();
                resumeSpawnerHunt(); // dongu basladiginda hemen 100 blok taranip spawnera gidilsin
                state = State.COLLECTING;
                player.sendMessage(Text.literal("[QuickSell] Dongu BASLADI: topla -> xp 33'te buyule -> P4 olana kadar devam")
                        .formatted(Formatting.GREEN), false);
            }
        }

        if (!loopActive) {
            return;
        }

        switch (state) {
            case COLLECTING -> tickCollecting(client, player);
            case GOTO_ENCHANT_TABLE -> tickGotoBlock(client, player, net.minecraft.block.Blocks.ENCHANTING_TABLE, State.AT_ENCHANT_TABLE);
            case AT_ENCHANT_TABLE -> tickAtEnchantTable(client, player);
            case GOTO_GRINDSTONE -> tickGotoBlock(client, player, net.minecraft.block.Blocks.GRINDSTONE, State.AT_GRINDSTONE);
            case AT_GRINDSTONE -> tickAtGrindstone(client, player);
            default -> {}
        }
    }

    // =========================================================
    // TOPLAMA FAZI (xp toplari + spawner arama)
    // =========================================================

    private void tickCollecting(MinecraftClient client, ClientPlayerEntity player) {
        var world = client.world;
        if (world == null) return;

        if (client.currentScreen != null) {
            resetMovementKeys(client);
            return;
        }

        float currentHealth = player.getHealth();
        if (lastHealth >= 0 && currentHealth < lastHealth) {
            stopEverything(client, "Hasar aldin, dongu durduruldu.");
            return;
        }
        lastHealth = currentHealth;

        if (isDangerousNearby(client, player.getBlockPos())) {
            stopEverything(client, "Tehlikeli blok (lav/ates) yakinda, dongu durduruldu.");
            return;
        }

        // XP esigine ulastik mi? Ulastiysak ve eksik P4 parca varsa buyu masasina git.
        if (player.experienceLevel >= REQUIRED_XP_LEVEL && player.experienceLevel > lastInsufficientXpLevel) {
            int slot = findArmorSlotNeedingProtection4(player);
            if (slot != -1) {
                targetInventorySlot = slot;
                cachedTargetBlock = findNearestBlock(client, player, net.minecraft.block.Blocks.ENCHANTING_TABLE, BLOCK_SEARCH_RADIUS);
                if (cachedTargetBlock != null) {
                    resetMovementKeys(client);
                    clearPath();
                    subStep = 0;
                    tickCounter = 0;
                    state = State.GOTO_ENCHANT_TABLE;
                    player.sendMessage(Text.literal("[QuickSell] XP " + player.experienceLevel + " -> buyu masasina gidiliyor.")
                            .formatted(Formatting.AQUA), false);
                    return;
                } else {
                    player.sendMessage(Text.literal("[QuickSell] Yakinda buyu masasi bulunamadi, toplamaya devam.")
                            .formatted(Formatting.YELLOW), false);
                }
            }
        }

        double radius = BLOCK_SEARCH_RADIUS; // artik esya toplama config'i degil, ayni 100 blokluk menzil

        // Sadece yerdeki XP toplarini hedef al (esya toplama kaldirildi).
        double nearestOrbDistSq = Double.MAX_VALUE;
        double orbX = 0, orbY = 0, orbZ = 0;
        boolean foundOrb = false;

        for (var orb : world.getEntitiesByClass(ExperienceOrbEntity.class,
                player.getBoundingBox().expand(radius), e -> true)) {
            double distSq = orb.squaredDistanceTo(player);
            if (distSq < nearestOrbDistSq) {
                nearestOrbDistSq = distSq;
                orbX = orb.getX();
                orbY = orb.getY();
                orbZ = orb.getZ();
                foundOrb = true;
            }
        }

        // ---- SPAWNER'A ULASILDIYSA: spawner takibi tamamen kapali, sadece XP topla. ----
        // Bu sayede spawnerin yaninda dururken her tick spawner-mesafesi ile
        // yeniden kiyaslama yapilmiyor (eskiden bu, XP toplanmamasina ve spawner
        // engelliyse takilip "mimik" hareketlere sebep oluyordu).
        if (spawnerReached) {
            if (!foundOrb) {
                resetMovementKeys(client);
                unstuckTicks = 0;
                return;
            }
            BlockPos targetGroundCheck = BlockPos.ofFloored(orbX, orbY - 1, orbZ);
            if (world.getBlockState(targetGroundCheck).isAir()
                    && world.getBlockState(targetGroundCheck.down()).isAir()) {
                return;
            }
            advanceTowardsBlock(client, player, BlockPos.ofFloored(orbX, orbY, orbZ), 0.6);
            return;
        }

        // ---- SPAWNERA HENUZ ULASILMADI: spawner cache guncelle + spawnera ilerle. ----
        updateSpawnerCache(client, player, world);

        // Spawner mesafesi (XP topuyla adil kiyaslama icin tam 3D: X/Y/Z, blok merkezine gore).
        boolean foundSpawner = cachedSpawnerBlock != null;
        double nearestSpawnerDistSq = Double.MAX_VALUE;
        if (foundSpawner) {
            double sdx = (cachedSpawnerBlock.getX() + 0.5) - player.getX();
            double sdy = (cachedSpawnerBlock.getY() + 0.5) - player.getY();
            double sdz = (cachedSpawnerBlock.getZ() + 0.5) - player.getZ();
            nearestSpawnerDistSq = sdx * sdx + sdy * sdy + sdz * sdz;
        }

        if (!foundOrb && !foundSpawner) {
            resetMovementKeys(client);
            unstuckTicks = 0;
            return;
        }

        // Iki hedeften hangisi daha yakinsa ona git. XP topu varsa ve spawnerdan
        // yakinsa (ya da spawner yoksa) direkt topu topla; degilse spawnera git.
        boolean goToOrb = foundOrb && (!foundSpawner || nearestOrbDistSq <= nearestSpawnerDistSq);

        if (goToOrb) {
            spawnerStuckTicks = 0;
            BlockPos targetGroundCheck = BlockPos.ofFloored(orbX, orbY - 1, orbZ);
            if (world.getBlockState(targetGroundCheck).isAir()
                    && world.getBlockState(targetGroundCheck.down()).isAir()) {
                return;
            }
            advanceTowardsBlock(client, player, BlockPos.ofFloored(orbX, orbY, orbZ), 0.6);
        } else {
            boolean arrivedAtSpawner = advanceTowardsBlock(client, player, cachedSpawnerBlock, SPAWNER_ARRIVE_DISTANCE);
            if (arrivedAtSpawner) {
                spawnerReached = true;
                spawnerStuckTicks = 0;
                resetMovementKeys(client);
            } else if (player.horizontalCollision) {
                spawnerStuckTicks++;
                if (spawnerStuckTicks > SPAWNER_STUCK_GIVEUP_TICKS) {
                    // Duvar/engel yuzunden tam mesafeye inilemiyor: pes edip
                    // "yeterince yakinim" say, sonsuz takilma/mimik hareketi engelle.
                    spawnerReached = true;
                    spawnerStuckTicks = 0;
                    resetMovementKeys(client);
                }
            } else {
                spawnerStuckTicks = 0;
            }
        }
    }

    /**
     * Spawner konumunu cache'ler. Pahali blok taramasi (100 blok yaricap) sadece
     * SPAWNER_RESCAN_TICKS'te bir yapilir. Aradaki tick'lerde sadece cache'lenen
     * pozisyonun hala spawner olup olmadigi (tek blok) kontrol edilir - bu ucuzdur.
     */
    private void updateSpawnerCache(MinecraftClient client, ClientPlayerEntity player, net.minecraft.world.World world) {
        if (cachedSpawnerBlock != null && !world.getBlockState(cachedSpawnerBlock).isOf(net.minecraft.block.Blocks.SPAWNER)) {
            // Spawner kirilmis/degismis olabilir, cache'i hemen bosalt.
            cachedSpawnerBlock = null;
        }

        spawnerRescanCounter--;
        if (spawnerRescanCounter <= 0) {
            spawnerRescanCounter = SPAWNER_RESCAN_TICKS;
            BlockPos found = findNearestBlock(client, player, net.minecraft.block.Blocks.SPAWNER, BLOCK_SEARCH_RADIUS);
            cachedSpawnerBlock = found; // yeni tarama sonucu (null olabilir) her zaman gecerli
        }
    }

    /** XP 33'e ulasip buyu/bileme donusu bittiginde tekrar spawner aramaya baslamak icin cagrilir. */
    private void resumeSpawnerHunt() {
        spawnerReached = false;
        cachedSpawnerBlock = null;
        spawnerRescanCounter = 0; // bir sonraki tickCollecting'de hemen tam tarama yapilsin
        spawnerStuckTicks = 0;
    }

    // =========================================================
    // BIR BLOGA GITME (buyu masasi / bileme tasi ortak fonksiyonu)
    // =========================================================

    private void tickGotoBlock(MinecraftClient client, ClientPlayerEntity player, net.minecraft.block.Block block, State onArriveState) {
        if (cachedTargetBlock == null) {
            // Hedef kayboldu (kirildi vs.), tekrar ara.
            cachedTargetBlock = findNearestBlock(client, player, block, BLOCK_SEARCH_RADIUS);
            if (cachedTargetBlock == null) {
                player.sendMessage(Text.literal("[QuickSell] Hedef blok bulunamadi, toplamaya donuluyor.")
                        .formatted(Formatting.YELLOW), false);
                resetMovementKeys(client);
                clearPath();
                resumeSpawnerHunt();
                state = State.COLLECTING;
                return;
            }
        }

        boolean arrived = advanceTowardsBlock(client, player, cachedTargetBlock, ARRIVE_DISTANCE);
        if (arrived) {
            resetMovementKeys(client);
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(cachedTargetBlock), Direction.UP, cachedTargetBlock, false);
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            subStep = 0;
            tickCounter = 0;
            state = onArriveState;
        }
    }

    // =========================================================
    // BUYU MASASI
    // =========================================================

    private void tickAtEnchantTable(MinecraftClient client, ClientPlayerEntity player) {
        if (!(client.currentScreen instanceof HandledScreen<?> handledScreen)
                || !(handledScreen.getScreenHandler() instanceof EnchantmentScreenHandler enchHandler)) {
            tickCounter++;
            if (tickCounter > 60) {
                player.sendMessage(Text.literal("[QuickSell] Buyu masasi acilmadi, toplamaya donuluyor.")
                        .formatted(Formatting.YELLOW), false);
                resumeSpawnerHunt();
                state = State.COLLECTING;
            }
            return;
        }

        switch (subStep) {
            case 0 -> {
                // Zirh parcasini slot 0'a, bir lapisi slot 1'e shift-click ile koy.
                clickSlotShift(client, enchHandler, toHandlerSlot(ENCHANT_PLAYER_INV_OFFSET, targetInventorySlot));
                int lapisSlot = findInventorySlotByPath(player, "lapis_lazuli");
                if (lapisSlot != -1) {
                    clickSlotShift(client, enchHandler, toHandlerSlot(ENCHANT_PLAYER_INV_OFFSET, lapisSlot));
                }
                subStep = 1;
                tickCounter = 0;
            }
            case 1 -> {
                tickCounter++;
                if (tickCounter < 5) return; // sunucunun secenekleri hesaplamasini bekle

                int chosen = -1;
                for (int i = 2; i >= 0; i--) {
                    int cost = enchHandler.enchantmentPower[i];
                    if (cost > 0 && player.experienceLevel >= cost) {
                        chosen = i;
                        break;
                    }
                }

                if (chosen == -1) {
                    // Parcayi geri al, toplamaya (xp biriktirmeye) don.
                    lastInsufficientXpLevel = player.experienceLevel;
                    clickSlotShift(client, enchHandler, 0);
                    client.player.closeHandledScreen();
                    client.setScreen(null);
                    player.sendMessage(Text.literal("[QuickSell] XP yetersiz, toplamaya donuluyor.")
                            .formatted(Formatting.YELLOW), false);
                    resumeSpawnerHunt();
                    state = State.COLLECTING;
                    return;
                }

                client.interactionManager.clickButton(enchHandler.syncId, chosen);
                subStep = 2;
                tickCounter = 0;
            }
            case 2 -> {
                tickCounter++;
                if (tickCounter < 4) return;

                ItemStack result = enchHandler.getSlot(0).getStack();
                int protLevel = getEnchantmentLevel(client, result, "protection");

                // Parcayi envantere geri al.
                clickSlotShift(client, enchHandler, 0);
                client.player.closeHandledScreen();
                client.setScreen(null);

                if (protLevel >= 4) {
                    player.sendMessage(Text.literal("[QuickSell] Koruma IV basarili! Toplamaya devam.")
                            .formatted(Formatting.GREEN), false);
                    lastInsufficientXpLevel = -1;
                    resumeSpawnerHunt();
                    state = State.COLLECTING;
                } else {
                    cachedTargetBlock = findNearestBlock(client, player, net.minecraft.block.Blocks.GRINDSTONE, BLOCK_SEARCH_RADIUS);
                    if (cachedTargetBlock == null) {
                        player.sendMessage(Text.literal("[QuickSell] Bileme tasi bulunamadi, toplamaya donuluyor.")
                                .formatted(Formatting.YELLOW), false);
                        resumeSpawnerHunt();
                        state = State.COLLECTING;
                    } else {
                        player.sendMessage(Text.literal("[QuickSell] P4 gelmedi, bileme tasina gidiliyor.")
                                .formatted(Formatting.GOLD), false);
                        clearPath();
                        state = State.GOTO_GRINDSTONE;
                    }
                }
            }
            default -> subStep = 0;
        }
    }

    // =========================================================
    // BILEME TASI (grindstone) - buyuyu sokup tekrar dene
    // =========================================================

    private void tickAtGrindstone(MinecraftClient client, ClientPlayerEntity player) {
        if (!(client.currentScreen instanceof HandledScreen<?> handledScreen)
                || !(handledScreen.getScreenHandler() instanceof GrindstoneScreenHandler grindHandler)) {
            tickCounter++;
            if (tickCounter > 60) {
                player.sendMessage(Text.literal("[QuickSell] Bileme tasi acilmadi, toplamaya donuluyor.")
                        .formatted(Formatting.YELLOW), false);
                resumeSpawnerHunt();
                state = State.COLLECTING;
            }
            return;
        }

        switch (subStep) {
            case 0 -> {
                int slot = findArmorSlotNeedingProtection4(player);
                if (slot == -1) slot = targetInventorySlot; // guvenlik icin yedek
                clickSlotShift(client, grindHandler, toHandlerSlot(GRINDSTONE_PLAYER_INV_OFFSET, slot));
                subStep = 1;
                tickCounter = 0;
            }
            case 1 -> {
                tickCounter++;
                if (tickCounter < 5) return;
                // Sonuc slotu genelde index 2'dir (0 ve 1 girdi, 2 cikti).
                clickSlotShift(client, grindHandler, 2);
                client.player.closeHandledScreen();
                client.setScreen(null);

                cachedTargetBlock = findNearestBlock(client, player, net.minecraft.block.Blocks.ENCHANTING_TABLE, BLOCK_SEARCH_RADIUS);
                if (cachedTargetBlock == null) {
                    player.sendMessage(Text.literal("[QuickSell] Buyu masasi bulunamadi, toplamaya donuluyor.")
                            .formatted(Formatting.YELLOW), false);
                    resumeSpawnerHunt();
                    state = State.COLLECTING;
                } else {
                    clearPath();
                    state = State.GOTO_ENCHANT_TABLE;
                }
            }
            default -> subStep = 0;
        }
    }

    // =========================================================
    // ROTA (PATHFINDING) ILE HAREKET
    // =========================================================

    /**
     * Hedef bloga (spawner, xp topu, buyu masasi, bileme tasi) PathFinder ile
     * hesaplanan guvenli rota uzerinden ilerler. Cikintili bloklara,
     * tumsek/cukurlara takilmadan, suya (ve lava) hic girmeden karadan gider.
     *
     * Rota bulunamazsa (cok karmasik yapi, cok uzak hedef vb.) otomatik olarak
     * eski duz-cizgi yontemine (moveTowardsPosition) duser.
     *
     * @return hedefe (arriveDistance icine) ulasildiysa true.
     */
    private boolean advanceTowardsBlock(MinecraftClient client, ClientPlayerEntity player, BlockPos goalBlock, double arriveDistance) {
        if (goalBlock == null) return false;

        double cdx = (goalBlock.getX() + 0.5) - player.getX();
        double cdz = (goalBlock.getZ() + 0.5) - player.getZ();
        if (cdx * cdx + cdz * cdz <= arriveDistance * arriveDistance) {
            resetMovementKeys(client);
            clearPath();
            return true;
        }

        var world = client.world;
        BlockPos playerPos = player.getBlockPos();

        boolean goalChanged = pathGoalBlock == null || !pathGoalBlock.equals(goalBlock);
        boolean needsRecompute = currentPath.isEmpty() || goalChanged || pathRecomputeCooldown <= 0;

        if (needsRecompute && world != null) {
            pathRecomputeCooldown = PATH_RECOMPUTE_TICKS;
            pathGoalBlock = goalBlock;

            BlockPos standTarget = PathFinder.findStandableNear(world, goalBlock, playerPos, 2);
            if (standTarget == null) {
                standTarget = PathFinder.findStandableNear(world, goalBlock, playerPos, 3);
            }
            if (standTarget == null) standTarget = goalBlock;

            List<BlockPos> found = PathFinder.findPath(world, playerPos, standTarget, PATH_MAX_EXPANSIONS);
            currentPath.clear();
            currentPath.addAll(found);
            pathStuckTicks = 0;
        } else {
            pathRecomputeCooldown--;
        }

        if (currentPath.isEmpty()) {
            // Rota bulunamadi: eski duz-cizgi yontemine dus (guvenlik agi).
            return moveTowardsPosition(client, player, goalBlock.getX() + 0.5, goalBlock.getZ() + 0.5, arriveDistance);
        }

        BlockPos waypoint = currentPath.getFirst();
        double wdx = (waypoint.getX() + 0.5) - player.getX();
        double wdz = (waypoint.getZ() + 0.5) - player.getZ();
        double wDistSq = wdx * wdx + wdz * wdz;
        double wDyAbs = Math.abs(waypoint.getY() - player.getY());

        if (wDistSq <= WAYPOINT_ARRIVE_DIST * WAYPOINT_ARRIVE_DIST && wDyAbs < 1.2) {
            currentPath.removeFirst();
            if (currentPath.isEmpty()) {
                resetMovementKeys(client);
                return false; // bir sonraki tick genel mesafe kontrolu "vardi" diyecek
            }
            waypoint = currentPath.getFirst();
            wdx = (waypoint.getX() + 0.5) - player.getX();
            wdz = (waypoint.getZ() + 0.5) - player.getZ();
        }

        float targetAngle = (float) (MathHelper.atan2(wdz, wdx) * (180.0 / Math.PI)) - 90f;
        float diff = MathHelper.wrapDegrees(targetAngle - player.getYaw());

        boolean moveBack = Math.abs(diff) > 100f;
        boolean moveForward = !moveBack;
        boolean moveRight = diff > 10f;
        boolean moveLeft = diff < -10f;

        client.options.forwardKey.setPressed(moveForward);
        client.options.backKey.setPressed(moveBack);
        client.options.leftKey.setPressed(moveLeft);
        client.options.rightKey.setPressed(moveRight);
        client.options.sneakKey.setPressed(false);

        boolean needJump = waypoint.getY() > playerPos.getY();
        client.options.jumpKey.setPressed(needJump || player.horizontalCollision);

        if (player.horizontalCollision) {
            pathStuckTicks++;
            if (pathStuckTicks > PATH_STUCK_LIMIT) {
                // Rota gecerliligini yitirmis olabilir (yeni yerlesen blok vb.):
                // zorla yeniden hesapla.
                clearPath();
            }
        } else {
            pathStuckTicks = 0;
        }

        return false;
    }

    private void clearPath() {
        currentPath.clear();
        pathGoalBlock = null;
        pathRecomputeCooldown = 0;
        pathStuckTicks = 0;
    }

    // =========================================================
    // ORTAK HAREKET FONKSIYONU (kafa sabit, govde kayarak gider) - GUVENLIK AGI
    // Rota bulunamadiginda advanceTowardsBlock tarafindan yedek olarak kullanilir.
    // =========================================================

    /** Hedefe ulasildiysa true doner. */
    private boolean moveTowardsPosition(MinecraftClient client, ClientPlayerEntity player, double targetX, double targetZ, double arriveDistance) {
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double distSq = dx * dx + dz * dz;
        if (distSq <= arriveDistance * arriveDistance) {
            resetMovementKeys(client);
            return true;
        }

        if (unstuckTicks > 0) {
            unstuckTicks--;
            client.options.forwardKey.setPressed(unstuckMoveForward);
            client.options.backKey.setPressed(unstuckMoveBack);
            client.options.leftKey.setPressed(unstuckMoveLeft);
            client.options.rightKey.setPressed(unstuckMoveRight);
            client.options.jumpKey.setPressed(true);
            client.options.sneakKey.setPressed(false);
            return false;
        }

        float targetAngle = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
        float diff = MathHelper.wrapDegrees(targetAngle - player.getYaw());

        boolean moveBack = Math.abs(diff) > 100f;
        boolean moveForward = !moveBack;
        boolean moveRight = diff > 10f;
        boolean moveLeft = diff < -10f;

        client.options.forwardKey.setPressed(moveForward);
        client.options.backKey.setPressed(moveBack);
        client.options.leftKey.setPressed(moveLeft);
        client.options.rightKey.setPressed(moveRight);
        client.options.sneakKey.setPressed(false);

        if (player.horizontalCollision) {
            stuckTicks++;
            client.options.jumpKey.setPressed(true);
            if (stuckTicks > 20) {
                stuckTicks = 0;
                unstuckTicks = 12;
                unstuckMoveForward = moveBack;
                unstuckMoveBack = moveForward;
                unstuckMoveLeft = moveRight;
                unstuckMoveRight = moveLeft;
            }
        } else {
            stuckTicks = 0;
            client.options.jumpKey.setPressed(false);
        }
        return false;
    }

    // =========================================================
    // YARDIMCI - envanter / esya kontrolleri
    // =========================================================

    private static final int HUNGER_EAT_THRESHOLD = 14; // 10 ikondan 3'u eksilince (7 ikon = 14/20)

    /** Aclik 3 kare (17/20 ve alti) dusunce sol eldeki (offhand) yiyecegi otomatik yer. */
    private void tickAutoEat(MinecraftClient client, ClientPlayerEntity player) {
        if (player.isUsingItem()) return; // zaten bir sey yiyor/kullaniyor, tekrar tetikleme

        int food = player.getHungerManager().getFoodLevel();
        if (food > HUNGER_EAT_THRESHOLD) return;

        ItemStack offhand = player.getOffHandStack();
        if (offhand.isEmpty()) return;
        if (offhand.get(DataComponentTypes.FOOD) == null) return; // yiyecek degilse dokunma

        client.interactionManager.interactItem(player, Hand.OFF_HAND);
    }

    private void clickSlotShift(MinecraftClient client, ScreenHandler handler, int slotId) {
        client.interactionManager.clickSlot(handler.syncId, slotId, 0, SlotActionType.QUICK_MOVE, client.player);
    }

    /**
     * PlayerInventory index (0-35, burada 0-8 hotbar, 9-35 ana depo) ile
     * ekrandaki (screen handler) slot numarasi AYNI SIRADA DEGIL: vanilla
     * ekranlarda once ana depo (9-35), sonra hotbar (0-8) sirayla eklenir.
     * Bu yuzden dogrudan "base + invIndex" yapmak yanlis slota tiklar.
     */
    private int toHandlerSlot(int base, int invIndex) {
        if (invIndex >= 9) {
            return base + (invIndex - 9);
        } else {
            return base + 27 + invIndex;
        }
    }

    private int findInventorySlotByPath(ClientPlayerEntity player, String path) {
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (Registries.ITEM.getId(stack.getItem()).getPath().equals(path)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isArmorPiece(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        return path.endsWith("_helmet") || path.endsWith("_chestplate")
                || path.endsWith("_leggings") || path.endsWith("_boots");
    }

    /** Envanterde Koruma IV eksik olan ilk zirh parcasinin slot indexini dondurur, yoksa -1. */
    private int findArmorSlotNeedingProtection4(ClientPlayerEntity player) {
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!isArmorPiece(stack)) continue;
            if (getEnchantmentLevel(MinecraftClient.getInstance(), stack, "protection") < 4) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Verilen esyada, verilen "enchantmentPath" (ornegin "protection") buyusunun
     * seviyesini dondurur, yoksa 0.
     *
     * NOT: Bu metot Minecraft'in "data component" sistemine (1.20.5+) gore yazildi.
     * Eger derlerken ItemEnchantmentsComponent / getEnchantments() bulunamazsa,
     * bu metodun icini projendeki gercek API'ye gore guncellememiz gerekecek.
     */
    private int getEnchantmentLevel(MinecraftClient client, ItemStack stack, String enchantmentPath) {
        if (stack.isEmpty()) return 0;
        ItemEnchantmentsComponent enchants = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchants == null) return 0;
        for (RegistryEntry<Enchantment> entry : enchants.getEnchantments()) {
            String path = entry.getKey().map(RegistryKey::getValue).map(Identifier::getPath).orElse("");
            if (path.equals(enchantmentPath)) {
                return enchants.getLevel(entry);
            }
        }
        return 0;
    }

    private BlockPos findNearestBlock(MinecraftClient client, ClientPlayerEntity player, net.minecraft.block.Block block, int radius) {
        var world = client.world;
        if (world == null) return null;
        BlockPos center = player.getBlockPos();
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -10, -radius), center.add(radius, 10, radius))) {
            if (world.getBlockState(pos).isOf(block)) {
                double ddx = pos.getX() + 0.5 - player.getX();
                double ddy = pos.getY() + 0.5 - player.getY();
                double ddz = pos.getZ() + 0.5 - player.getZ();
                double d = ddx * ddx + ddy * ddy + ddz * ddz;
                if (d < nearestDistSq) {
                    nearestDistSq = d;
                    nearest = pos.toImmutable();
                }
            }
        }
        return nearest;
    }

    private boolean isDangerousNearby(MinecraftClient client, BlockPos center) {
        var world = client.world;
        if (world == null) return false;
        for (BlockPos pos : BlockPos.iterate(center.add(-1, -1, -1), center.add(1, 1, 1))) {
            var state = world.getBlockState(pos);
            if (!state.getFluidState().isEmpty() && state.getFluidState().getFluid().matchesType(Fluids.LAVA)) {
                return true;
            }
            if (state.isOf(net.minecraft.block.Blocks.FIRE) || state.isOf(net.minecraft.block.Blocks.LAVA)) {
                return true;
            }
        }
        return false;
    }

    // =========================================================
    // GENEL YARDIMCI
    // =========================================================

    private void resetMovementKeys(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
    }

    private void stopEverything(MinecraftClient client, String reason) {
        loopActive = false;
        state = State.IDLE;
        resetMovementKeys(client);
        clearPath();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[QuickSell] " + reason).formatted(Formatting.RED), false);
        }
    }

    private void resetAll(MinecraftClient client) {
        loopActive = false;
        state = State.IDLE;
        clearPath();
    }

    // =========================================================
    // ROTA BULUCU (PathFinder) - tek dosyada kalsin diye buraya tasindi
    // =========================================================
    private static final class PathFinder {

        private PathFinder() {
        }

        private static final int MAX_JUMP_UP = 1;
        private static final int MAX_FALL_DOWN = 3;
        // Rota, hedefe (spawner/xp/buyu masasi/bileme tasi - hepsi) gore 100 blok
        // menzile kadar genis engellerden/duvarlardan dolanabilsin diye ayni
        // BLOCK_SEARCH_RADIUS ile hizalandi. Genis alan = daha fazla dugum = daha
        // pahali arama; performans sikinti cikarirsa bu iki sabiti dusurmek yeterli.
        private static final int PATH_SEARCH_PADDING = 100;
        private static final int PATH_SEARCH_Y_PADDING = 24;

        private static final int[][] NEIGHBOR_DIRS = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };

        /**
         * start'tan goal'e A* ile rota hesaplar. Basarili olursa start haric,
         * goal dahil sirali BlockPos listesi doner. Bulunamazsa bos liste doner.
         *
         * @param maxExpansions performans siniri: bu kadar dugum acildiktan sonra
         *                       hala hedefe ulasilamadiysa aramadan vazgecilir.
         */
        public static List<BlockPos> findPath(World world, BlockPos start, BlockPos goal, int maxExpansions) {
            if (world == null || start == null || goal == null) return List.of();

            Node startNode = new Node(start.toImmutable(), 0.0, heuristic(start, goal), null);

            PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
            Map<BlockPos, Double> bestG = new HashMap<>();
            open.add(startNode);
            bestG.put(startNode.pos, 0.0);

            int minX = Math.min(start.getX(), goal.getX()) - PATH_SEARCH_PADDING;
            int maxX = Math.max(start.getX(), goal.getX()) + PATH_SEARCH_PADDING;
            int minZ = Math.min(start.getZ(), goal.getZ()) - PATH_SEARCH_PADDING;
            int maxZ = Math.max(start.getZ(), goal.getZ()) + PATH_SEARCH_PADDING;
            int minY = Math.min(start.getY(), goal.getY()) - PATH_SEARCH_Y_PADDING;
            int maxY = Math.max(start.getY(), goal.getY()) + PATH_SEARCH_Y_PADDING;

            Node goalNode = null;
            int expansions = 0;

            while (!open.isEmpty() && expansions < maxExpansions) {
                Node current = open.poll();
                expansions++;

                if (current.pos.getX() == goal.getX() && current.pos.getY() == goal.getY() && current.pos.getZ() == goal.getZ()) {
                    goalNode = current;
                    break;
                }

                for (int[] dir : NEIGHBOR_DIRS) {
                    int dx = dir[0];
                    int dz = dir[1];
                    boolean diagonal = dx != 0 && dz != 0;

                    if (diagonal) {
                        BlockPos side1 = current.pos.add(dx, 0, 0);
                        BlockPos side2 = current.pos.add(0, 0, dz);
                        if (!isOpenColumn(world, side1) || !isOpenColumn(world, side2)) {
                            continue;
                        }
                    }

                    for (int dy = MAX_JUMP_UP; dy >= -MAX_FALL_DOWN; dy--) {
                        BlockPos cand = current.pos.add(dx, dy, dz);
                        if (cand.getX() < minX || cand.getX() > maxX || cand.getZ() < minZ || cand.getZ() > maxZ
                                || cand.getY() < minY || cand.getY() > maxY) {
                            continue;
                        }

                        if (!isOpenColumn(world, cand)) continue;
                        if (isHazard(world, cand) || isHazard(world, cand.up()) || isHazard(world, cand.down())) continue;
                        if (!hasSupport(world, cand)) continue;
                        if (dy > 0 && !isPassable(world, current.pos.up().up())) continue; // ziplarken bas cikintiya carpmasin

                        double stepCost = diagonal ? 1.4 : 1.0;
                        if (dy != 0) stepCost += 0.15 * Math.abs(dy);

                        double newG = current.g + stepCost;
                        Double existing = bestG.get(cand);
                        if (existing == null || newG < existing - 1e-6) {
                            bestG.put(cand, newG);
                            open.add(new Node(cand, newG, newG + heuristic(cand, goal), current));
                        }
                        break; // bu yon icin ilk gecerli yukseklik yeterli
                    }
                }
            }

            if (goalNode == null) return List.of();

            LinkedList<BlockPos> path = new LinkedList<>();
            Node n = goalNode;
            while (n.parent != null) {
                path.addFirst(n.pos);
                n = n.parent;
            }
            return path;
        }

        /**
         * Hedef blogun (buyu masasi, bileme tasi, spawner vb.) yaninda durulabilecek,
         * oyuncunun mevcut konumuna en yakin bos noktayi bulur. Hedefin kendisi
         * genelde katidir (spawner, masa vb.), bu yuzden direkt hedefe rota
         * cizmek yerine yanindaki bos noktaya rota cizilir.
         */
        public static BlockPos findStandableNear(World world, BlockPos target, BlockPos preferFrom, int searchRadius) {
            if (world == null) return null;
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;

            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos cand = target.add(dx, dy, dz);
                        if (!isOpenColumn(world, cand)) continue;
                        if (isHazard(world, cand) || isHazard(world, cand.up())) continue;
                        if (!hasSupport(world, cand)) continue;

                        double ddx = cand.getX() - preferFrom.getX();
                        double ddy = cand.getY() - preferFrom.getY();
                        double ddz = cand.getZ() - preferFrom.getZ();
                        double d = ddx * ddx + ddy * ddy + ddz * ddz;
                        if (d < bestDist) {
                            bestDist = d;
                            best = cand.toImmutable();
                        }
                    }
                }
            }
            return best;
        }

        // =========================================================
        // Blok kontrolleri
        // =========================================================

        /** Ayak seviyesi + bas seviyesi (2 blok) bos mu (oyuncu sigar mi)? */
        private static boolean isOpenColumn(World world, BlockPos feet) {
            return isPassable(world, feet) && isPassable(world, feet.up());
        }

        private static boolean isPassable(World world, BlockPos pos) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) return true;
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) return false; // su/lav: artik "gecilebilir" DEGIL, tamamen kacinilir
            return state.getCollisionShape(world, pos).isEmpty();
        }

        private static boolean isHazard(World world, BlockPos pos) {
            BlockState state = world.getBlockState(pos);
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) return true; // su da lav da hazard: rota bu bloklara hic girmez
            return state.isOf(net.minecraft.block.Blocks.FIRE) || state.isOf(net.minecraft.block.Blocks.LAVA) || state.isOf(net.minecraft.block.Blocks.MAGMA_BLOCK)
                    || state.isOf(net.minecraft.block.Blocks.SOUL_FIRE) || state.isOf(net.minecraft.block.Blocks.CACTUS);
        }

        private static boolean hasSupport(World world, BlockPos feet) {
            // Su/lav uzerinde "yuzerek" durmaya artik izin yok - sadece kati zemin gecerli.
            return !world.getBlockState(feet.down()).getCollisionShape(world, feet.down()).isEmpty();
        }

        private static double heuristic(BlockPos a, BlockPos b) {
            double dx = Math.abs(a.getX() - b.getX());
            double dz = Math.abs(a.getZ() - b.getZ());
            double dy = Math.abs(a.getY() - b.getY());
            double diag = Math.min(dx, dz);
            double straight = Math.max(dx, dz) - diag;
            return diag * 1.4 + straight + dy * 0.5;
        }

        private static final class Node {
            final BlockPos pos;
            final double g;
            final double f;
            final Node parent;

            Node(BlockPos pos, double g, double f, Node parent) {
                this.pos = pos;
                this.g = g;
                this.f = f;
                this.parent = parent;
            }
        }
    }

}
