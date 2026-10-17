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
        if (ThreadLocalWorldView.isGenerating()) {
            CascadeBlockBuffer.INSTANCE.stageTickUpdate((WorldServer) (Object) this, pos, blockIn, delay, priority);
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

    @Inject(method = "tickUpdates(Z)Z", at = @At("HEAD"))
    private void worldflow$onTickUpdatesHead(boolean runAllPending, CallbackInfoReturnable<Boolean> cir) {
        synchronized (this.pendingTickListEntriesTreeSet) {
            if (this.pendingTickListEntriesTreeSet.size() != this.pendingTickListEntriesHashSet.size()) {
                WorldFlow.LOGGER.warn("[WorldFlow] Resynchronizing pending tick list: TreeSet={} vs HashSet={}",
                        this.pendingTickListEntriesTreeSet.size(), this.pendingTickListEntriesHashSet.size());
                this.pendingTickListEntriesHashSet.clear();
                this.pendingTickListEntriesHashSet.addAll(this.pendingTickListEntriesTreeSet);
            }
        }
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
