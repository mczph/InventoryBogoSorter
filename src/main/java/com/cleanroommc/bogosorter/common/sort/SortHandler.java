package com.cleanroommc.bogosorter.common.sort;

import com.cleanroommc.bogosorter.BogoSortAPI;
import com.cleanroommc.bogosorter.ClientEventHandler;
import com.cleanroommc.bogosorter.api.ISortableContainer;
import com.cleanroommc.bogosorter.api.SortRule;
import com.cleanroommc.bogosorter.common.McUtils;
import com.cleanroommc.bogosorter.common.network.CSlotSync;
import com.cleanroommc.bogosorter.common.network.NetworkHandler;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenCustomHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.SlotItemHandler;
import net.minecraftforge.items.wrapper.PlayerInvWrapper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.stream.Collectors;

@SideOnly(Side.CLIENT)
public class SortHandler {

    private static final List<SortRule<ItemStack>> sortRules = new ArrayList<>();
    private static final List<NbtSortRule> nbtSortRules = new ArrayList<>();

    static {
        String[] itemRules = {"mod", "material", "ore_prefix", "id", "meta", "nbt_has", "nbt_rules"};
        String[] nbtRules = {"enchantment", "enchantment_book", "potion", "gt_circ_config"};

        sortRules.addAll(Arrays.stream(itemRules).map(BogoSortAPI.INSTANCE::getItemSortRule).collect(Collectors.toList()));
        nbtSortRules.addAll(Arrays.stream(nbtRules).map(BogoSortAPI.INSTANCE::getNbtSortRule).collect(Collectors.toList()));
    }

    private final Container container;
    private GuiSortingContext context;

    public SortHandler(Container container, boolean player) {
        this.container = container;
        this.context = createSortContext(player);
    }

    public GuiSortingContext createSortContext(boolean player) {
        if (player) {
            GuiSortingContext.Builder builder = new GuiSortingContext.Builder(container);
            List<Slot> slots = new ArrayList<>();
            for (Slot slot : container.inventorySlots) {
                if (slot.inventory instanceof InventoryPlayer ||
                        (slot instanceof SlotItemHandler &&
                                (((SlotItemHandler) slot).getItemHandler() instanceof PlayerMainInvWrapper || ((SlotItemHandler) slot).getItemHandler() instanceof PlayerInvWrapper))) {
                    if (slot.getSlotIndex() >= 9 && slot.getSlotIndex() < 36) {
                        slots.add(slot);
                    }
                }
            }
            builder.addSlotGroup(9, slots);
            return builder.build();
        }
        if (container instanceof ISortableContainer) {
            GuiSortingContext.Builder builder = new GuiSortingContext.Builder(container);
            ((ISortableContainer) container).buildSortingContext(builder);
            return builder.build();
        }
        if (BogoSortAPI.isValidSortable(container)) {
            GuiSortingContext.Builder builder = new GuiSortingContext.Builder(container);
            BogoSortAPI.INSTANCE.getBuilder(container).accept(container, builder);
            return builder.build();
        }
        return new GuiSortingContext(container, Collections.emptyList());
    }

    public void sort(int slotId) {
        Slot[][] slotGroup = context.getSlotGroup(slotId);
        if (slotGroup != null) {
            if (new Random().nextFloat() < 0.005f) {
                sortBogo(slotGroup);
                Minecraft.getMinecraft().player.sendMessage(new TextComponentString("Get Bogo'd!"));
            } else {
                sortHorizontal(slotGroup);
            }
            McUtils.syncSlotsToServer(slotGroup);
        }
    }

    public void sortHorizontal(Slot[][] slotGroup) {
        Object2IntMap<ItemStack> items = gatherItems(slotGroup);
        if (items.isEmpty()) return;
        LinkedList<ItemStack> itemList = new LinkedList<>(items.keySet());
        itemList.forEach(item -> item.setCount(items.getInt(item)));
        itemList.sort(ITEM_COMPARATOR);
        ItemStack item = itemList.pollFirst();
        if (item == null) return;
        int remaining = items.getInt(item);
        for (Slot[] slotRow : slotGroup) {
            for (Slot slot : slotRow) {
                if (item == ItemStack.EMPTY) {
                    slot.putStack(item);
                    continue;
                }
                if (!slot.isItemValid(item)) continue;
                int limit = Math.min(slot.getItemStackLimit(item), item.getMaxStackSize());
                limit = Math.min(remaining, limit);
                if (limit <= 0) continue;
                ItemStack toInsert = item.copy();
                toInsert.setCount(limit);
                slot.putStack(toInsert);
                remaining -= limit;
                if (remaining <= 0) {
                    if (itemList.isEmpty()) {
                        item = ItemStack.EMPTY;
                        continue;
                    }
                    item = itemList.pollFirst();
                    remaining = items.getInt(item);
                }
            }
        }
        if (!itemList.isEmpty()) {
            McUtils.giveItemsToPlayer(Minecraft.getMinecraft().player, prepareDropList(items, itemList));
        }
    }

