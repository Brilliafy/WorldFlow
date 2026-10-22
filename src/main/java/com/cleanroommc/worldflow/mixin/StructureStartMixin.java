package com.cleanroommc.worldflow.mixin;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.MapGenStructureIO;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraft.world.gen.structure.StructureComponent;
import net.minecraft.world.gen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage 2/3 structure layout is read-only. Vanilla removed fully placed components
 * from the shared LinkedList while neighbouring stride workers were still iterating
 * it, which caused CMEs. Removing that mutation gives every worker an immutable
 * component view; completed components are simply revisited and are no-ops.
 *
 * <p>There is deliberately no monitor here. The component list is finalized before
 * the 4-phase population phases start.</p>
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {

    @Shadow
    protected List<StructureComponent> components;

    @Unique
    private final Set<StructureComponent> worldflow$completed = ConcurrentHashMap.newKeySet();

    /**
     * @author WorldFlow
     * @reason Read-only component iteration; never mutate the shared layout list on a worker.
     */
    @Overwrite
    public void generateStructure(World worldIn, Random rand, StructureBoundingBox structurebb) {
        List<StructureComponent> snapshot;
        try {
            snapshot = new ArrayList<>(this.components);
        } catch (Throwable ignored) {
            // Defensive fallback for a start that is still being finalized during boot.
            snapshot = this.components;
        }
        for (StructureComponent component : snapshot) {
            if (this.worldflow$completed.contains(component)) {
                continue;
            }
            if (component.getBoundingBox().intersectsWith(structurebb)) {
                // false means the component has no remaining pieces. Record it in the
                // lock-free set instead of removing from the shared layout list.
                if (!component.addComponentParts(worldIn, rand, structurebb)) {
                    this.worldflow$completed.add(component);
                }
            }
        }
    }

    /**
     * @author WorldFlow
     * @reason NBT is serialized on the Stage 4 tick thread, so no worker-thread monitor is needed.
     */
    @Overwrite
    public NBTTagCompound writeStructureComponentsToNBT(int chunkX, int chunkZ) {
        StructureStart self = (StructureStart) (Object) this;
        if (MapGenStructureIO.getStructureStartName(self) == null) {
            throw new RuntimeException("StructureStart \"" + this.getClass().getName()
                    + "\" missing ID Mapping, Modder see MapGenStructureIO");
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", MapGenStructureIO.getStructureStartName(self));
        tag.setInteger("ChunkX", chunkX);
        tag.setInteger("ChunkZ", chunkZ);
        tag.setTag("BB", this.boundingBox.toNBTTagIntArray());
        NBTTagList children = new NBTTagList();
        for (StructureComponent component : new ArrayList<>(this.components)) {
            children.appendTag(component.createStructureBaseNBT());
        }
        tag.setTag("Children", children);
        self.writeToNBT(tag);
        return tag;
    }

    @Shadow
    protected abstract void writeToNBT(NBTTagCompound tagCompound);

    @Shadow
    protected StructureBoundingBox boundingBox;
}
