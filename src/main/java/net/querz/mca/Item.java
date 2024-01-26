package net.querz.mca;

import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.IntTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.Tag;

public class Item {//todo enum for items id
    private final CompoundTag item = new CompoundTag();
    private ListTag<CompoundTag> enchantments;

    public Item id(String id) {
        item.putString("id", id);
        return this;
    }

    public Item count(int count) {
        item.putByte("Count",(byte) count);
        return this;
    }

    public Item slot(int slot) {
        item.putByte("Slot", (byte) slot);
        return this;
    }

    public Item addEnchantment(String id, int lvl) {
        if (enchantments == null) {
            enchantments = new ListTag<>(CompoundTag.class);
            CompoundTag tag = new CompoundTag();
            tag.put("Enchantments", enchantments);
            tag.put("Damage", new IntTag(0));
            item.put("tag", tag); // 'tag' is where Minecraft expects custom tags.
        }
        CompoundTag enchantmentTag = new CompoundTag();
        enchantmentTag.putString("id", id);
        enchantmentTag.putShort("lvl", (short) lvl);
        enchantments.add(enchantmentTag);
        return this;
    }

    public Item addCustomTag(String key, Tag<?> tag) {
        CompoundTag customTags = item.getCompoundTag("tag");
        if (customTags == null) {
            customTags = new CompoundTag();
            item.put("tag", customTags);
        }
        customTags.put(key, tag);
        return this;
    }

    public CompoundTag build() {
        return item;
    }

    public static Item builder() {
        return new Item();
    }
}