    public void sortVertical(Slot[][] slotGroup) {
        Object2IntMap<ItemStack> items = gatherItems(slotGroup);
        if (items.isEmpty()) return;
        LinkedList<ItemStack> itemList = new LinkedList<>(items.keySet());
        itemList.forEach(item -> item.setCount(items.getInt(item)));
        itemList.sort(ITEM_COMPARATOR);
        ItemStack item = itemList.pollFirst();
        if (item == null) return;
        int remaining = items.getInt(item);
        main:
        for (int c = 0; c < slotGroup[0].length; c++) {
            for (Slot[] slots : slotGroup) {
                if (c >= slots.length) break main;
                Slot slot = slots[c];
                if (item == ItemStack.EMPTY) {
                    slot.putStack(item);
                    continue;
                }
                if (!slot.isItemValid(item)) continue;
                int limit = Math.min(slot.getItemStackLimit(item), item.getMaxStackSize());
                limit = Math.min(remaining, limit);
                if (limit <= 0) continue;
                ItemStack toInsert = item.copy();
                toInsert.setCount(limit);
                slot.putStack(toInsert);
                remaining -= limit;
                if (remaining <= 0) {
                    if (itemList.isEmpty()) {
                        item = ItemStack.EMPTY;
                        continue;
                    }
                    item = itemList.pollFirst();
                    remaining = items.getInt(item);
                }
            }
        }
        if (!itemList.isEmpty()) {
            McUtils.giveItemsToPlayer(Minecraft.getMinecraft().player, prepareDropList(items, itemList));
        }
    }

    public void sortBogo(Slot[][] slotGroup) {
        ItemStack[][] itemGrid = new ItemStack[slotGroup.length][slotGroup[0].length];
        for (ItemStack[] itemRow : itemGrid) {
            Arrays.fill(itemRow, ItemStack.EMPTY);
        }
        List<ItemStack> items = new ArrayList<>();
        for (Slot[] slotRow : slotGroup) {
            for (Slot slot : slotRow) {
                ItemStack stack = slot.getStack();
                if (!stack.isEmpty()) {
                    items.add(stack);
                }
            }
        }
        Random rnd = new Random();
        for (ItemStack item : items) {
            int slot, row;
            do {
                row = rnd.nextInt(itemGrid.length);
                slot = rnd.nextInt(itemGrid[row].length);
            } while (!itemGrid[row][slot].isEmpty());
            itemGrid[row][slot] = item;
        }
        for (int r = 0; r < slotGroup.length; r++) {
            for (int c = 0; c < slotGroup[r].length; c++) {
                slotGroup[r][c].putStack(itemGrid[r][c]);
            }
        }
    }

    public Object2IntMap<ItemStack> gatherItems(Slot[][] slotGroup) {
        Object2IntOpenCustomHashMap<ItemStack> items = new Object2IntOpenCustomHashMap<>(BogoSortAPI.ITEM_META_NBT_HASH_STRATEGY);
        for (Slot[] slotRow : slotGroup) {
            for (Slot slot : slotRow) {
                ItemStack stack = slot.getStack();
                if (!stack.isEmpty()) {
                    int amount = stack.getCount();
                    stack = stack.copy();
                    stack.setCount(1);
                    items.compute(stack, (key, value) -> value == null ? amount : value + amount);
                }
            }
        }
        return items;
    }

    private static List<ItemStack> prepareDropList(Object2IntMap<ItemStack> itemMap, List<ItemStack> sortedList) {
        List<ItemStack> dropList = new ArrayList<>();
        for (ItemStack item : sortedList) {
            int amount = itemMap.getInt(item);
            while (amount > 0) {
                int maxSize = Math.min(amount, item.getMaxStackSize());
                ItemStack newStack = item.copy();
                newStack.setCount(maxSize);
                amount -= maxSize;
                dropList.add(newStack);
            }
        }
        return dropList;
    }

    public static final Comparator<ItemStack> ITEM_COMPARATOR = (stack1, stack2) -> {
        int result = 0;
        for (SortRule<ItemStack> sortRule : sortRules) {
            result = sortRule.compare(stack1, stack2);
            if (result != 0) return result;
        }
        return result;
    };

    public static List<NbtSortRule> getNbtSortRules() {
        return nbtSortRules;
    }

    public static List<SortRule<ItemStack>> getItemSortRules() {
        return sortRules;
    }

    public void clearAllItems(Slot slot1) {
        Slot[][] slotGroup = context.getSlotGroup(slot1.slotNumber);
        if (slotGroup != null) {
            List<Pair<ItemStack, Integer>> slots = new ArrayList<>();
            for (Slot[] slotRow : slotGroup) {
                for (Slot slot : slotRow) {
                    if (!slot.getStack().isEmpty()) {
                        slot.putStack(ItemStack.EMPTY);
                        slots.add(Pair.of(ItemStack.EMPTY, slot.slotNumber));
                    }
                }
            }
            NetworkHandler.sendToServer(new CSlotSync(slots));
        }
    }

    public void randomizeItems(Slot slot1) {
        Slot[][] slotGroup = context.getSlotGroup(slot1.slotNumber);
        if (slotGroup != null) {
            List<Pair<ItemStack, Integer>> slots = new ArrayList<>();
            Random random = new Random();
            for (Slot[] slotRow : slotGroup) {
                for (Slot slot : slotRow) {
                    if (random.nextFloat() < 0.3f) {
                        ItemStack randomItem = ClientEventHandler.allItems.get(random.nextInt(ClientEventHandler.allItems.size())).copy();
                        slot.putStack(randomItem.copy());
                        slots.add(Pair.of(randomItem, slot.slotNumber));
                    }
                }
            }
            NetworkHandler.sendToServer(new CSlotSync(slots));
        }
    }
}
