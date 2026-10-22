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

import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * MapGenStructure used to serialize this method on the generator instance. That lock was
 * removed for parallel terrain generation, but the component list is still mutable and a
 * single StructureStart can be visited by several neighboring population workers.
 *
 * <p>A monitor on the StructureStart (not on the MapGenStructure) keeps each structure
 * piece list coherent without serializing unrelated structures or chunks.</p>
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {

    @Shadow
    protected List<StructureComponent> components;

    /**
     * @author WorldFlow
     * @reason Structure components are mutated while neighboring chunks write the same start.
     */
    @Overwrite
    public void generateStructure(World worldIn, Random rand, StructureBoundingBox structurebb) {
        synchronized (this) {
            Iterator<StructureComponent> iterator = this.components.iterator();
            while (iterator.hasNext()) {
                StructureComponent structurecomponent = iterator.next();
                if (structurecomponent.getBoundingBox().intersectsWith(structurebb)
                        && !structurecomponent.addComponentParts(worldIn, rand, structurebb)) {
                    iterator.remove();
                }
            }
        }
    }

    /**
     * @author WorldFlow
     * @reason The NBT writer iterates the same mutable component list as generateStructure.
     */
    @Overwrite
    public NBTTagCompound writeStructureComponentsToNBT(int chunkX, int chunkZ) {
        StructureStart self = (StructureStart) (Object) this;
        synchronized (this) {
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
            for (StructureComponent component : this.components) {
                children.appendTag(component.createStructureBaseNBT());
            }
            tag.setTag("Children", children);
            self.writeToNBT(tag);
            return tag;
        }
    }

    @Shadow
    protected abstract void writeToNBT(NBTTagCompound tagCompound);

    @Shadow
    protected StructureBoundingBox boundingBox;
}
