package com.example.quicksell;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.ItemEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class QuickSellClient implements ClientModInitializer {

    public static final String MOD_ID = "quicksell";
    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

    private static KeyBinding loopToggleKey; // G: dongu baslat/durdur
    private static KeyBinding stopKey;       // H: her ne olursa olsun hemen durdur

    private enum State { IDLE, COLLECTING, SELL_WAITING_FOR_GUI, SELL_CLICKING, SELL_CLOSING }

    private State state = State.IDLE;
    private boolean loopActive = false;

    private int tickCounter = 0;
    private int noItemTicks = 0;
    private int stuckTicks = 0;
    private float lastHealth = -1f;

    private int clickIndex = 0;
    private final List<Integer> targetSlots = new ArrayList<>();

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

        while (stopKey.wasPressed()) {
            stopEverything(client, "Elle durduruldu.");
        }

        while (loopToggleKey.wasPressed()) {
            if (loopActive) {
                stopEverything(client, "Dongu kapatildi.");
            } else {
                loopActive = true;
                lastHealth = player.getHealth();
                noItemTicks = 0;
                stuckTicks = 0;
                state = State.COLLECTING;
                player.sendMessage(Text.literal("[QuickSell] Dongu BASLADI: topla -> envanter dolunca sat -> tekrarla")
                        .formatted(Formatting.GREEN), false);
            }
        }

        if (!loopActive) {
            return;
        }

        switch (state) {
            case COLLECTING -> tickCollecting(client, player);
            case SELL_WAITING_FOR_GUI -> handleWaitingForGui(client);
            case SELL_CLICKING -> handleClicking(client);
            case SELL_CLOSING -> handleClosing(client);
            default -> {}
        }
    }

    // =========================================================
    // TOPLAMA FAZI
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

        if (isInventoryFull(player)) {
            resetMovementKeys(client);
            beginSell(client, player);
            return;
        }

        double radius = QuickSellConfig.get().collectRadius;
        var wantedIds = QuickSellConfig.get().sellItemIds();

        ItemEntity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (var entity : world.getEntitiesByClass(ItemEntity.class,
                player.getBoundingBox().expand(radius), e -> true)) {
            ItemStack stack = entity.getStack();
            Identifier id = Registries.ITEM.getId(stack.getItem());
            if (!wantedIds.contains(id)) continue;
            double distSq = entity.squaredDistanceTo(player);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = entity;
            }
        }

        if (nearest == null) {
            resetMovementKeys(client);
            noItemTicks++;
            if (noItemTicks > QuickSellConfig.get().noItemTimeoutTicks && hasAnySellableItem(player)) {
                beginSell(client, player);
            }
            return;
        }
        noItemTicks = 0;

        BlockPos targetGroundCheck = BlockPos.ofFloored(nearest.getX(), nearest.getY() - 1, nearest.getZ());
        if (world.getBlockState(targetGroundCheck).isAir()
                && world.getBlockState(targetGroundCheck.down()).isAir()) {
            return;
        }

        // --- Kafa/govde yonunu (yaw) DEGISTIRMIYORUZ ---
        // Karakter nereye bakiyorsa baksin; sadece hareket tuslariyla
        // govde esyaya dogru "kayarak" (strafe) gidiyor.
        double dx = nearest.getX() - player.getX();
        double dz = nearest.getZ() - player.getZ();
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
            if (stuckTicks > 60) {
                stopEverything(client, "Bir engelde takildim, dongu durduruldu.");
                return;
            }
        } else {
            stuckTicks = 0;
            client.options.jumpKey.setPressed(false);
        }
    }

    private boolean isInventoryFull(ClientPlayerEntity player) {
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getStack(i).isEmpty()) return false;
        }
        return true;
    }

    private boolean hasAnySellableItem(ClientPlayerEntity player) {
        var wanted = QuickSellConfig.get().sellItemIds();
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (wanted.contains(Registries.ITEM.getId(stack.getItem()))) return true;
        }
        return false;
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

    private float smoothAngle(float current, float target, float maxStep) {
        float diff = MathHelper.wrapDegrees(target - current);
        diff = MathHelper.clamp(diff, -maxStep, maxStep);
        return current + diff;
    }

    // =========================================================
    // SATIS FAZI (/sellgui + shift-click)
    // =========================================================

    private void beginSell(MinecraftClient client, ClientPlayerEntity player) {
        player.networkHandler.sendChatCommand(QuickSellConfig.get().sellCommand);
        state = State.SELL_WAITING_FOR_GUI;
        tickCounter = 0;
    }

    private void handleWaitingForGui(MinecraftClient client) {
        tickCounter++;
        if (client.currentScreen instanceof HandledScreen<?> handledScreen) {
            String title = handledScreen.getTitle().getString();
            if (title.contains(QuickSellConfig.get().guiTitleContains)) {
                if (tickCounter >= QuickSellConfig.get().openDelayTicks) {
                    prepareClicks(client, handledScreen.getScreenHandler());
                    state = State.SELL_CLICKING;
                    tickCounter = 0;
                }
                return;
            }
        }
        if (tickCounter > 100) {
            stopEverything(client, "sellgui menusu acilmadi, dongu durduruldu.");
        }
    }

    private void prepareClicks(MinecraftClient client, ScreenHandler handler) {
        targetSlots.clear();
        clickIndex = 0;
        var wanted = QuickSellConfig.get().sellItemIds();
        for (Slot slot : handler.slots) {
            if (!slot.inventory.equals(client.player.getInventory())) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            Identifier id = Registries.ITEM.getId(stack.getItem());
            if (wanted.contains(id)) {
                targetSlots.add(slot.id);
            }
        }
    }

    private void handleClicking(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> handledScreen)) {
            state = State.COLLECTING;
            return;
        }
        tickCounter++;
        if (tickCounter < QuickSellConfig.get().clickDelayTicks) return;
        tickCounter = 0;

        if (clickIndex >= targetSlots.size()) {
            state = State.SELL_CLOSING;
            return;
        }

        int slotId = targetSlots.get(clickIndex);
        ScreenHandler handler = handledScreen.getScreenHandler();
        client.interactionManager.clickSlot(handler.syncId, slotId, 0, SlotActionType.QUICK_MOVE, client.player);
        clickIndex++;
    }

    private void handleClosing(MinecraftClient client) {
        tickCounter++;
        if (tickCounter < QuickSellConfig.get().closeDelayTicks) return;
        if (client.player != null) {
            client.player.closeHandledScreen();
        }
        client.setScreen(null);

        if (loopActive) {
            noItemTicks = 0;
            stuckTicks = 0;
            lastHealth = client.player != null ? client.player.getHealth() : -1f;
            state = State.COLLECTING;
        } else {
            state = State.IDLE;
        }
    }

    // =========================================================
    // YARDIMCI
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
