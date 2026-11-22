package ch.laxacube.sudimetools;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.block.Biome;

import java.util.List;
import java.util.Random;

/**
 * Terrain de la Lune : collines douces de régolithe et de roche lunaire.
 * <p>
 * La surface est écrite directement avec les états de blocs CraftEngine
 * (résolus au démarrage par {@link SudimeMoon}), donc les blocs sur lesquels
 * le joueur marche sont bien des blocs custom.
 */
public final class MoonGenerator extends ChunkGenerator {

    private static final int MIN_Y = -64;
    /** Altitude moyenne de la surface lunaire. */
    private static final int BASE_Y = 68;

    private BlockData regolith = Material.CALCITE.createBlockData();
    private BlockData moonRock = Material.TUFF.createBlockData();
    private BlockData filler = Material.DEEPSLATE.createBlockData();
    private BlockData pyrite;

    void blockData(BlockData regolith, BlockData moonRock, BlockData pyrite) {
        if (regolith != null) {
            this.regolith = regolith;
        }
        if (moonRock != null) {
            this.moonRock = moonRock;
        }
        this.pyrite = pyrite;
        this.filler = Material.DEEPSLATE.createBlockData();
    }

    /** Vrai si les états CraftEngine ont pu être résolus. */
    boolean hasCustomSurface() {
        return regolith.getMaterial() != Material.CALCITE;
    }

    @Override
    public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = baseX + dx;
                int z = baseZ + dz;
                int surface = height(x, z);
                data.setBlock(dx, MIN_Y, dz, Material.BEDROCK);
                if (surface - 3 > MIN_Y + 1) {
                    data.setRegion(dx, MIN_Y + 1, dz, dx + 1, surface - 2, dz + 1, filler);
                }
                data.setBlock(dx, surface - 2, dz, moonRock);
                data.setBlock(dx, surface - 1, dz, moonRock);
                data.setBlock(dx, surface, dz, regolith);
            }
        }
    }

    /** Les règles de surface vanilla écraseraient le régolithe : on ne fait rien. */
    @Override
    public void generateSurface(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
    }

    @Override
    public void generateCaves(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
    }

    @Override
    public void generateBedrock(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
    }

    /** Veines de pyrite : minerai abondant, jusqu'à la surface. */
    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(new OrePopulator());
    }

    private final class OrePopulator extends BlockPopulator {
        @Override
        public void populate(WorldInfo info, Random random, int chunkX, int chunkZ, LimitedRegion region) {
            if (pyrite == null) {
                return;
            }
            int baseX = chunkX << 4;
            int baseZ = chunkZ << 4;
            for (int vein = 0; vein < 12; vein++) {
                int dx = random.nextInt(16);
                int dz = random.nextInt(16);
                int x = baseX + dx;
                int z = baseZ + dz;
                int surface = height(x, z);
                int y = surface - 1 - random.nextInt(12);
                if (y <= MIN_Y + 2) {
                    continue;
                }
                int size = 2 + random.nextInt(4);
                for (int i = 0; i < size; i++) {
                    if (dx < 1 || dz < 1 || dx > 14 || dz > 14) {
                        break;
                    }
                    int targetY = y + random.nextInt(3) - 1;
                    BlockData current = region.getBlockData(x, targetY, z);
                    if (current != null && current.getMaterial() != Material.AIR
                            && current.getMaterial() != Material.BEDROCK) {
                        region.setBlockData(x, targetY, z, pyrite);
                    }
                    dx = Math.max(1, Math.min(14, dx + random.nextInt(3) - 1));
                    dz = Math.max(1, Math.min(14, dz + random.nextInt(3) - 1));
                    x = baseX + dx;
                    z = baseZ + dz;
                }
            }
        }
    }

    private int height(int x, int z) {
        double hills = Math.sin(x / 41.0) * Math.cos(z / 37.0) * 6.0
                + Math.sin((x + z) / 17.0) * 2.0
                + Math.cos((x - z) / 23.0) * 2.5;
        return BASE_Y + (int) Math.round(hills);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new BiomeProvider() {
            @Override
            public Biome getBiome(WorldInfo info, int x, int y, int z) {
                return Biome.THE_END;
            }

            @Override
            public List<Biome> getBiomes(WorldInfo info) {
                return List.of(Biome.THE_END);
            }
        };
    }
}
