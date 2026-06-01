package com.tot.addon.modules;

import com.tot.addon.Categories;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalNear;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Collections;
import java.util.List;

public class SpawnerSaver extends Module {

    // ─── Setting Groups ───────────────────────────────────────────────────────

    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");
    private final SettingGroup sgBehavior  = settings.createGroup("Behavior");

    // ─── General ─────────────────────────────────────────────────────────────

    private final Setting<Double> detectionRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("detection-range")
        .description("Radius (in blocks) to watch for non-whitelisted players.")
        .defaultValue(16.0).min(4.0).sliderMax(64.0)
        .build());

    private final Setting<Double> mineRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("mine-range")
        .description("Max distance to mine a spawner.")
        .defaultValue(4.5).min(1.0).sliderMax(6.0)
        .build());

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("ender-chest-range")
        .description("Max distance to interact with an ender chest.")
        .defaultValue(4.5).min(1.0).sliderMax(6.0)
        .build());

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show chat status messages.")
        .defaultValue(true)
        .build());

    // ─── Whitelist ────────────────────────────────────────────────────────────

    private final Setting<List<String>> whitelist = sgWhitelist.add(new StringListSetting.Builder()
        .name("whitelist")
        .description("Players that will NOT trigger this module.")
        .defaultValue(Collections.emptyList())
        .build());

    // ─── Behavior ─────────────────────────────────────────────────────────────

    private final Setting<Boolean> autoNavigate = sgBehavior.add(new BoolSetting.Builder()
        .name("auto-navigate")
        .description("Use Baritone to pathfind to spawners and ender chests.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> mineDelay = sgBehavior.add(new IntSetting.Builder()
        .name("mine-delay")
        .description("Extra ticks to hold left-click on each spawner.")
        .defaultValue(0).min(0).sliderMax(20)
        .build());

    private final Setting<Boolean> requireSilkTouch = sgBehavior.add(new BoolSetting.Builder()
        .name("require-silk-touch")
        .description("Only mine when a Silk Touch tool is equipped.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoEquipPickaxe = sgBehavior.add(new BoolSetting.Builder()
        .name("auto-equip-pickaxe")
        .description("Automatically switch to a Silk Touch pickaxe from inventory.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> pauseWhenFull = sgBehavior.add(new BoolSetting.Builder()
        .name("pause-when-full")
        .description("Stop mining and deposit when inventory is full.")
        .defaultValue(true)
        .build());

    // ─── Internal State ───────────────────────────────────────────────────────

    private enum State { IDLE, MINING, DEPOSITING, NAVIGATING_SPAWNER, NAVIGATING_CHEST }

    private State    state         = State.IDLE;
    private BlockPos targetSpawner = null;
    private BlockPos targetChest   = null;
    private int      mineTimer     = 0;
    private boolean  sneaking      = false;

    // ─── Constructor ──────────────────────────────────────────────────────────

    public SpawnerSaver() {
        super(Categories.ToT, "Spawner-Saver",
            "Mines spawners when a non-whitelisted player is detected, then stores them in an ender chest.");
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        state         = State.IDLE;
        targetSpawner = null;
        targetChest   = null;
        mineTimer     = 0;
        sneaking      = false;
    }

    @Override
    public void onDeactivate() {
        stopSneak();
        stopBaritone();
    }

    // ─── Main Tick ────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (!threatDetected()) {
            if (state != State.IDLE) {
                stopSneak();
                stopBaritone();
                state = State.IDLE;
                notify("No threat – standing by.");
            }
            return;
        }

        if (pauseWhenFull.get() && inventoryFull() && state == State.MINING) {
            stopSneak();
            state = State.DEPOSITING;
            notify("Inventory full – depositing spawners.");
            return;
        }

        switch (state) {
            case IDLE                -> tickIdle();
            case MINING              -> tickMining();
            case DEPOSITING          -> tickDepositing();
            case NAVIGATING_SPAWNER  -> tickNavSpawner();
            case NAVIGATING_CHEST    -> tickNavChest();
        }
    }

    // ─── State: IDLE ──────────────────────────────────────────────────────────

    private void tickIdle() {
        targetSpawner = nearestSpawner();
        if (targetSpawner == null) {
            notify("Threat detected but no spawners found nearby.");
            return;
        }

        if (distTo(targetSpawner) > mineRange.get()) {
            if (autoNavigate.get()) {
                navigateTo(targetSpawner);
                state = State.NAVIGATING_SPAWNER;
                notify("Navigating to spawner @ " + targetSpawner.toShortString());
            }
        } else {
            if (equipSilkTool()) {
                startSneak();
                mineTimer = 0;
                state = State.MINING;
                notify("Mining spawner @ " + targetSpawner.toShortString());
            } else {
                notify("No Silk Touch tool found – disabling.");
                toggle();
            }
        }
    }

    // ─── State: MINING ────────────────────────────────────────────────────────

    private void tickMining() {
        if (targetSpawner == null) { state = State.IDLE; return; }

        if (!isSpawner(targetSpawner)) {
            stopSneak();
            notify("Spawner collected.");
            targetSpawner = null;
            state = State.IDLE;
            return;
        }

        if (distTo(targetSpawner) > mineRange.get()) {
            if (autoNavigate.get()) {
                stopSneak();
                navigateTo(targetSpawner);
                state = State.NAVIGATING_SPAWNER;
            }
            return;
        }

        if (mineTimer > 0) { mineTimer--; return; }
        mineTimer = mineDelay.get();

        startSneak();
        mc.interactionManager.attackBlock(targetSpawner, facingToward(targetSpawner));
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    // ─── State: DEPOSITING ────────────────────────────────────────────────────

    private void tickDepositing() {
        targetChest = nearestEnderChest();

        if (targetChest == null) {
            notify("No ender chest found – resuming.");
            state = State.IDLE;
            return;
        }

        if (distTo(targetChest) > placeRange.get()) {
            if (autoNavigate.get()) {
                navigateTo(targetChest);
                state = State.NAVIGATING_CHEST;
                notify("Navigating to ender chest @ " + targetChest.toShortString());
            }
            return;
        }

        mc.interactionManager.interactBlock(
            mc.player, Hand.MAIN_HAND,
            new BlockHitResult(Vec3d.ofCenter(targetChest), facingToward(targetChest), targetChest, false)
        );

        shiftClickSpawners();
        notify("Deposited spawners into ender chest.");
        state = State.IDLE;
    }

    // ─── State: NAVIGATING_SPAWNER ────────────────────────────────────────────

    private void tickNavSpawner() {
        if (targetSpawner == null) { state = State.IDLE; return; }

        if (distTo(targetSpawner) <= mineRange.get() - 0.5) {
            stopBaritone();
            state = State.MINING;
            notify("Reached spawner.");
        }
        // Baritone handles movement; nothing else needed here
    }

    // ─── State: NAVIGATING_CHEST ──────────────────────────────────────────────

    private void tickNavChest() {
        if (targetChest == null) { state = State.IDLE; return; }

        if (distTo(targetChest) <= placeRange.get() - 0.5) {
            stopBaritone();
            state = State.DEPOSITING;
            notify("Reached ender chest.");
        }
    }

    // ─── Baritone ─────────────────────────────────────────────────────────────

    private void navigateTo(BlockPos dest) {
        BaritoneAPI.getProvider()
            .getPrimaryBaritone()
            .getCustomGoalProcess()
            .setGoalAndPath(new GoalNear(dest, 2));
    }

    private void stopBaritone() {
        BaritoneAPI.getProvider()
            .getPrimaryBaritone()
            .getPathingBehavior()
            .cancelEverything();
    }

    // ─── World Scanning ───────────────────────────────────────────────────────

    private BlockPos nearestSpawner() {
        BlockPos origin = mc.player.getBlockPos();
        int scan = (int) mineRange.get() + 16;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int x = -scan; x <= scan; x++)
            for (int y = -scan; y <= scan; y++)
                for (int z = -scan; z <= scan; z++) {
                    BlockPos p = origin.add(x, y, z);
                    if (isSpawner(p)) {
                        double d = distTo(p);
                        if (d < bestDist) { bestDist = d; best = p; }
                    }
                }
        return best;
    }

    private BlockPos nearestEnderChest() {
        BlockPos origin = mc.player.getBlockPos();
        int scan = 24;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int x = -scan; x <= scan; x++)
            for (int y = -scan; y <= scan; y++)
                for (int z = -scan; z <= scan; z++) {
                    BlockPos p = origin.add(x, y, z);
                    if (mc.world.getBlockState(p).getBlock() == Blocks.ENDER_CHEST) {
                        double d = distTo(p);
                        if (d < bestDist) { bestDist = d; best = p; }
                    }
                }
        return best;
    }

    private boolean isSpawner(BlockPos p) {
        return mc.world.getBlockState(p).getBlock() == Blocks.SPAWNER;
    }

    private double distTo(BlockPos p) {
        return mc.player.getPos().distanceTo(Vec3d.ofCenter(p));
    }

    private Direction facingToward(BlockPos p) {
        Vec3d diff = mc.player.getEyePos().subtract(Vec3d.ofCenter(p));
        double ax = Math.abs(diff.x), ay = Math.abs(diff.y), az = Math.abs(diff.z);
        if (ax > ay && ax > az) return diff.x > 0 ? Direction.EAST  : Direction.WEST;
        if (ay > ax && ay > az) return diff.y > 0 ? Direction.UP    : Direction.DOWN;
        return diff.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // ─── Inventory ────────────────────────────────────────────────────────────

    private boolean inventoryFull() {
        for (int i = 0; i < 36; i++)
            if (mc.player.getInventory().getStack(i).isEmpty()) return false;
        return true;
    }

    private boolean equipSilkTool() {
        if (!requireSilkTouch.get()) return true;
        if (isSilkPickaxe(mc.player.getMainHandStack())) return true;
        if (!autoEquipPickaxe.get()) return false;

        for (int i = 0; i < 9; i++) {
            if (isSilkPickaxe(mc.player.getInventory().getStack(i))) {
                mc.player.getInventory().selectedSlot = i;
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (isSilkPickaxe(mc.player.getInventory().getStack(i))) {
                InvUtils.move().from(i).toHotbar(8);
                mc.player.getInventory().selectedSlot = 8;
                return true;
            }
        }
        return false;
    }

    private boolean isSilkPickaxe(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (!(stack.getItem() instanceof PickaxeItem)) return false;
        return hasSilkTouch(stack);
    }

    private boolean hasSilkTouch(ItemStack stack) {
        ItemEnchantmentsComponent enchants =
            stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        return enchants.getEnchantments().stream()
            .anyMatch(e -> e.matchesKey(Enchantments.SILK_TOUCH));
    }

    private void shiftClickSpawners() {
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() == Items.SPAWNER) {
                int screenSlot = i < 9 ? i + 36 : i;
                InvUtils.shiftClick().slot(screenSlot);
            }
        }
    }

    // ─── Threat Detection ─────────────────────────────────────────────────────

    private boolean threatDetected() {
        double range = detectionRange.get();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (isWhitelisted(p.getName().getString())) continue;
            if (mc.player.distanceTo(p) <= range) return true;
        }
        return false;
    }

    private boolean isWhitelisted(String name) {
        for (String w : whitelist.get())
            if (w.equalsIgnoreCase(name)) return true;
        return false;
    }

    // ─── Sneak ────────────────────────────────────────────────────────────────

    private void startSneak() {
        if (sneaking) return;
        sneaking = true;
        mc.options.sneakKey.setPressed(true);
        mc.player.setSneaking(true);
    }

    private void stopSneak() {
        if (!sneaking) return;
        sneaking = false;
        mc.options.sneakKey.setPressed(false);
        mc.player.setSneaking(false);
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    private void notify(String msg) {
        if (notifications.get())
            ChatUtils.sendMsg(title + " §7» §f" + msg);
    }
}
