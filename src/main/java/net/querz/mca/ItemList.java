package net.querz.mca;

import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;

public class ItemList {
    private final ListTag<CompoundTag> items = new ListTag<>(CompoundTag.class);

    public ItemList addItem(CompoundTag item) {
        items.add(item);
        return this;
    }

    public ListTag<CompoundTag> build() {
        return items;
    }

    public static ItemList builder() {
        return new ItemList();
    }
}
