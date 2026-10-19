/*
 * Decompiled with CFR 0.152.
 */
package com.pg85.otg.customobjects.structures.bo4;

import com.pg85.otg.OTG;
import com.pg85.otg.common.LocalBiome;
import com.pg85.otg.common.LocalWorld;
import com.pg85.otg.configuration.biome.BiomeConfig;
import com.pg85.otg.customobjects.bo4.BO4;
import com.pg85.otg.customobjects.structures.CustomStructure;
import com.pg85.otg.customobjects.structures.CustomStructureCache;
import com.pg85.otg.customobjects.structures.CustomStructureFileManager;
import com.pg85.otg.customobjects.structures.PlottedChunksRegion;
import com.pg85.otg.customobjects.structures.StructuredCustomObject;
import com.pg85.otg.customobjects.structures.bo4.BO4CustomStructure;
import com.pg85.otg.customobjects.structures.bo4.BO4CustomStructureCoordinate;
import com.pg85.otg.exception.InvalidConfigException;
import com.pg85.otg.generator.resource.CustomStructureGen;
import com.pg85.otg.logging.LogMarker;
import com.pg85.otg.util.ChunkCoordinate;
import com.pg85.otg.util.FifoMap;
import com.pg85.otg.util.bo3.Rotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class CustomStructurePlotter {
    private static final java.util.concurrent.locks.ReentrantLock CACHE_LOCK = new java.util.concurrent.locks.ReentrantLock();
    private static final java.util.concurrent.locks.ReentrantLock[] REGION_LOCKS = new java.util.concurrent.locks.ReentrantLock[64];
    static {
        for (int i = 0; i < 64; i++) REGION_LOCKS[i] = new java.util.concurrent.locks.ReentrantLock();
    }
    private static java.util.concurrent.locks.ReentrantLock regionLock(ChunkCoordinate c) {
        return REGION_LOCKS[((c.getChunkX() * 31) ^ c.getChunkZ()) & 63];
    }

    private final Map<ChunkCoordinate, BO4CustomStructure[][]> bo4StructureCache;
    private final Map<ChunkCoordinate, PlottedChunksRegion> plottedChunks;
    private final HashMap<String, ArrayList<ChunkCoordinate>> spawnedStructuresByName;
    private final HashMap<String, HashMap<ChunkCoordinate, Integer>> spawnedStructuresByGroup;
    private final Object processingLock = new Object();
    private boolean processing = false;
    private final java.util.concurrent.atomic.AtomicBoolean structurePlottedAtSpawn = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final ThreadLocal<Map<ChunkCoordinate, ArrayList<String>>> structureNamesPerChunk =
            ThreadLocal.withInitial(java.util.concurrent.ConcurrentHashMap::new);

    private ArrayList<String> namesGet(ChunkCoordinate c) { return this.structureNamesPerChunk.get().get(c); }
    private void namesPut(ChunkCoordinate c, ArrayList<String> v) {
        Map<ChunkCoordinate, ArrayList<String>> m = this.structureNamesPerChunk.get();
        if (m.size() > 4096) m.clear();
        m.put(c, v);
    }
    private void namesRemove(ChunkCoordinate c) { this.structureNamesPerChunk.get().remove(c); }
    private final Map<ChunkCoordinate, Object> plottedChunksFastCache = new java.util.concurrent.ConcurrentHashMap<>();

    public CustomStructurePlotter() {
        this.spawnedStructuresByName = new HashMap<String, ArrayList<ChunkCoordinate>>();
        this.spawnedStructuresByGroup = new HashMap<String, HashMap<ChunkCoordinate, Integer>>();
        this.bo4StructureCache = new java.util.concurrent.ConcurrentHashMap<ChunkCoordinate, BO4CustomStructure[][]>();
        this.plottedChunks = new java.util.concurrent.ConcurrentHashMap<ChunkCoordinate, PlottedChunksRegion>();
    }

    private boolean structureCacheContainsKey(ChunkCoordinate chunkCoordinate) {
        CACHE_LOCK.lock();
        try { return structureCacheContainsKey0(chunkCoordinate); } finally { CACHE_LOCK.unlock(); }
    }

    private boolean structureCacheContainsKey0(ChunkCoordinate chunkCoordinate) {
        ChunkCoordinate regionCoord = chunkCoordinate.toRegionCoord();
        BO4CustomStructure[][] chunkRegion = this.bo4StructureCache.get(regionCoord);
        return chunkRegion != null && chunkRegion[chunkCoordinate.getRegionInternalX()][chunkCoordinate.getRegionInternalZ()] != null;
    }

    private void addToStructureCache(ChunkCoordinate chunkCoordinate, BO4CustomStructure structure) {
        CACHE_LOCK.lock();
        try { addToStructureCache0(chunkCoordinate, structure); } finally { CACHE_LOCK.unlock(); }
    }

    private void addToStructureCache0(ChunkCoordinate chunkCoordinate, BO4CustomStructure structure) {
        ChunkCoordinate regionCoord = chunkCoordinate.toRegionCoord();
        BO4CustomStructure[][] chunkRegion = this.bo4StructureCache.get(regionCoord);
        if (chunkRegion == null) {
            chunkRegion = new BO4CustomStructure[100][100];
            this.bo4StructureCache.put(regionCoord, chunkRegion);
        }
        chunkRegion[chunkCoordinate.getRegionInternalX()][chunkCoordinate.getRegionInternalZ()] = structure;
    }

    private void removeFromStructureCache(ChunkCoordinate chunkCoordinate) {
        CACHE_LOCK.lock();
        try { removeFromStructureCache0(chunkCoordinate); } finally { CACHE_LOCK.unlock(); }
    }

    private void removeFromStructureCache0(ChunkCoordinate chunkCoordinate) {
        ChunkCoordinate regionCoord = chunkCoordinate.toRegionCoord();
        BO4CustomStructure[][] chunkRegion = this.bo4StructureCache.get(regionCoord);
        if (chunkRegion != null) {
            chunkRegion[chunkCoordinate.getRegionInternalX()][chunkCoordinate.getRegionInternalZ()] = null;
        }
    }

    private BO4CustomStructure getFromStructureCache(ChunkCoordinate chunkCoordinate) {
        CACHE_LOCK.lock();
        try { return getFromStructureCache0(chunkCoordinate); } finally { CACHE_LOCK.unlock(); }
    }

    private BO4CustomStructure getFromStructureCache0(ChunkCoordinate chunkCoordinate) {
        ChunkCoordinate regionCoord = chunkCoordinate.toRegionCoord();
        BO4CustomStructure[][] chunkRegion = this.bo4StructureCache.get(regionCoord);
        if (chunkRegion != null) {
            return chunkRegion[chunkCoordinate.getRegionInternalX()][chunkCoordinate.getRegionInternalZ()];
        }
        return null;
    }

    private boolean plottedChunksContainsKey(ChunkCoordinate chunkCoordinate) {
        CACHE_LOCK.lock();
        try { return plottedChunksContainsKey0(chunkCoordinate); } finally { CACHE_LOCK.unlock(); }
    }

    private boolean plottedChunksContainsKey0(ChunkCoordinate chunkCoordinate) {
        ChunkCoordinate regionCoord = chunkCoordinate.toRegionCoord();
        PlottedChunksRegion chunkRegion = this.plottedChunks.get(regionCoord);
        return chunkRegion != null && chunkRegion.getChunk(chunkCoordinate.getRegionInternalX(), chunkCoordinate.getRegionInternalZ());
    }

    private void addToPlottedChunks(ChunkCoordinate chunkCoordinate) {
        CACHE_LOCK.lock();
        try { addToPlottedChunks0(chunkCoordinate); } finally { CACHE_LOCK.unlock(); }
    }

    private void addToPlottedChunks0(ChunkCoordinate chunkCoordinate) {
        ChunkCoordinate regionCoord = chunkCoordinate.toRegionCoord();
        PlottedChunksRegion chunkRegion = this.plottedChunks.get(regionCoord);
        if (chunkRegion == null) {
            chunkRegion = new PlottedChunksRegion();
            this.plottedChunks.put(regionCoord, chunkRegion);
        }
        chunkRegion.setChunk(chunkCoordinate.getRegionInternalX(), chunkCoordinate.getRegionInternalZ());
    }

    public boolean isBo4ChunkPlotted(LocalWorld world, ChunkCoordinate chunkCoordinate) {
        CACHE_LOCK.lock();
        try { return isBo4ChunkPlotted0(world, chunkCoordinate); } finally { CACHE_LOCK.unlock(); }
    }

    private boolean isBo4ChunkPlotted0(LocalWorld world, ChunkCoordinate chunkCoordinate) {
        boolean bFound;
        boolean bl = bFound = world.isInsidePregeneratedRegion(chunkCoordinate) || this.plottedChunksFastCache.containsKey(chunkCoordinate) || this.plottedChunksContainsKey(chunkCoordinate);
        if (bFound) {
            this.plottedChunksFastCache.put(chunkCoordinate, Boolean.TRUE);
        }
        return bFound;
    }

    public void addBo4ToStructureCache(ChunkCoordinate chunkCoordinate, BO4CustomStructure structure) {
        CACHE_LOCK.lock();
        try { addBo4ToStructureCache0(chunkCoordinate, structure); } finally { CACHE_LOCK.unlock(); }
    }

    private void addBo4ToStructureCache0(ChunkCoordinate chunkCoordinate, BO4CustomStructure structure) {
        this.addToStructureCache(chunkCoordinate, structure);
        this.addToPlottedChunks(chunkCoordinate);
        this.setChunkOccupied(chunkCoordinate);
    }

    public void spawnBO4Chunk(ChunkCoordinate chunkCoordinate, LocalWorld world, ChunkCoordinate chunkBeingPopulated) {
        java.util.concurrent.locks.ReentrantLock lock = regionLock(chunkCoordinate);
        lock.lock();
        try { spawnBO4Chunk0(chunkCoordinate, world, chunkBeingPopulated); } finally { lock.unlock(); }
    }

    private void spawnBO4Chunk0(ChunkCoordinate chunkCoordinate, LocalWorld world, ChunkCoordinate chunkBeingPopulated) {
        BO4CustomStructure structureStart = this.getFromStructureCache(chunkCoordinate);
        if (structureStart != null && structureStart.start != null) {
            structureStart.spawnInChunk(chunkCoordinate, world, chunkBeingPopulated);
        } else if (structureStart != null && structureStart.start == null) {
            throw new RuntimeException("This shouldn't happen, please contact Team OTG about this crash.");
        }
        this.removeFromStructureCache(chunkCoordinate);
        this.setChunkOccupied(chunkCoordinate);
    }

    public BO4CustomStructure plotStructures(CustomStructureCache structureCache, LocalWorld world, Random rand, ChunkCoordinate chunkCoord) {
        return this.plotStructures(structureCache, null, null, world, rand, chunkCoord);
    }

    public BO4CustomStructure plotStructures(CustomStructureCache structureCache, BO4 targetStructure, ArrayList<String> targetBiomes, LocalWorld world, Random rand, ChunkCoordinate chunkCoord) {
        return this.plotStructures(structureCache, targetStructure, targetBiomes, world, rand, chunkCoord, false);
    }

    private int getStructureCount() {
        CACHE_LOCK.lock();
        try { return spawnedStructuresByName.size(); } finally { CACHE_LOCK.unlock(); }
    }

    private int getStructureCount0() {
        return this.spawnedStructuresByName.entrySet().size();
    }

    private void setChunkOccupied(ChunkCoordinate chunkCoord) {
        CACHE_LOCK.lock();
        try { setChunkOccupied0(chunkCoord); } finally { CACHE_LOCK.unlock(); }
    }

    private void setChunkOccupied0(ChunkCoordinate chunkCoord) {
        this.namesPut(chunkCoord, new ArrayList());
        this.plottedChunksFastCache.put(chunkCoord, Boolean.TRUE);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private BO4CustomStructure plotStructures(CustomStructureCache structureCache, BO4 targetStructure, ArrayList<String> targetBiomes, LocalWorld world, Random rand, ChunkCoordinate chunkCoord, boolean spawningStructureAtSpawn) {
        if (!spawningStructureAtSpawn && this.structurePlottedAtSpawn.compareAndSet(false, true)) {
            ChunkCoordinate spawn = world.getSpawnChunk();
            if (!chunkCoord.equals(spawn)) {
                // Runs before this thread takes its own coord lock: no nested coord locks.
                this.plotStructures(structureCache, targetStructure, targetBiomes, world, rand, spawn, true);
            }
        }
        java.util.concurrent.locks.ReentrantLock lock = regionLock(chunkCoord);
        lock.lock();
        try { return plotStructures0(structureCache, targetStructure, targetBiomes, world, rand, chunkCoord, spawningStructureAtSpawn); } finally { lock.unlock(); }
    }

    private BO4CustomStructure plotStructures0(CustomStructureCache structureCache, BO4 targetStructure, ArrayList<String> targetBiomes, LocalWorld world, Random rand, ChunkCoordinate chunkCoord, boolean spawningStructureAtSpawn) {
        if (!this.isBo4ChunkPlotted(world, chunkCoord)) {
            LocalBiome biome = world.getBiome(chunkCoord.getBlockX() + 8, chunkCoord.getBlockZ() + 7);
            BiomeConfig biomeConfig = biome.getBiomeConfig();
            ArrayList<CustomStructureGen> customStructureGens = new ArrayList<CustomStructureGen>();
            if (targetStructure == null && !this.chunkHasDefaultStructureSafe(world, rand, chunkCoord)) {
                for (CustomStructureGen customStructureGen : biomeConfig.getCustomStructures()) {
                    customStructureGens.add(customStructureGen);
                }
            }
            if (targetStructure != null || customStructureGens.size() > 0) {
                HashMap<StructuredCustomObject, Double> structuredCustomObjects = new HashMap<StructuredCustomObject, Double>();
                if (targetStructure != null) {
                    if (targetBiomes.size() == 0 || targetBiomes.contains(biome.getName())) {
                        structuredCustomObjects.put(targetStructure, 100.0);
                    }
                } else {
                    for (CustomStructureGen customStructureGen : customStructureGens) {
                        int i = 0;
                        for (StructuredCustomObject structure : customStructureGen.getObjects(world.getName())) {
                            if (structure == null) continue;
                            if (structure instanceof BO4) {
                                structuredCustomObjects.put(structure, customStructureGen.objectChances.get(i));
                                ++i;
                                continue;
                            }
                            if (!OTG.getPluginConfig().spawnLog) continue;
                            OTG.log(LogMarker.WARN, "CustomStructure " + structure.getName() + " in biome " + biome.getName() + " has IsOTGPlus:false, ignoring.", new Object[0]);
                        }
                    }
                }
                if (structuredCustomObjects.size() > 0) {
                    double BO3size;
                    Object[] topLeftAndLowerRightChunkCoordinates;
                    Object var12_17 = null;
                    Object var13_25 = null;
                    ArrayList<Object[]> bo4sBySize = new ArrayList<Object[]>();
                    ArrayList<String> structuresToSpawn1 = new ArrayList<String>();
                    if (targetStructure == null && spawningStructureAtSpawn) {
                        for (Map.Entry bo4AndRarity : structuredCustomObjects.entrySet()) {
                            if (((BO4)bo4AndRarity.getKey()).isInvalidConfig || !((BO4)bo4AndRarity.getKey()).getConfig().isSpawnPoint) continue;
                            structuresToSpawn1.add(((StructuredCustomObject)bo4AndRarity.getKey()).getName());
                            BO4CustomStructureCoordinate bO4CustomStructureCoordinate = new BO4CustomStructureCoordinate(world, (StructuredCustomObject)bo4AndRarity.getKey(), null, Rotation.NORTH, (short)chunkCoord.getBlockX(), (short)0, (short)chunkCoord.getBlockZ(), 0, false, false, null);
                            BO4CustomStructure bO4CustomStructure = new BO4CustomStructure(world, bO4CustomStructureCoordinate);
                            try {
                                topLeftAndLowerRightChunkCoordinates = bO4CustomStructure.getMinimumSize(world);
                                BO3size = Math.abs((Integer)topLeftAndLowerRightChunkCoordinates[0] - -((Integer)topLeftAndLowerRightChunkCoordinates[2]).intValue()) * Math.abs((Integer)topLeftAndLowerRightChunkCoordinates[1] - -((Integer)topLeftAndLowerRightChunkCoordinates[3]).intValue());
                                bo4sBySize.add(new Object[]{bo4AndRarity.getKey(), topLeftAndLowerRightChunkCoordinates, BO3size, bo4AndRarity.getValue()});
                            }
                            catch (InvalidConfigException e) {
                                ((BO4)bo4AndRarity.getKey()).isInvalidConfig = true;
                            }
                        }
                    }
                    if (!spawningStructureAtSpawn || bo4sBySize.size() == 0) {
                        if (spawningStructureAtSpawn && bo4sBySize.size() == 0) {
                            spawningStructureAtSpawn = false;
                        }
                        for (Map.Entry bo4AndRarity : structuredCustomObjects.entrySet()) {
                            if (((BO4)bo4AndRarity.getKey()).isInvalidConfig || (int)Math.round((Double)bo4AndRarity.getValue()) <= 0 || !this.isBO4AllowedToSpawnAtByFrequency(chunkCoord, (BO4)bo4AndRarity.getKey())) continue;
                            structuresToSpawn1.add(((StructuredCustomObject)bo4AndRarity.getKey()).getName());
                            BO4CustomStructureCoordinate bO4CustomStructureCoordinate = new BO4CustomStructureCoordinate(world, (StructuredCustomObject)bo4AndRarity.getKey(), null, Rotation.NORTH, (short)chunkCoord.getBlockX(), (short)0, (short)chunkCoord.getBlockZ(), 0, false, false, null);
                            BO4CustomStructure bO4CustomStructure = new BO4CustomStructure(world, bO4CustomStructureCoordinate);
                            try {
                                topLeftAndLowerRightChunkCoordinates = bO4CustomStructure.getMinimumSize(world);
                                BO3size = Math.abs((Integer)topLeftAndLowerRightChunkCoordinates[0] - -((Integer)topLeftAndLowerRightChunkCoordinates[2]).intValue()) * Math.abs((Integer)topLeftAndLowerRightChunkCoordinates[1] - -((Integer)topLeftAndLowerRightChunkCoordinates[3]).intValue());
                                int insertAtIndex = bo4sBySize.size();
                                int i = 0;
                                for (Object[] entry : bo4sBySize) {
                                    if (((BO4)bo4AndRarity.getKey()).getConfig().timesSpawned < ((BO4)entry[0]).getConfig().timesSpawned || BO3size > (Double)entry[2] && ((BO4)bo4AndRarity.getKey()).getConfig().timesSpawned == ((BO4)entry[0]).getConfig().timesSpawned) {
                                        insertAtIndex = i;
                                        break;
                                    }
                                    ++i;
                                }
                                bo4sBySize.add(insertAtIndex, new Object[]{bo4AndRarity.getKey(), topLeftAndLowerRightChunkCoordinates, BO3size, bo4AndRarity.getValue()});
                            }
                            catch (InvalidConfigException e) {
                                ((BO4)bo4AndRarity.getKey()).isInvalidConfig = true;
                            }
                        }
                    }
                    if (bo4sBySize.size() > 0) {
                        block16: for (Object[] currentStructureSpawning : bo4sBySize) {
                            int pass = 0;
                            int left = 0;
                            int right = 0;
                            int top = 0;
                            int bottom = 0;
                            int areaLength = 0;
                            int areaWidth = 0;
                            Object[] topLeftAndLowerRightChunkCoordinates2 = (Object[])currentStructureSpawning[1];
                            int structureLength = (Integer)topLeftAndLowerRightChunkCoordinates2[1] + (Integer)topLeftAndLowerRightChunkCoordinates2[3] + 1;
                            int structureWidth = (Integer)topLeftAndLowerRightChunkCoordinates2[0] + (Integer)topLeftAndLowerRightChunkCoordinates2[2] + 1;
                            int structureTop = (Integer)topLeftAndLowerRightChunkCoordinates2[0];
                            int structureRight = (Integer)topLeftAndLowerRightChunkCoordinates2[1];
                            int structureBottom = (Integer)topLeftAndLowerRightChunkCoordinates2[2];
                            int structureLeft = (Integer)topLeftAndLowerRightChunkCoordinates2[3];
                            while (pass <= 4) {
                                int spawnCoordZ;
                                int spawnCoordX;
                                ChunkCoordinate spawnChunk;
                                ++pass;
                                if ((int)Math.round((Double)currentStructureSpawning[3]) <= 0 && (!spawningStructureAtSpawn || !((BO4)currentStructureSpawning[0]).getConfig().isSpawnPoint)) continue;
                                left = 0;
                                right = 0;
                                top = 0;
                                bottom = 0;
                                boolean leftEdgeFound = false;
                                boolean rightEdgeFound = false;
                                boolean topEdgeFound = false;
                                boolean bottomEdgeFound = false;
                                if (pass == 1) {
                                    topEdgeFound = true;
                                    rightEdgeFound = true;
                                }
                                if (pass == 2) {
                                    topEdgeFound = true;
                                    leftEdgeFound = true;
                                }
                                if (pass == 3) {
                                    bottomEdgeFound = true;
                                    rightEdgeFound = true;
                                }
                                if (pass == 4) {
                                    bottomEdgeFound = true;
                                    leftEdgeFound = true;
                                }
                                int scanDistance = 0;
                                Rotation fixedRotation = ((BO4)currentStructureSpawning[0]).getConfig().fixedRotation;
                                if (!spawningStructureAtSpawn) {
                                    while (!(leftEdgeFound && rightEdgeFound && topEdgeFound && bottomEdgeFound)) {
                                        ArrayList<String> structuresToSpawn;
                                        BiomeConfig biomeConfig3;
                                        ArrayList biomeStructures;
                                        LocalBiome biome3;
                                        boolean canSpawnHere;
                                        int i;
                                        ++scanDistance;
                                        if (!rightEdgeFound && (fixedRotation == null && right + left + 1 >= structureWidth && right + left + 1 >= structureLength || (fixedRotation == Rotation.NORTH || fixedRotation == Rotation.SOUTH) && right + left + 1 >= structureWidth || (fixedRotation == Rotation.EAST || fixedRotation == Rotation.WEST) && right + left + 1 >= structureLength)) {
                                            rightEdgeFound = true;
                                        }
                                        if (!rightEdgeFound) {
                                            for (i = -top; i <= bottom; ++i) {
                                                canSpawnHere = false;
                                                if (!world.isInsidePregeneratedRegion(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() + scanDistance, chunkCoord.getChunkZ() + i))) {
                                                    if (targetStructure != null) {
                                                        if (!world.chunkHasDefaultStructure(rand, chunkCoord)) {
                                                            if (targetBiomes.size() == 0) {
                                                                canSpawnHere = true;
                                                            } else {
                                                                biome3 = world.getBiome((chunkCoord.getChunkX() + scanDistance) * 16 + 8, (chunkCoord.getChunkZ() + i) * 16 + 7);
                                                                if (targetBiomes.contains(biome3.getName())) {
                                                                    canSpawnHere = true;
                                                                }
                                                            }
                                                        }
                                                    } else {
                                                        biomeStructures = (ArrayList)this.namesGet(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() + scanDistance, chunkCoord.getChunkZ() + i));
                                                        if (biomeStructures == null) {
                                                            if (!world.chunkHasDefaultStructure(rand, chunkCoord)) {
                                                                biome3 = world.getBiome((chunkCoord.getChunkX() + scanDistance) * 16 + 8, (chunkCoord.getChunkZ() + i) * 16 + 7);
                                                                if (!biome3.getName().equals(biome.getName())) {
                                                                    biomeConfig3 = biome3.getBiomeConfig();
                                                                    structuresToSpawn = new ArrayList<String>();
                                                                    for (CustomStructureGen res : biomeConfig3.getCustomStructures()) {
                                                                        for (String bo4Name : res.objectNames) {
                                                                            structuresToSpawn.add(bo4Name);
                                                                        }
                                                                    }
                                                                    biomeStructures = structuresToSpawn;
                                                                } else {
                                                                    canSpawnHere = true;
                                                                    biomeStructures = structuresToSpawn1;
                                                                }
                                                            } else {
                                                                biomeStructures = new ArrayList();
                                                            }
                                                            this.namesPut(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() + scanDistance, chunkCoord.getChunkZ() + i), biomeStructures);
                                                        }
                                                        if (biomeStructures != null) {
                                                            for (String structureToSpawn : (java.util.ArrayList<String>) biomeStructures) {
                                                                if (!structureToSpawn.equals(((BO4)currentStructureSpawning[0]).getName())) continue;
                                                                canSpawnHere = true;
                                                                break;
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    this.namesRemove(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() + scanDistance, chunkCoord.getChunkZ() + i));
                                                }
                                                if (canSpawnHere) continue;
                                                rightEdgeFound = true;
                                            }
                                            if (!rightEdgeFound) {
                                                ++right;
                                            }
                                        }
                                        if (!leftEdgeFound && (fixedRotation == null && right + left + 1 >= structureWidth && right + left + 1 >= structureLength || (fixedRotation == Rotation.NORTH || fixedRotation == Rotation.SOUTH) && right + left + 1 >= structureWidth || (fixedRotation == Rotation.EAST || fixedRotation == Rotation.WEST) && right + left + 1 >= structureLength)) {
                                            leftEdgeFound = true;
                                        }
                                        if (!leftEdgeFound) {
                                            for (i = -top; i <= bottom; ++i) {
                                                canSpawnHere = false;
                                                if (!world.isInsidePregeneratedRegion(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() - scanDistance, chunkCoord.getChunkZ() + i))) {
                                                    if (targetStructure != null) {
                                                        if (!world.chunkHasDefaultStructure(rand, chunkCoord)) {
                                                            if (targetBiomes.size() == 0) {
                                                                canSpawnHere = true;
                                                            } else {
                                                                biome3 = world.getBiome((chunkCoord.getChunkX() - scanDistance) * 16 + 8, (chunkCoord.getChunkZ() + i) * 16 + 7);
                                                                if (targetBiomes.contains(biome3.getName())) {
                                                                    canSpawnHere = true;
                                                                }
                                                            }
                                                        }
                                                    } else {
                                                        biomeStructures = (ArrayList)this.namesGet(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() - scanDistance, chunkCoord.getChunkZ() + i));
                                                        if (biomeStructures == null) {
                                                            if (!world.chunkHasDefaultStructure(rand, chunkCoord)) {
                                                                biome3 = world.getBiome((chunkCoord.getChunkX() - scanDistance) * 16 + 8, (chunkCoord.getChunkZ() + i) * 16 + 7);
                                                                if (!biome3.getName().equals(biome.getName())) {
                                                                    biomeConfig3 = biome3.getBiomeConfig();
                                                                    structuresToSpawn = new ArrayList();
                                                                    for (CustomStructureGen res : biomeConfig3.getCustomStructures()) {
                                                                        for (String bo4Name : res.objectNames) {
                                                                            structuresToSpawn.add(bo4Name);
                                                                        }
                                                                    }
                                                                    biomeStructures = structuresToSpawn;
                                                                } else {
                                                                    canSpawnHere = true;
                                                                    biomeStructures = structuresToSpawn1;
                                                                }
                                                            } else {
                                                                biomeStructures = new ArrayList();
                                                            }
                                                            this.namesPut(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() - scanDistance, chunkCoord.getChunkZ() + i), biomeStructures);
                                                        }
                                                        if (biomeStructures != null) {
                                                            for (String structureToSpawn : (java.util.ArrayList<String>) biomeStructures) {
                                                                if (!structureToSpawn.equals(((BO4)currentStructureSpawning[0]).getName())) continue;
                                                                canSpawnHere = true;
                                                                break;
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    this.namesRemove(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() - scanDistance, chunkCoord.getChunkZ() + i));
                                                }
                                                if (canSpawnHere) continue;
                                                leftEdgeFound = true;
                                            }
                                            if (!leftEdgeFound) {
                                                ++left;
                                            }
                                        }
                                        if (!bottomEdgeFound && (fixedRotation == null && bottom + top + 1 >= structureWidth && bottom + top + 1 >= structureLength || (fixedRotation == Rotation.NORTH || fixedRotation == Rotation.SOUTH) && bottom + top + 1 >= structureLength || (fixedRotation == Rotation.EAST || fixedRotation == Rotation.WEST) && bottom + top + 1 >= structureWidth)) {
                                            bottomEdgeFound = true;
                                        }
                                        if (!bottomEdgeFound) {
                                            for (i = -left; i <= right; ++i) {
                                                canSpawnHere = false;
                                                if (!world.isInsidePregeneratedRegion(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() + i, chunkCoord.getChunkZ() + scanDistance))) {
                                                    if (targetStructure != null) {
                                                        if (!world.chunkHasDefaultStructure(rand, chunkCoord)) {
                                                            if (targetBiomes.size() == 0) {
                                                                canSpawnHere = true;
                                                            } else {
                                                                biome3 = world.getBiome((chunkCoord.getChunkX() + i) * 16 + 8, (chunkCoord.getChunkZ() + scanDistance) * 16 + 7);
                                                                if (targetBiomes.contains(biome3.getName())) {
                                                                    canSpawnHere = true;
                                                                }
                                                            }
                                                        }
                                                    } else {
                                                        biomeStructures = (ArrayList)this.namesGet(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() + i, chunkCoord.getChunkZ() + scanDistance));
                                                        if (biomeStructures == null) {
                                                            if (!world.chunkHasDefaultStructure(rand, chunkCoord)) {
                                                                biome3 = world.getBiome((chunkCoord.getChunkX() + i) * 16 + 8, (chunkCoord.getChunkZ() + scanDistance) * 16 + 7);
                                                                if (!biome3.getName().equals(biome.getName())) {
                                                                    biomeConfig3 = biome3.getBiomeConfig();
                                                                    structuresToSpawn = new ArrayList();
                                                                    for (CustomStructureGen res : biomeConfig3.getCustomStructures()) {
                                                                        for (String bo4Name : res.objectNames) {
                                                                            structuresToSpawn.add(bo4Name);
                                                                        }
                                                                    }
                                                                    biomeStructures = structuresToSpawn;
                                                                } else {
                                                                    canSpawnHere = true;
                                                                    biomeStructures = structuresToSpawn1;
                                                                }
                                                            } else {
                                                                biomeStructures = new ArrayList();
                                                            }
                                                            this.namesPut(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() + i, chunkCoord.getChunkZ() + scanDistance), biomeStructures);
                                                        }
                                                        if (biomeStructures != null) {
                                                            for (String structureToSpawn : (java.util.ArrayList<String>) biomeStructures) {
                                                                if (!structureToSpawn.equals(((BO4)currentStructureSpawning[0]).getName())) continue;
                                                                canSpawnHere = true;
                                                                break;
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    this.namesRemove(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() + i, chunkCoord.getChunkZ() + scanDistance));
                                                }
                                                if (canSpawnHere) continue;
                                                bottomEdgeFound = true;
                                            }
                                            if (!bottomEdgeFound) {
                                                ++bottom;
                                            }
                                        }
                                        if (!topEdgeFound && (fixedRotation == null && bottom + top + 1 >= structureWidth && bottom + top + 1 >= structureLength || (fixedRotation == Rotation.NORTH || fixedRotation == Rotation.SOUTH) && bottom + top + 1 >= structureLength || (fixedRotation == Rotation.EAST || fixedRotation == Rotation.WEST) && bottom + top + 1 >= structureWidth)) {
                                            topEdgeFound = true;
                                        }
                                        if (topEdgeFound) continue;
                                        for (i = -left; i <= right; ++i) {
                                            canSpawnHere = false;
                                            if (!world.isInsidePregeneratedRegion(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() + i, chunkCoord.getChunkZ() - scanDistance))) {
                                                if (targetStructure != null) {
                                                    if (!world.chunkHasDefaultStructure(rand, chunkCoord)) {
                                                        if (targetBiomes.size() == 0) {
                                                            canSpawnHere = true;
                                                        } else {
                                                            biome3 = world.getBiome((chunkCoord.getChunkX() + i) * 16 + 8, (chunkCoord.getChunkZ() - scanDistance) * 16 + 7);
                                                            if (targetBiomes.contains(biome3.getName())) {
                                                                canSpawnHere = true;
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    biomeStructures = (ArrayList)this.namesGet(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() + i, chunkCoord.getChunkZ() - scanDistance));
                                                    if (biomeStructures == null) {
                                                        if (!world.chunkHasDefaultStructure(rand, chunkCoord)) {
                                                            biome3 = world.getBiome((chunkCoord.getChunkX() + i) * 16 + 8, (chunkCoord.getChunkZ() - scanDistance) * 16 + 7);
                                                            if (!biome3.getName().equals(biome.getName())) {
                                                                biomeConfig3 = biome3.getBiomeConfig();
                                                                structuresToSpawn = new ArrayList();
                                                                for (CustomStructureGen res : biomeConfig3.getCustomStructures()) {
                                                                    for (String bo4Name : res.objectNames) {
                                                                        structuresToSpawn.add(bo4Name);
                                                                    }
                                                                }
                                                                biomeStructures = structuresToSpawn;
                                                            } else {
                                                                canSpawnHere = true;
                                                                biomeStructures = structuresToSpawn1;
                                                            }
                                                        } else {
                                                            biomeStructures = new ArrayList();
                                                        }
                                                        this.namesPut(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() + i, chunkCoord.getChunkZ() - scanDistance), biomeStructures);
                                                    }
                                                    if (biomeStructures != null) {
                                                        for (String structureToSpawn : (java.util.ArrayList<String>) biomeStructures) {
                                                            if (!structureToSpawn.equals(((BO4)currentStructureSpawning[0]).getName())) continue;
                                                            canSpawnHere = true;
                                                            break;
                                                        }
                                                    }
                                                }
                                            } else {
                                                this.namesRemove(ChunkCoordinate.fromChunkCoords(chunkCoord.getChunkX() + i, chunkCoord.getChunkZ() - scanDistance));
                                            }
                                            if (canSpawnHere) continue;
                                            topEdgeFound = true;
                                        }
                                        if (topEdgeFound) continue;
                                        ++top;
                                    }
                                } else {
                                    left = (int)Math.ceil((double)((Integer)topLeftAndLowerRightChunkCoordinates2[3] + (Integer)topLeftAndLowerRightChunkCoordinates2[1]) / 2.0);
                                    right = (int)Math.floor((double)((Integer)topLeftAndLowerRightChunkCoordinates2[3] + (Integer)topLeftAndLowerRightChunkCoordinates2[1]) / 2.0);
                                    top = (int)Math.ceil((double)((Integer)topLeftAndLowerRightChunkCoordinates2[0] + (Integer)topLeftAndLowerRightChunkCoordinates2[2]) / 2.0);
                                    bottom = (int)Math.floor((double)((Integer)topLeftAndLowerRightChunkCoordinates2[0] + (Integer)topLeftAndLowerRightChunkCoordinates2[2]) / 2.0);
                                }
                                areaLength = left + right + 1;
                                areaWidth = top + bottom + 1;
                                if ((fixedRotation != null && fixedRotation != Rotation.NORTH && fixedRotation != Rotation.SOUTH || structureLength > areaLength || structureWidth > areaWidth) && (fixedRotation != null && fixedRotation != Rotation.EAST && fixedRotation != Rotation.WEST || structureLength > areaWidth || structureWidth > areaLength)) continue;
                                Rotation rotation = fixedRotation;
                                if (rotation == null) {
                                    boolean canSpawnUnrotated = false;
                                    boolean canSpawnRotated = false;
                                    if (structureLength <= areaLength && structureWidth <= areaWidth) {
                                        canSpawnUnrotated = true;
                                    }
                                    if (structureLength <= areaWidth && structureWidth <= areaLength) {
                                        canSpawnRotated = true;
                                    }
                                    Rotation rotation2 = rotation = rand.nextBoolean() ? Rotation.NORTH : Rotation.SOUTH;
                                    if (canSpawnUnrotated && canSpawnRotated) {
                                        rotation = Rotation.getRandomRotation(rand);
                                    } else if (canSpawnRotated) {
                                        rotation = rand.nextBoolean() ? Rotation.EAST : Rotation.WEST;
                                    }
                                }
                                int structureLengthRotated = 0;
                                int structureWidthRotated = 0;
                                if (rotation == Rotation.NORTH || rotation == Rotation.SOUTH) {
                                    structureLengthRotated = structureLength;
                                    structureWidthRotated = structureWidth;
                                } else {
                                    structureLengthRotated = structureWidth;
                                    structureWidthRotated = structureLength;
                                }
                                int structureBBInsideAreaX = (int)Math.floor((double)(chunkCoord.getChunkX() - left) + (double)(left + right + 1) / 2.0 - (double)structureLengthRotated / 2.0);
                                if (structureBBInsideAreaX > chunkCoord.getChunkX()) {
                                    structureBBInsideAreaX = chunkCoord.getChunkX();
                                } else if (structureBBInsideAreaX + structureLengthRotated < chunkCoord.getChunkX()) {
                                    structureBBInsideAreaX = chunkCoord.getChunkX() - structureLengthRotated + 1;
                                }
                                int structureBBInsideAreaZ = (int)Math.floor((double)(chunkCoord.getChunkZ() - top) + (double)(top + bottom + 1) / 2.0 - (double)structureWidthRotated / 2.0);
                                if (structureBBInsideAreaZ > chunkCoord.getChunkZ()) {
                                    structureBBInsideAreaZ = chunkCoord.getChunkZ();
                                } else if (structureBBInsideAreaZ + structureWidthRotated < chunkCoord.getChunkZ()) {
                                    structureBBInsideAreaZ = chunkCoord.getChunkZ() - structureWidthRotated + 1;
                                }
                                if (!this.isBO4AllowedToSpawnAtByFrequency(spawnChunk = ChunkCoordinate.fromChunkCoords(spawnCoordX = structureBBInsideAreaX + (rotation == Rotation.NORTH ? structureLeft : (rotation == Rotation.EAST ? structureBottom : (rotation == Rotation.SOUTH ? structureRight : structureTop))), spawnCoordZ = structureBBInsideAreaZ + (rotation == Rotation.NORTH ? structureTop : (rotation == Rotation.EAST ? structureLeft : (rotation == Rotation.SOUTH ? structureBottom : structureRight)))), (BO4)currentStructureSpawning[0])) continue block16;
                                BO4CustomStructureCoordinate bO4CustomStructureCoordinate = new BO4CustomStructureCoordinate(world, (BO4)currentStructureSpawning[0], null, rotation, (short)(spawnCoordX * 16), (short)0, (short)(spawnCoordZ * 16), 0, false, false, null);
                                BO4CustomStructure bO4CustomStructure = new BO4CustomStructure(world, bO4CustomStructureCoordinate, spawningStructureAtSpawn, targetBiomes, chunkCoord);
                                if (!bO4CustomStructure.IsSpawned) continue block16;
                                structureCache.addBo4ToStructureCache(spawnChunk, bO4CustomStructure);
                                CACHE_LOCK.lock();
                                try { ++((BO4)bO4CustomStructureCoordinate.getObject()).getConfig().timesSpawned; } finally { CACHE_LOCK.unlock(); }
                                if (OTG.getPluginConfig().spawnLog) {
                                    OTG.log(LogMarker.INFO, "Plotted structure " + bO4CustomStructureCoordinate.getObject().getName() + " at chunk " + spawnCoordX + " " + spawnCoordZ + " (" + spawnCoordX * 16 + " 100 " + spawnCoordZ * 16 + ")", new Object[0]);
                                }
                                CACHE_LOCK.lock();
                                try {
                                if (((BO4)currentStructureSpawning[0]).getConfig().frequency > 0 || ((BO4)currentStructureSpawning[0]).getConfig().bo4Groups.size() > 0) {
                                    String bO3Name = ((BO4)currentStructureSpawning[0]).getName();
                                    ChunkCoordinate bo4SpawnCoord = ChunkCoordinate.fromChunkCoords(spawnCoordX, spawnCoordZ);
                                    ArrayList<ChunkCoordinate> chunkCoords = this.spawnedStructuresByName.get(bO3Name);
                                    if (chunkCoords == null) {
                                        chunkCoords = new ArrayList();
                                        this.spawnedStructuresByName.put(bO3Name, chunkCoords);
                                    }
                                    chunkCoords.add(bo4SpawnCoord);
                                    if (((BO4)currentStructureSpawning[0]).getConfig().bo4Groups.size() > 0) {
                                        int structureCenterX = structureBBInsideAreaX + (int)Math.floor((double)(rotation == Rotation.NORTH || rotation == Rotation.SOUTH ? structureLeft + structureRight + 1 : structureBottom + structureTop + 1) / 2.0);
                                        int structureCenterZ = structureBBInsideAreaZ + (int)Math.floor((double)(rotation == Rotation.NORTH || rotation == Rotation.SOUTH ? structureTop + structureBottom + 1 : structureLeft + structureRight + 1) / 2.0);
                                        ChunkCoordinate bo4CenterSpawnCoord = ChunkCoordinate.fromChunkCoords(structureCenterX, structureCenterZ);
                                        for (Map.Entry<String, Integer> entry : ((BO4)currentStructureSpawning[0]).getConfig().bo4Groups.entrySet()) {
                                            String bo4GroupName = entry.getKey();
                                            int bo4GroupFrequency = entry.getValue();
                                            if (bo4GroupFrequency <= 0) continue;
                                            HashMap<ChunkCoordinate, Integer> spawnedStructures = this.spawnedStructuresByGroup.get(bo4GroupName);
                                            if (spawnedStructures == null) {
                                                spawnedStructures = new HashMap();
                                                spawnedStructures.put(bo4CenterSpawnCoord, entry.getValue());
                                                this.spawnedStructuresByGroup.put(bo4GroupName, spawnedStructures);
                                                continue;
                                            }
                                            Integer frequency = spawnedStructures.get(bo4CenterSpawnCoord);
                                            if (frequency != null) {
                                                if (frequency >= bo4GroupFrequency) continue;
                                                spawnedStructures.put(bo4CenterSpawnCoord, (int)entry.getValue());
                                                continue;
                                            }
                                            spawnedStructures.put(bo4CenterSpawnCoord, (int)entry.getValue());
                                        }
                                    }
                                }
                                } finally {
                                    CACHE_LOCK.unlock();
                                }
                                if (!this.structureCacheContainsKey(chunkCoord) && targetStructure == null) continue block16;
                                                        return bO4CustomStructure;
                            }
                        }
                    }
                }
            }
        }
        this.addToPlottedChunks(chunkCoord);
        this.setChunkOccupied(chunkCoord);
        return null;
    }

    private boolean chunkHasDefaultStructureSafe(LocalWorld world, Random rand, ChunkCoordinate chunkCoord) {
        com.cleanroommc.worldflow.pipeline.compat.WorldGenSafety.UNSAFE_WORLDGEN_LOCK.lock();
        try {
            return world.chunkHasDefaultStructure(rand, chunkCoord);
        } finally {
            com.cleanroommc.worldflow.pipeline.compat.WorldGenSafety.UNSAFE_WORLDGEN_LOCK.unlock();
        }
    }

    private boolean isBO4AllowedToSpawnAtByFrequency(ChunkCoordinate chunkCoord, BO4 BO3ToSpawn) {
        CACHE_LOCK.lock();
        try { return isBO4AllowedToSpawnAtByFrequency0(chunkCoord, BO3ToSpawn); } finally { CACHE_LOCK.unlock(); }
    }

    private boolean isBO4AllowedToSpawnAtByFrequency0(ChunkCoordinate chunkCoord, BO4 BO3ToSpawn) {
        float distanceBetweenStructures;
        int radius = BO3ToSpawn.getConfig().frequency;
        String bO3Name = BO3ToSpawn.getName();
        if (radius > 0) {
            distanceBetweenStructures = 0.0f;
            ArrayList<ChunkCoordinate> chunkCoords = this.spawnedStructuresByName.get(bO3Name);
            if (chunkCoords != null) {
                for (ChunkCoordinate cachedChunk : chunkCoords) {
                    distanceBetweenStructures = (int)Math.floor(Math.sqrt(Math.pow(chunkCoord.getChunkX() - cachedChunk.getChunkX(), 2.0) + Math.pow(chunkCoord.getChunkZ() - cachedChunk.getChunkZ(), 2.0)));
                    if (!(distanceBetweenStructures <= (float)radius)) continue;
                    return false;
                }
            }
        }
        if (BO3ToSpawn.getConfig().bo4Groups.size() > 0) {
            distanceBetweenStructures = 0.0f;
            int cachedChunkRadius = 0;
            ChunkCoordinate cachedChunk = null;
            for (Map.Entry<String, Integer> entry : BO3ToSpawn.getConfig().bo4Groups.entrySet()) {
                HashMap<ChunkCoordinate, Integer> spawnedStructure = this.spawnedStructuresByGroup.get(entry.getKey());
                if (spawnedStructure == null) continue;
                for (Map.Entry<ChunkCoordinate, Integer> cachedChunkEntry : spawnedStructure.entrySet()) {
                    cachedChunk = cachedChunkEntry.getKey();
                    cachedChunkRadius = cachedChunkEntry.getValue();
                    int n = radius = entry.getValue() >= cachedChunkRadius ? entry.getValue() : cachedChunkRadius;
                    distanceBetweenStructures = (int)Math.floor(Math.sqrt(Math.pow(chunkCoord.getChunkX() - cachedChunk.getChunkX(), 2.0) + Math.pow(chunkCoord.getChunkZ() - cachedChunk.getChunkZ(), 2.0)));
                    if (!(distanceBetweenStructures <= (float)radius)) continue;
                    return false;
                }
            }
        }
        return true;
    }

    public void savePlottedChunks(LocalWorld world) {
        CustomStructureFileManager.savePlottedChunksData(world, this.plottedChunks);
    }

    public void loadPlottedChunks(LocalWorld world) {
        this.plottedChunks.clear();
        this.plottedChunks.putAll(CustomStructureFileManager.loadPlottedChunksData(world));
    }

    public void saveSpawnedStructures(LocalWorld world) {
        CustomStructureFileManager.saveChunksMapFile(world, this.spawnedStructuresByName, this.spawnedStructuresByGroup);
    }

    public void loadSpawnedStructures(LocalWorld world) {
        CustomStructureFileManager.loadChunksMapFile(world, this.spawnedStructuresByName, this.spawnedStructuresByGroup);
    }

    public void saveStructureCache(LocalWorld world) {
        if (world.isBo4Enabled()) {
            this.savePlottedChunks(world);
            this.saveSpawnedStructures(world);
        }
    }

    public void loadStructureCache(LocalWorld world, Map<CustomStructure, ArrayList<ChunkCoordinate>> loadedStructures) {
        this.bo4StructureCache.clear();
        if (world.isBo4Enabled()) {
            if (loadedStructures != null) {
                for (Map.Entry<CustomStructure, ArrayList<ChunkCoordinate>> loadedStructure : loadedStructures.entrySet()) {
                    if (loadedStructure == null) {
                        throw new RuntimeException("This shouldn't happen, please ask for help on the OTG Discord and/or file an issue on the OTG github.");
                    }
                    for (ChunkCoordinate chunkCoord : ((BO4CustomStructure)loadedStructure.getKey()).objectsToSpawn.keySet()) {
                        this.addToStructureCache(chunkCoord, (BO4CustomStructure)loadedStructure.getKey());
                    }
                    for (ChunkCoordinate chunkCoord : ((BO4CustomStructure)loadedStructure.getKey()).smoothingAreaManager.getSmoothingAreaChunkCoords()) {
                        this.addToStructureCache(chunkCoord, (BO4CustomStructure)loadedStructure.getKey());
                    }
                }
            }
            this.loadPlottedChunks(world);
            this.loadSpawnedStructures(world);
            for (ChunkCoordinate chunkCoord : this.bo4StructureCache.keySet()) {
                this.setChunkOccupied(chunkCoord);
            }
            if (loadedStructures != null && loadedStructures.size() > 0 || this.getStructureCount() > 0) {
                this.structurePlottedAtSpawn.set(true);
            }
        }
        OTG.log(LogMarker.DEBUG, "Loading done", new Object[0]);
    }
}

