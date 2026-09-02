package com.moulberry.flashback.visuals;

import com.moulberry.flashback.action.PositionAndAngle;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes view bob (walkDist, bob, oBob) from AccurateEntityPositionHandler's
 * per-tick position data, bypassing Entity.aiStep() which doesn't run properly
 * for RemotePlayer during replay playback.
 */
public class ViewBobState {

    private static final Map<Integer, BobState> STATES = new HashMap<>();

    public static class BobState {
        public float walkDist = 0f;
        public float walkDistO = 0f;
        public float bob = 0f;
        public float oBob = 0f;
        public float rawDist = 0f;
        private double lastX = Double.NaN;
        private double lastZ = Double.NaN;
    }

    public static void reset() {
        STATES.clear();
    }

    /**
     * Called once per game tick from AccurateEntityPositionHandler.tick().
     * Computes per-tick bob state from position deltas.
     */
    public static void tick(Int2ObjectMap<List<PositionAndAngle>> data) {
        for (Int2ObjectMap.Entry<List<PositionAndAngle>> entry : data.int2ObjectEntrySet()) {
            int entityId = entry.getIntKey();
            List<PositionAndAngle> positions = entry.getValue();

            BobState state = STATES.computeIfAbsent(entityId, k -> new BobState());

            PositionAndAngle lastPos = positions.get(positions.size() - 1);
            double x = lastPos.x();
            double z = lastPos.z();

            if (!Double.isNaN(state.lastX)) {
                double dx = x - state.lastX;
                double dz = z - state.lastZ;
                float dist = (float) Math.sqrt(dx * dx + dz * dz);

                state.rawDist = dist;
                state.walkDistO = state.walkDist;
                state.walkDist += dist * 0.6f;

                state.oBob = state.bob;
                state.bob += (dist - state.bob) * 0.4f;
            }

            state.lastX = x;
            state.lastZ = z;
        }
    }

    @Nullable
    public static BobState getState(int entityId) {
        return STATES.get(entityId);
    }
}
