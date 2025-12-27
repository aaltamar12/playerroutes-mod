package com.playerroutes.render;

import com.playerroutes.PlayerRoutes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ChunkRenderer {
    private static final int CHUNK_SIZE = 16;
    private static final int TILE_SIZE = 128; // 8 pixels per block for better quality
    private static final int PIXELS_PER_BLOCK = TILE_SIZE / CHUNK_SIZE;

    // Height array for hillshading (stores heights for current and previous row)
    private static final int[][] heightBuffer = new int[CHUNK_SIZE + 1][CHUNK_SIZE + 1];

    public static boolean renderChunk(Level level, ChunkPos chunkPos, Path outputPath) {
        try {
            // Load chunk from disk if not in memory
            ChunkAccess chunk = level.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
            if (chunk == null) {
                return false;
            }

            // Also try to get north neighbor for hillshading
            ChunkAccess northChunk = null;
            try {
                northChunk = level.getChunk(chunkPos.x, chunkPos.z - 1, ChunkStatus.FULL, false);
            } catch (Exception ignored) {}

            BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_RGB);

            // First pass: collect heights for hillshading
            for (int x = 0; x <= CHUNK_SIZE; x++) {
                for (int z = 0; z <= CHUNK_SIZE; z++) {
                    int localX = Math.min(x, CHUNK_SIZE - 1);
                    int localZ = Math.min(z, CHUNK_SIZE - 1);

                    if (z == 0 && northChunk != null) {
                        // Get height from north chunk for smooth shading
                        heightBuffer[x][z] = northChunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, CHUNK_SIZE - 1);
                    } else {
                        heightBuffer[x][z] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, Math.max(0, localZ - 1));
                    }
                }
            }

            // Second pass: render with colors and shading
            for (int x = 0; x < CHUNK_SIZE; x++) {
                for (int z = 0; z < CHUNK_SIZE; z++) {
                    int worldX = chunkPos.getMinBlockX() + x;
                    int worldZ = chunkPos.getMinBlockZ() + z;

                    // Get top block height
                    int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);

                    // Get block at surface
                    BlockPos pos = new BlockPos(worldX, y, worldZ);
                    BlockState state = chunk.getBlockState(pos);

                    // If air, try below
                    if (state.isAir() && y > level.getMinBuildHeight()) {
                        y--;
                        pos = new BlockPos(worldX, y, worldZ);
                        state = chunk.getBlockState(pos);
                    }

                    // Check for water - render water with depth effect
                    boolean isWater = state.is(Blocks.WATER);
                    int waterDepth = 0;
                    if (isWater) {
                        // Find water depth
                        int checkY = y - 1;
                        while (checkY > level.getMinBuildHeight() && chunk.getBlockState(new BlockPos(worldX, checkY, worldZ)).is(Blocks.WATER)) {
                            waterDepth++;
                            checkY--;
                        }
                    }

                    // Get biome for color tinting
                    Holder<Biome> biomeHolder = level.getBiome(pos);

                    // Calculate base color
                    int color = getBlockColor(state, level, pos, biomeHolder, y, waterDepth);

                    // Apply hillshading
                    float shade = calculateHillshade(x, z, y);
                    color = applyShade(color, shade);

                    // Fill pixels for this block (PIXELS_PER_BLOCK x PIXELS_PER_BLOCK)
                    fillBlockPixels(image, x, z, color);
                }
            }

            // Ensure directory exists
            Files.createDirectories(outputPath.getParent());

            // Write PNG
            File outputFile = outputPath.toFile();
            ImageIO.write(image, "PNG", outputFile);

            return true;
        } catch (IOException e) {
            PlayerRoutes.LOGGER.error("Failed to render chunk {}: {}", chunkPos, e.getMessage());
            return false;
        } catch (Exception e) {
            PlayerRoutes.LOGGER.debug("Could not render chunk {}: {}", chunkPos, e.getMessage());
            return false;
        }
    }

    private static void fillBlockPixels(BufferedImage image, int blockX, int blockZ, int color) {
        int startX = blockX * PIXELS_PER_BLOCK;
        int startZ = blockZ * PIXELS_PER_BLOCK;

        for (int px = 0; px < PIXELS_PER_BLOCK; px++) {
            for (int pz = 0; pz < PIXELS_PER_BLOCK; pz++) {
                image.setRGB(startX + px, startZ + pz, color);
            }
        }
    }

    private static float calculateHillshade(int x, int z, int currentHeight) {
        // Simple hillshading based on height difference with neighbors
        int northHeight = heightBuffer[x][z];
        int westHeight = (x > 0) ? heightBuffer[x - 1][z + 1] : currentHeight;

        // Calculate slope
        float dx = (currentHeight - westHeight);
        float dz = (currentHeight - northHeight);

        // Light from northwest, elevated 45 degrees
        float lightX = -0.7f;
        float lightZ = -0.7f;
        float lightY = 1.0f;

        // Normalize
        float slope = (float) Math.sqrt(dx * dx + dz * dz + 1);
        float shade = (dx * lightX + dz * lightZ + lightY) / (slope * 1.5f);

        // Clamp and adjust
        shade = Math.max(0.6f, Math.min(1.2f, shade + 0.5f));

        return shade;
    }

    private static int applyShade(int color, float shade) {
        int r = (int) Math.min(255, Math.max(0, ((color >> 16) & 0xFF) * shade));
        int g = (int) Math.min(255, Math.max(0, ((color >> 8) & 0xFF) * shade));
        int b = (int) Math.min(255, Math.max(0, (color & 0xFF) * shade));
        return (r << 16) | (g << 8) | b;
    }

    private static int getBlockColor(BlockState state, Level level, BlockPos pos, Holder<Biome> biomeHolder, int y, int waterDepth) {
        // Water with depth effect
        if (state.is(Blocks.WATER)) {
            return getWaterColor(biomeHolder, waterDepth);
        }

        // Try to get biome-tinted colors for foliage
        String blockName = state.getBlock().getDescriptionId().toLowerCase();

        // Grass blocks - use biome grass color
        if (blockName.contains("grass_block") || blockName.contains("grass")) {
            return getBiomeGrassColor(biomeHolder, y);
        }

        // Leaves - use biome foliage color
        if (blockName.contains("leaves")) {
            return getBiomeFoliageColor(biomeHolder, blockName);
        }

        // Try MapColor first
        try {
            MapColor mapColor = state.getMapColor(level, pos);
            if (mapColor != null && mapColor.col != 0) {
                return mapColor.col;
            }
        } catch (Exception ignored) {}

        // Fallback to custom colors
        return getDefaultColor(state);
    }

    private static int getWaterColor(Holder<Biome> biomeHolder, int depth) {
        // Base water color - try to get from biome
        int baseColor = 0x3F76E4; // Default water blue

        try {
            int biomeWater = biomeHolder.value().getWaterColor();
            if (biomeWater != 0) {
                baseColor = biomeWater;
            }
        } catch (Exception ignored) {}

        // Darken based on depth
        float depthFactor = Math.max(0.4f, 1.0f - (depth * 0.03f));

        int r = (int) (((baseColor >> 16) & 0xFF) * depthFactor);
        int g = (int) (((baseColor >> 8) & 0xFF) * depthFactor);
        int b = (int) ((baseColor & 0xFF) * depthFactor);

        return (r << 16) | (g << 8) | b;
    }

    private static int getBiomeGrassColor(Holder<Biome> biomeHolder, int y) {
        try {
            // Get grass color modifier from biome
            int grassColor = biomeHolder.value().getGrassColor(0, 0);
            if (grassColor != 0) {
                return grassColor;
            }
        } catch (Exception ignored) {}

        // Default grass green
        return 0x7CBD6B;
    }

    private static int getBiomeFoliageColor(Holder<Biome> biomeHolder, String blockName) {
        // Special cases for leaves that don't use biome colors
        if (blockName.contains("spruce")) return 0x619961;
        if (blockName.contains("birch")) return 0x80A755;
        if (blockName.contains("azalea")) return 0x6DB03F;
        if (blockName.contains("cherry")) return 0xE2A2C1;
        if (blockName.contains("mangrove")) return 0x8DB127;

        try {
            int foliageColor = biomeHolder.value().getFoliageColor();
            if (foliageColor != 0) {
                return foliageColor;
            }
        } catch (Exception ignored) {}

        // Default foliage green
        return 0x59AE30;
    }

    private static int getDefaultColor(BlockState state) {
        String blockName = state.getBlock().getDescriptionId().toLowerCase();

        // Lava
        if (blockName.contains("lava")) return 0xD45A12;

        // Stone types
        if (blockName.contains("deepslate")) return 0x4D4D4D;
        if (blockName.contains("stone") || blockName.contains("cobble")) return 0x7D7D7D;
        if (blockName.contains("andesite")) return 0x8A8A8E;
        if (blockName.contains("diorite")) return 0xBDBDBD;
        if (blockName.contains("granite")) return 0x9A6B53;
        if (blockName.contains("tuff")) return 0x6B6B5F;

        // Dirt variants
        if (blockName.contains("podzol")) return 0x6B4423;
        if (blockName.contains("mycelium")) return 0x6B6369;
        if (blockName.contains("mud")) return 0x3C3837;
        if (blockName.contains("dirt") || blockName.contains("coarse")) return 0x8B6914;
        if (blockName.contains("rooted")) return 0x70533B;

        // Sand
        if (blockName.contains("red_sand")) return 0xA95821;
        if (blockName.contains("sand")) return 0xDBCFA0;
        if (blockName.contains("gravel")) return 0x837E7E;

        // Wood
        if (blockName.contains("mangrove") && blockName.contains("log")) return 0x6B5231;
        if (blockName.contains("cherry") && blockName.contains("log")) return 0x331C1C;
        if (blockName.contains("log") || blockName.contains("wood") || blockName.contains("stem")) return 0x6B5231;
        if (blockName.contains("plank")) return 0xA08050;

        // Ores
        if (blockName.contains("ancient_debris")) return 0x5E4236;
        if (blockName.contains("coal")) return 0x2D2D2D;
        if (blockName.contains("iron")) return 0xD8AF93;
        if (blockName.contains("copper")) return 0xA87454;
        if (blockName.contains("gold")) return 0xFCEE4B;
        if (blockName.contains("diamond")) return 0x4AEDD9;
        if (blockName.contains("redstone")) return 0xAA0000;
        if (blockName.contains("emerald")) return 0x17DD62;
        if (blockName.contains("lapis")) return 0x345EC3;

        // Snow/ice
        if (blockName.contains("powder_snow")) return 0xF5F5F5;
        if (blockName.contains("snow")) return 0xFAFAFA;
        if (blockName.contains("blue_ice")) return 0x74B4E6;
        if (blockName.contains("packed_ice")) return 0x8DADDB;
        if (blockName.contains("ice")) return 0xA0C8F0;

        // Terracotta
        if (blockName.contains("terracotta")) return getTerracottaColor(blockName);

        // Concrete
        if (blockName.contains("concrete")) return getConcreteColor(blockName);

        // Nether
        if (blockName.contains("netherrack")) return 0x6F3535;
        if (blockName.contains("nether_brick")) return 0x2D1515;
        if (blockName.contains("soul_sand") || blockName.contains("soul_soil")) return 0x513F35;
        if (blockName.contains("basalt")) return 0x3D3D3D;
        if (blockName.contains("blackstone")) return 0x2A2328;
        if (blockName.contains("crimson_nylium")) return 0x831818;
        if (blockName.contains("warped_nylium")) return 0x167E7E;
        if (blockName.contains("crimson")) return 0x7B0000;
        if (blockName.contains("warped")) return 0x167E7E;
        if (blockName.contains("shroomlight")) return 0xF09035;
        if (blockName.contains("glowstone")) return 0xFFBC5E;
        if (blockName.contains("nether_wart_block")) return 0x720000;
        if (blockName.contains("warped_wart_block")) return 0x168383;

        // End
        if (blockName.contains("end_stone")) return 0xDBDE9F;
        if (blockName.contains("purpur")) return 0xA97CA9;
        if (blockName.contains("chorus")) return 0x8B698B;

        // Flowers and plants
        if (blockName.contains("flower") || blockName.contains("rose") || blockName.contains("tulip") ||
            blockName.contains("dandelion") || blockName.contains("poppy") || blockName.contains("orchid") ||
            blockName.contains("allium") || blockName.contains("lily") || blockName.contains("cornflower")) {
            return 0x7CBD6B; // Blend with grass
        }

        // Mushrooms
        if (blockName.contains("brown_mushroom")) return 0x916D55;
        if (blockName.contains("red_mushroom")) return 0xC83737;

        // Crops
        if (blockName.contains("wheat")) return 0xD5C98A;
        if (blockName.contains("carrot") || blockName.contains("potato")) return 0x4B8B3B;
        if (blockName.contains("beetroot")) return 0x4B8B3B;
        if (blockName.contains("melon")) return 0x6B8B23;
        if (blockName.contains("pumpkin")) return 0xC87418;

        // Air/void
        if (blockName.contains("air") || blockName.contains("void") || blockName.contains("cave_air")) return 0x000000;

        // Bedrock
        if (blockName.contains("bedrock")) return 0x353535;

        // Default gray
        return 0x808080;
    }

    private static int getTerracottaColor(String blockName) {
        if (blockName.contains("white")) return 0xD1B1A0;
        if (blockName.contains("orange")) return 0xA05325;
        if (blockName.contains("magenta")) return 0x95586C;
        if (blockName.contains("light_blue")) return 0x706C8A;
        if (blockName.contains("yellow")) return 0xB98423;
        if (blockName.contains("lime")) return 0x677534;
        if (blockName.contains("pink")) return 0xA14E4E;
        if (blockName.contains("gray")) return 0x392A23;
        if (blockName.contains("light_gray")) return 0x876A61;
        if (blockName.contains("cyan")) return 0x565A5A;
        if (blockName.contains("purple")) return 0x764656;
        if (blockName.contains("blue")) return 0x4A3B5B;
        if (blockName.contains("brown")) return 0x4D3323;
        if (blockName.contains("green")) return 0x4B522A;
        if (blockName.contains("red")) return 0x8E3C2E;
        if (blockName.contains("black")) return 0x251610;
        return 0x985F45; // Default terracotta
    }

    private static int getConcreteColor(String blockName) {
        if (blockName.contains("white")) return 0xCFD5D6;
        if (blockName.contains("orange")) return 0xE06100;
        if (blockName.contains("magenta")) return 0xA9309F;
        if (blockName.contains("light_blue")) return 0x2389C6;
        if (blockName.contains("yellow")) return 0xF0AF15;
        if (blockName.contains("lime")) return 0x5EA818;
        if (blockName.contains("pink")) return 0xD6658E;
        if (blockName.contains("gray")) return 0x363B3E;
        if (blockName.contains("light_gray")) return 0x7D7D73;
        if (blockName.contains("cyan")) return 0x157788;
        if (blockName.contains("purple")) return 0x64209C;
        if (blockName.contains("blue")) return 0x2C2E8E;
        if (blockName.contains("brown")) return 0x60331A;
        if (blockName.contains("green")) return 0x495B24;
        if (blockName.contains("red")) return 0x8E2020;
        if (blockName.contains("black")) return 0x080A0F;
        return 0x808080;
    }
}
