package com.neltra.neltrastack;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// ═══════════════════════════════════════════════════════════
//  NeltraStack 0.1-fix  |  Folia 1.21.1
//  Gop 5 stack item thanh 1 super-stack
//  Fix: inventory day slot, hopper dupe, anti-dupe lock
// ═══════════════════════════════════════════════════════════
public class NeltraStack extends JavaPlugin implements Listener {

    private static NeltraStack instance;

    // Lock chong dupe: UUID cua item entity dang duoc xu ly
    private final Set<UUID> processingItems =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ───────────────────────────────────────────────────────
    //  LIFECYCLE
    // ───────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        instance = this;
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("NeltraStack 0.1-fix enabled! (Folia 1.21.1)");
    }

    @Override
    public void onDisable() {
        getLogger().info("NeltraStack disabled.");
    }

    public static NeltraStack getInstance() {
        return instance;
    }

    /**
     * Max stack = vanilla max * 5
     * Vi du: dat 64 -> 320 | bow 1 -> 5
     */
    public static int getMaxStack(ItemStack item) {
        if (item == null) return 0;
        return item.getType().getMaxStackSize() * 5;
    }

    // ───────────────────────────────────────────────────────
    //  1. PLAYER PICKUP
    //     - Merge vao slot cu truoc, sau moi mo slot moi
    //     - Neu day inventory: de lai item entity (khong mat, khong drop thua)
    // ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Item itemEntity = event.getItem();
        ItemStack dropped = itemEntity.getItemStack();
        int maxStack = getMaxStack(dropped);

        // Item vanilla binh thuong -> bo qua
        if (maxStack <= dropped.getType().getMaxStackSize()) return;

        // Anti-dupe: neu dang xu ly roi thi cancel
        UUID itemId = itemEntity.getUniqueId();
        if (!processingItems.add(itemId)) {
            event.setCancelled(true);
            return;
        }

        try {
            event.setCancelled(true);
            giveItemSafely(player, dropped, itemEntity, maxStack);
        } finally {
            processingItems.remove(itemId);
        }
    }

    /**
     * Nhet item vao inventory player an toan:
     * Pass 1: merge vao slot da co cung loai
     * Pass 2: dien vao slot trong
     * Con thua: cap nhat lai entity (khong xoa, khong drop them)
     */
    private void giveItemSafely(Player player, ItemStack dropped, Item itemEntity, int maxStack) {
        Inventory inv = player.getInventory();
        ItemStack template = dropped.clone();
        int remaining = template.getAmount();

        // Pass 1: merge slot cu
        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot == null || slot.getType().isAir()) continue;
            if (!slot.isSimilar(template)) continue;

            int current = slot.getAmount();
            if (current >= maxStack) continue;

            int take = Math.min(maxStack - current, remaining);
            slot.setAmount(current + take);
            inv.setItem(i, slot);
            remaining -= take;
        }

        // Pass 2: slot trong
        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot != null && !slot.getType().isAir()) continue;

            int take = Math.min(maxStack, remaining);
            ItemStack newSlot = template.clone();
            newSlot.setAmount(take);
            inv.setItem(i, newSlot);
            remaining -= take;
        }

        if (remaining <= 0) {
            itemEntity.remove();
        } else {
            // Day inventory: de lai phan con thua trong world
            ItemStack leftover = template.clone();
            leftover.setAmount(remaining);
            itemEntity.setItemStack(leftover);
        }

        player.updateInventory();
    }

    // ───────────────────────────────────────────────────────
    //  2. HOPPER PICKUP (hut item tu dat)
    // ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        Item itemEntity = event.getItem();
        ItemStack picked = itemEntity.getItemStack();
        int maxStack = getMaxStack(picked);

        if (maxStack <= picked.getType().getMaxStackSize()) return;

        UUID itemId = itemEntity.getUniqueId();
        if (!processingItems.add(itemId)) {
            event.setCancelled(true);
            return;
        }

        try {
            event.setCancelled(true);
            insertIntoInventorySafely(event.getInventory(), picked, itemEntity, maxStack);
        } finally {
            processingItems.remove(itemId);
        }
    }

    // ───────────────────────────────────────────────────────
    //  3. HOPPER TRANSFER (chuyen item giua container)
    //     - Chi can dam bao khong bi split thanh stack nho hon maxStack
    //     - Vanilla tu xu ly dung neu slot hop le
    // ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        ItemStack item = event.getItem();
        int maxStack = getMaxStack(item);

        // Neu item super-stack dang chuyen ma vuot maxStack thi gioi han lai
        if (maxStack <= item.getType().getMaxStackSize()) return;

        if (item.getAmount() > maxStack) {
            item.setAmount(maxStack);
            event.setItem(item);
        }
    }

    // ───────────────────────────────────────────────────────
    //  HELPER: nhet item vao inventory (dung cho hopper)
    // ───────────────────────────────────────────────────────

    private void insertIntoInventorySafely(Inventory inv, ItemStack item, Item itemEntity, int maxStack) {
        ItemStack template = item.clone();
        int remaining = template.getAmount();

        // Pass 1: merge slot cu
        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot == null || slot.getType().isAir()) continue;
            if (!slot.isSimilar(template)) continue;

            int current = slot.getAmount();
            if (current >= maxStack) continue;

            int take = Math.min(maxStack - current, remaining);
            slot.setAmount(current + take);
            inv.setItem(i, slot);
            remaining -= take;
        }

        // Pass 2: slot trong
        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot != null && !slot.getType().isAir()) continue;

            int take = Math.min(maxStack, remaining);
            ItemStack newSlot = template.clone();
            newSlot.setAmount(take);
            inv.setItem(i, newSlot);
            remaining -= take;
        }

        if (remaining <= 0) {
            itemEntity.remove();
        } else {
            ItemStack leftover = template.clone();
            leftover.setAmount(remaining);
            itemEntity.setItemStack(leftover);
        }
    }
}
