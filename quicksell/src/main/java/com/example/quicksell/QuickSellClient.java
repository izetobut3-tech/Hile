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
import java.util.List;

/**
 * QuickSellClient - artik "satis" yapmiyor.
 *
 * Yeni gorev:
 *  1) Etraftaki (config'de belirtilen) esyalari ve yerdeki XP toplarini topla.
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
    // Vanilla ekranlarda oyuncu envanteri hep sabit slotlardan baslar:
    private static final int ENCHANT_PLAYER_INV_OFFSET = 2;   // 0=esya,1=lapis, 2'den itibaren envanter
    private static final int GRINDSTONE_PLAYER_INV_OFFSET = 3; // 0,1=girdi,2=cikti, 3'ten itibaren envanter

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
    // TOPLAMA FAZI (esya + xp toplari)
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

        double nearestDistSq = Double.MAX_VALUE;
        double nearestX = 0, nearestZ = 0, nearestY = 0;
        boolean foundTarget = false;

        // Sadece yerdeki XP toplarini hedef al (esya toplama kaldirildi).
        for (var orb : world.getEntitiesByClass(ExperienceOrbEntity.class,
                player.getBoundingBox().expand(radius), e -> true)) {
            double distSq = orb.squaredDistanceTo(player);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearestX = orb.getX();
                nearestY = orb.getY();
                nearestZ = orb.getZ();
                foundTarget = true;
            }
        }

        if (!foundTarget) {
            resetMovementKeys(client);
            unstuckTicks = 0;
            return;
        }

        BlockPos targetGroundCheck = BlockPos.ofFloored(nearestX, nearestY - 1, nearestZ);
        if (world.getBlockState(targetGroundCheck).isAir()
                && world.getBlockState(targetGroundCheck.down()).isAir()) {
            return;
        }

        moveTowardsPosition(client, player, nearestX, nearestZ, 0.6);
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
                state = State.COLLECTING;
                return;
            }
        }

        double cx = cachedTargetBlock.getX() + 0.5;
        double cz = cachedTargetBlock.getZ() + 0.5;
        boolean arrived = moveTowardsPosition(client, player, cx, cz, ARRIVE_DISTANCE);
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
                    state = State.COLLECTING;
                } else {
                    cachedTargetBlock = findNearestBlock(client, player, net.minecraft.block.Blocks.GRINDSTONE, BLOCK_SEARCH_RADIUS);
                    if (cachedTargetBlock == null) {
                        player.sendMessage(Text.literal("[QuickSell] Bileme tasi bulunamadi, toplamaya donuluyor.")
                                .formatted(Formatting.YELLOW), false);
                        state = State.COLLECTING;
                    } else {
                        player.sendMessage(Text.literal("[QuickSell] P4 gelmedi, bileme tasina gidiliyor.")
                                .formatted(Formatting.GOLD), false);
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
                    state = State.COLLECTING;
                } else {
                    state = State.GOTO_ENCHANT_TABLE;
                }
            }
            default -> subStep = 0;
        }
    }

    // =========================================================
    // ORTAK HAREKET FONKSIYONU (kafa sabit, govde kayarak gider)
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
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[QuickSell] " + reason).formatted(Formatting.RED), false);
        }
    }

    private void resetAll(MinecraftClient client) {
        loopActive = false;
        state = State.IDLE;
    }
}
