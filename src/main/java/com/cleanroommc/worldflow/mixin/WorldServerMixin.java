package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer;
import com.cleanroommc.worldflow.pipeline.PublishManager;
import com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Mixin(WorldServer.class)
public abstract class WorldServerMixin {

    @Shadow
    @Final
    private TreeSet<NextTickListEntry> pendingTickListEntriesTreeSet;

    @Shadow
    @Final
    private Set<NextTickListEntry> pendingTickListEntriesHashSet;

    @Shadow
    @Final
    private List<NextTickListEntry> pendingTickListEntriesThisTick;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void worldflow$onWorldTick(CallbackInfo ci) {
        WorldServer world = (WorldServer) (Object) this;
        // Process chunks completed by parallel worker pipeline and publish to live world
        PublishManager.INSTANCE.tick(world, 32);
        com.cleanroommc.worldflow.pipeline.GenerationScheduler.getInstance().checkLogMetrics();
    }

    @Inject(method = "scheduleBlockUpdate(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;II)V",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onScheduleBlockUpdate(BlockPos pos, Block blockIn, int delay, int priority, CallbackInfo ci) {
        WorldServer world = (WorldServer) (Object) this;
        if ((world.getMinecraftServer() != null && !world.getMinecraftServer().isCallingFromMinecraftThread()) || ThreadLocalWorldView.isGenerating()) {
            CascadeBlockBuffer.INSTANCE.stageTickUpdate(world, pos, blockIn, delay, priority);
            ci.cancel();
        }
    }

    @Inject(method = "updateBlockTick(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;II)V",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onUpdateBlockTick(BlockPos pos, Block blockIn, int delay, int priority, CallbackInfo ci) {
        WorldServer world = (WorldServer) (Object) this;
        if ((world.getMinecraftServer() != null && !world.getMinecraftServer().isCallingFromMinecraftThread()) || ThreadLocalWorldView.isGenerating()) {
            CascadeBlockBuffer.INSTANCE.stageTickUpdate(world, pos, blockIn, delay, priority);
            ci.cancel();
        }
    }

    @Inject(method = "scheduleUpdate(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;I)V",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onScheduleUpdate(BlockPos pos, Block blockIn, int delay, CallbackInfo ci) {
        WorldServer world = (WorldServer) (Object) this;
        if ((world.getMinecraftServer() != null && !world.getMinecraftServer().isCallingFromMinecraftThread()) || ThreadLocalWorldView.isGenerating()) {
            CascadeBlockBuffer.INSTANCE.stageTickUpdate(world, pos, blockIn, delay, 0);
            ci.cancel();
        }
    }

    @Inject(method = "getPendingBlockUpdates(Lnet/minecraft/world/gen/structure/StructureBoundingBox;Z)Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onGetPendingBlockUpdates(StructureBoundingBox structureBB, boolean remove, CallbackInfoReturnable<List<NextTickListEntry>> cir) {
        synchronized (this.pendingTickListEntriesTreeSet) {
            List<NextTickListEntry> list = null;
            for (int i = 0; i < 2; ++i) {
                Iterator<NextTickListEntry> iterator = (i == 0) ?
                        this.pendingTickListEntriesTreeSet.iterator() :
                        this.pendingTickListEntriesThisTick.iterator();
                while (iterator.hasNext()) {
                    NextTickListEntry entry = iterator.next();
                    BlockPos blockpos = entry.position;
                    if (blockpos.getX() >= structureBB.minX && blockpos.getX() < structureBB.maxX &&
                            blockpos.getZ() >= structureBB.minZ && blockpos.getZ() < structureBB.maxZ) {
                        if (remove) {
                            if (i == 0) {
                                this.pendingTickListEntriesHashSet.remove(entry);
                            }
                            iterator.remove();
                        }
                        if (list == null) {
                            list = new ArrayList<>();
                        }
                        list.add(entry);
                    }
                }
            }
            cir.setReturnValue(list);
        }
    }

    @Inject(method = "tickUpdates(Z)Z", at = @At("HEAD"), cancellable = true)
    private void worldflow$onTickUpdatesHead(boolean runAllPending, CallbackInfoReturnable<Boolean> cir) {
        WorldServer world = (WorldServer) (Object) this;
        if (world.getWorldInfo().getTerrainType() == net.minecraft.world.WorldType.DEBUG_ALL_BLOCK_STATES) {
            cir.setReturnValue(false);
            return;
        }

        synchronized (this.pendingTickListEntriesTreeSet) {
            if (this.pendingTickListEntriesTreeSet.isEmpty() && !this.pendingTickListEntriesHashSet.isEmpty()) {
                WorldFlow.LOGGER.warn("[WorldFlow] Resynchronizing pending tick list: TreeSet was empty but HashSet had {} entries",
                        this.pendingTickListEntriesHashSet.size());
                this.pendingTickListEntriesTreeSet.addAll(this.pendingTickListEntriesHashSet);
            } else if (!this.pendingTickListEntriesTreeSet.isEmpty() && this.pendingTickListEntriesHashSet.isEmpty()) {
                WorldFlow.LOGGER.warn("[WorldFlow] Resynchronizing pending tick list: HashSet was empty but TreeSet had {} entries",
                        this.pendingTickListEntriesTreeSet.size());
                this.pendingTickListEntriesHashSet.addAll(this.pendingTickListEntriesTreeSet);
            } else if (this.pendingTickListEntriesTreeSet.size() != this.pendingTickListEntriesHashSet.size()) {
                WorldFlow.LOGGER.warn("[WorldFlow] Resynchronizing pending tick list: TreeSet={} vs HashSet={}",
                        this.pendingTickListEntriesTreeSet.size(), this.pendingTickListEntriesHashSet.size());
                this.pendingTickListEntriesHashSet.clear();
                this.pendingTickListEntriesHashSet.addAll(this.pendingTickListEntriesTreeSet);
            }

            int count = this.pendingTickListEntriesTreeSet.size();
            if (count > 65536) {
                count = 65536;
            }

            world.profiler.startSection("cleaning");

            for (int j = 0; j < count; ++j) {
                if (this.pendingTickListEntriesTreeSet.isEmpty()) {
                    break;
                }
                NextTickListEntry entry = this.pendingTickListEntriesTreeSet.first();
                if (!runAllPending && entry.scheduledTime > world.getWorldInfo().getWorldTotalTime()) {
                    break;
                }

                this.pendingTickListEntriesTreeSet.remove(entry);
                this.pendingTickListEntriesHashSet.remove(entry);
                this.pendingTickListEntriesThisTick.add(entry);
            }

            world.profiler.endSection();
        }

        world.profiler.startSection("ticking");
        Iterator<NextTickListEntry> iterator = this.pendingTickListEntriesThisTick.iterator();

        while (iterator.hasNext()) {
            NextTickListEntry entry = iterator.next();
            iterator.remove();

            if (world.isBlockLoaded(entry.position)) {
                net.minecraft.block.state.IBlockState state = world.getBlockState(entry.position);
                if (state.getMaterial() != net.minecraft.block.material.Material.AIR && Block.isEqualTo(state.getBlock(), entry.getBlock())) {
                    try {
                        state.getBlock().updateTick(world, entry.position, state, world.rand);
                    } catch (Throwable throwable) {
                        net.minecraft.crash.CrashReport report = net.minecraft.crash.CrashReport.makeCrashReport(throwable, "Exception while ticking a block");
                        net.minecraft.crash.CrashReportCategory category = report.makeCategory("Block being ticked");
                        net.minecraft.crash.CrashReportCategory.addBlockInfo(category, entry.position, state);
                        throw new net.minecraft.util.ReportedException(report);
                    }
                }
            } else {
                world.scheduleUpdate(entry.position, entry.getBlock(), 0);
            }
        }

        world.profiler.endSection();
        this.pendingTickListEntriesThisTick.clear();
        cir.setReturnValue(!this.pendingTickListEntriesTreeSet.isEmpty());
    }

    @Inject(method = "onEntityAdded", at = @At("HEAD"), cancellable = true)
    private void worldflow$onEntityAdded(net.minecraft.entity.Entity entityIn, CallbackInfo ci) {
        WorldServer world = (WorldServer) (Object) this;
        if (world.getMinecraftServer() != null && !world.getMinecraftServer().isCallingFromMinecraftThread()) {
            WorldFlow.LOGGER.warn("[WorldFlow] Off-thread WorldServer.onEntityAdded detected for {} on thread {}! Staging to tick thread.",
                    entityIn != null ? entityIn.getName() : "null", Thread.currentThread().getName());
            if (entityIn != null) {
                CascadeBlockBuffer.INSTANCE.stageEntity(world, entityIn);
            }
            ci.cancel();
        }
    }
}
