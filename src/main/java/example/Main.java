package example;

import net.querz.mca.*;
import net.querz.nbt.tag.CompoundTag;

import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
        try {
            MCAFile mcaFile = MCAUtil.read("input\\r.0.0.mca");
            Chunk chunk = mcaFile.getChunk(0, 0);

            CompoundTag blockState = chunk.getBlockStateAt(4,75, 4);
            System.out.println(4 + " " + 75 + " " + 4 + ": " + blockState.getValue());

//            mcaFile.createZombie(9, -62, 9); //use only with entity mca files!


            mcaFile.createChest(30, -60, 30, "north", ItemList.builder()
                    .addItem(Item.builder().id("minecraft:gold_block").slot(0).count(80).build())
                    .addItem(Item.builder().id("minecraft:diamond_block").slot(2).count(50).build())
                    .addItem(Item.builder().id("minecraft:netherite_sword").slot(20).count(1)
                            .addEnchantment("minecraft:sharpness", 10000)
                            .addEnchantment("minecraft:mending", 1).build())
                    .build());


            mcaFile.drawImage("input\\image.jpg",512,256, 0, 0, 20);


            mcaFile.createMonsterSpawner(25, -61, 25, MinecraftMob.CREEPER, (short) 4, (short) 4, (short) 200, (short) 800, (short) 16, (short) 6, (short) 0);
            mcaFile.createMonsterSpawner(25, -61, 26, MinecraftMob.ENDER_DRAGON, (short) 4, (short) 20, (short) 200, (short) 800, (short) 60, (short) 30, (short) 0);

            chunk.clearChunk();

            for(int x = 1; x < 8; x++)
                for(int z = 1; z < 8; z++)
                        mcaFile.getChunk(x, z).setBiome(MinecraftBiome.NETHER_WASTES);

            for(int x = 1; x < 10; x++)
                for(int z = 1; z < 10; z++) {
                    CompoundTag bedrock = new CompoundTag();
                    bedrock.putString("Name", "minecraft:bedrock");
                    mcaFile.setBlockStateAt(x, -61, z, bedrock, false);
                }

//            CompoundTag blockState = chunk.getBlockStateAt(4,75, 4);
//            System.out.println(blockState.toString());

            mcaFile.cleanupPalettesAndBlockStates();
            MCAUtil.write(mcaFile, "output\\r.0.0.mca");
        }catch (Exception e) {
            System.out.println(e.getMessage());
        }

    }
}