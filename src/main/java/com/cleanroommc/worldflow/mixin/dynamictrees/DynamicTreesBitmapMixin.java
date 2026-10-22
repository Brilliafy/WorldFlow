package com.cleanroommc.worldflow.mixin.dynamictrees;

import com.ferreusveritas.dynamictrees.util.SimpleBitmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * DynamicTrees' SimpleBitmap keeps dstOffsety/srcOffsety/runH in static fields.
 * Those are per-call scratch values, so two feature workers overwrite each other's
 * offsets and index past the bitmap. Replace the blit primitives with local scratch
 * variables; no shared state and no monitor.
 */
@Mixin(targets = "com.ferreusveritas.dynamictrees.util.SimpleBitmap", remap = false)
public abstract class DynamicTreesBitmapMixin {

    @Shadow private int h;
    @Shadow private int w;
    @Shadow private int[] bits;
    @Shadow public boolean touched;

    @Shadow public abstract int[] getBits();

    private int[] worldflow$prep(int relX, int relY, SimpleBitmap src) {
        if (relX <= -src.getW() || relX >= this.w || relY <= -src.getH() || relY >= this.h
                || (!this.touched && !src.touched)) {
            return null;
        }
        int dst;
        int srcOff;
        if (relY >= 0) {
            dst = relY;
            srcOff = 0;
        } else {
            dst = 0;
            srcOff = -relY;
        }
        int run = Math.min(this.h - dst, Math.min(src.getH(), this.h - relY) - srcOff);
        return new int[]{dst, srcOff, run};
    }

    /**
     * @author WorldFlow
     * @reason static blit scratch fields race between feature workers
     */
    @Overwrite
    public boolean isColliding(int relX, int relY, SimpleBitmap src) {
        int[] s = worldflow$prep(relX, relY, src);
        if (s == null) {
            return false;
        }
        int dst = s[0];
        int srcOff = s[1];
        int runH = s[2];
        if (relX < 0) {
            relX = -relX;
            while (runH-- > 0) {
                if ((this.bits[dst++] << relX & src.getBits()[srcOff++]) != 0) {
                    return true;
                }
            }
        } else {
            while (runH-- > 0) {
                if ((this.bits[dst++] & src.getBits()[srcOff++] << relX) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    @Overwrite
    public void BlitOr(int relX, int relY, SimpleBitmap src) {
        int[] s = worldflow$prep(relX, relY, src);
        if (s == null) return;
        int dst = s[0], srcOff = s[1], runH = s[2];
        if (relX < 0) {
            relX = -relX;
            while (runH-- > 0) {
                int n = dst++;
                this.bits[n] = this.bits[n] | src.getBits()[srcOff++] >>> relX;
            }
        } else {
            while (runH-- > 0) {
                int n = dst++;
                this.bits[n] = this.bits[n] | src.getBits()[srcOff++] << relX;
            }
        }
        this.touched = true;
    }

    @Overwrite
    public void BlitSub(int relX, int relY, SimpleBitmap src) {
        int[] s = worldflow$prep(relX, relY, src);
        if (s == null) return;
        int dst = s[0], srcOff = s[1], runH = s[2];
        if (relX < 0) {
            relX = -relX;
            while (runH-- > 0) {
                int n = dst++;
                this.bits[n] = this.bits[n] & ~(src.getBits()[srcOff++] >>> relX);
            }
        } else {
            while (runH-- > 0) {
                int n = dst++;
                this.bits[n] = this.bits[n] & ~(src.getBits()[srcOff++] << relX);
            }
        }
        this.touched = true;
    }

    @Overwrite
    public void BlitAnd(int relX, int relY, SimpleBitmap src) {
        int[] s = worldflow$prep(relX, relY, src);
        if (s == null) return;
        int dst = s[0], srcOff = s[1], runH = s[2];
        if (relX < 0) {
            relX = -relX;
            while (runH-- > 0) {
                int n = dst++;
                this.bits[n] = this.bits[n] & src.getBits()[srcOff++] >>> relX;
            }
        } else {
            while (runH-- > 0) {
                int n = dst++;
                this.bits[n] = this.bits[n] & src.getBits()[srcOff++] << relX;
            }
        }
        this.touched = true;
    }
}
