package com.cleanroommc.worldflow.mixin;

import net.minecraft.block.BlockStone;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeDecorator;
import net.minecraft.world.gen.ChunkGeneratorSettings;
import net.minecraft.world.gen.feature.WorldGenMinable;
import net.minecraft.world.gen.feature.WorldGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Random;

@Mixin(BiomeDecorator.class)
public abstract class BiomeDecoratorMixin {

    @Shadow
    public boolean decorating;

    @Shadow
    public BlockPos chunkPos;

    @Shadow
    public ChunkGeneratorSettings chunkProviderSettings;

    @Shadow
    public WorldGenerator dirtGen;
    @Shadow
    public WorldGenerator gravelOreGen;
    @Shadow
    public WorldGenerator graniteGen;
    @Shadow
    public WorldGenerator dioriteGen;
    @Shadow
    public WorldGenerator andesiteGen;
    @Shadow
    public WorldGenerator coalGen;
    @Shadow
    public WorldGenerator ironGen;
    @Shadow
    public WorldGenerator goldGen;
    @Shadow
    public WorldGenerator redstoneGen;
    @Shadow
    public WorldGenerator diamondGen;
    @Unique
    private String worldflow$optionsCache;

    @Unique
    private boolean worldflow$gensReady;

    @Unique
    private final ThreadLocal<BiomeDecorator> worldflow$localDecorator = new ThreadLocal<>();

    @Unique
    private static final ThreadLocal<Boolean> WORLDFLOW_DELEGATING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Unique
    private BiomeDecorator worldflow$cloneDecorator() {
        try {
            java.lang.reflect.Constructor<?> constructor = this.getClass().getDeclaredConstructor();
            constructor.setAccessible(true);
            BiomeDecorator clone = (BiomeDecorator) constructor.newInstance();
            for (Class<?> c = this.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (field.getType() == ThreadLocal.class) continue;
                    try {
                        field.setAccessible(true);
                        field.set(clone, field.get(this));
                    } catch (Throwable ignored) {
                    }
                }
            }
            return clone;
        } catch (Throwable ignored) {
            return new BiomeDecorator();
        }
    }

    @Shadow
    public WorldGenerator lapisGen;

    @Shadow
    protected abstract void genDecorations(Biome biomeIn, World worldIn, Random random);

    /**
     * @author WorldFlow
     * @reason Synchronize on decorator instance and guarantee decorating flag is cleared in finally block,
     *         eliminating 'Already decorating' crashes across concurrent worker threads.
     */
    @Overwrite
    public void decorate(World worldIn, Random random, Biome biome, BlockPos pos) {
        // Feature workers own a private clone of this decorator. No monitor is needed:
        // chunkPos, decorating, generator selection and RNG all stay thread-confined.
        if (ThreadLocalWorldView.isGenerating() && !WORLDFLOW_DELEGATING.get()) {
            BiomeDecorator local = this.worldflow$localDecorator.get();
            if (local == null) {
                local = this.worldflow$cloneDecorator();
                this.worldflow$localDecorator.set(local);
            }
            if (local != (BiomeDecorator) (Object) this) {
                WORLDFLOW_DELEGATING.set(Boolean.TRUE);
                try {
                    local.decorate(worldIn, random, biome, pos);
                } finally {
                    WORLDFLOW_DELEGATING.set(Boolean.FALSE);
                }
                return;
            }
        }
        {
            if (this.decorating) {
                return;
            }
            this.decorating = true;
            try {
                String options = worldIn.getWorldInfo().getGeneratorOptions();
                // Vanilla re-parses the JSON options and allocates 11 WorldGenMinable objects on
                // EVERY decorate call (per chunk, per biome). That is pure waste: settings only
                // change with the world's generator options, so build them once per decorator.
                if (!this.worldflow$gensReady || !options.equals(this.worldflow$optionsCache)) {
                    this.chunkProviderSettings = ChunkGeneratorSettings.Factory.jsonToFactory(options).build();
                    this.dirtGen = new WorldGenMinable(Blocks.DIRT.getDefaultState(), this.chunkProviderSettings.dirtSize);
                    this.gravelOreGen = new WorldGenMinable(Blocks.GRAVEL.getDefaultState(), this.chunkProviderSettings.gravelSize);
                    this.graniteGen = new WorldGenMinable(Blocks.STONE.getDefaultState().withProperty(BlockStone.VARIANT, BlockStone.EnumType.GRANITE), this.chunkProviderSettings.graniteSize);
                    this.dioriteGen = new WorldGenMinable(Blocks.STONE.getDefaultState().withProperty(BlockStone.VARIANT, BlockStone.EnumType.DIORITE), this.chunkProviderSettings.dioriteSize);
                    this.andesiteGen = new WorldGenMinable(Blocks.STONE.getDefaultState().withProperty(BlockStone.VARIANT, BlockStone.EnumType.ANDESITE), this.chunkProviderSettings.andesiteSize);
                    this.coalGen = new WorldGenMinable(Blocks.COAL_ORE.getDefaultState(), this.chunkProviderSettings.coalSize);
                    this.ironGen = new WorldGenMinable(Blocks.IRON_ORE.getDefaultState(), this.chunkProviderSettings.ironSize);
                    this.goldGen = new WorldGenMinable(Blocks.GOLD_ORE.getDefaultState(), this.chunkProviderSettings.goldSize);
                    this.redstoneGen = new WorldGenMinable(Blocks.REDSTONE_ORE.getDefaultState(), this.chunkProviderSettings.redstoneSize);
                    this.diamondGen = new WorldGenMinable(Blocks.DIAMOND_ORE.getDefaultState(), this.chunkProviderSettings.diamondSize);
                    this.lapisGen = new WorldGenMinable(Blocks.LAPIS_ORE.getDefaultState(), this.chunkProviderSettings.lapisSize);
                    this.worldflow$optionsCache = options;
                    this.worldflow$gensReady = true;
                }
                this.chunkPos = pos;
                this.genDecorations(biome, worldIn, random);
            } finally {
                this.decorating = false;
            }
        }
    }
}
