package dev.vaplus.anticheat.check.impl.world;

/**
 * Per-player mirror of the <em>other</em> entities (players, mobs, end crystals, armor stands; see
 * {@link EntityDimensions}) whose spawn/move/destroy packets the server sent to this viewer. Filled
 * by {@link NearbyPlayersTracker} from outbound entity packets; owner-thread confined like the rest
 * of {@code PlayerData}.
 *
 * <p>This is deliberately a small, module-local tracker (positions, hitbox size and the crouch /
 * invisibility flags), not a full entity tracker. Unknown entity ids are ignored, so packets for
 * entities that spawned before the tracker existed cannot inject positions.
 *
 * <p>Storage is primitive arrays with a linear scan and in-place removal; {@link #MAX_TRACKED}
 * bounds memory per viewer. When the array is full, a new player evicts a non-player entry and
 * every other spawn is ignored (detection gets weaker, never wrong).
 */
public final class NearbyPlayers {

    /** Upper bound of tracked entities per viewer. */
    public static final int MAX_TRACKED = 256;

    private final int[] ids = new int[MAX_TRACKED];
    private final double[] x = new double[MAX_TRACKED];
    private final double[] y = new double[MAX_TRACKED];
    private final double[] z = new double[MAX_TRACKED];
    /** Entity flags from the metadata packet: invisible and sneaking change the hitbox/validity. */
    private final boolean[] invisible = new boolean[MAX_TRACKED];
    private final boolean[] sneaking = new boolean[MAX_TRACKED];
    /** Whether the entity is a player (ESP judges players only; only players crouch to 1.5). */
    private final boolean[] playerEntity = new boolean[MAX_TRACKED];
    private final double[] halfWidth = new double[MAX_TRACKED];
    private final double[] baseHeight = new double[MAX_TRACKED];
    private int size;

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int id(int index) {
        return ids[index];
    }

    public double x(int index) {
        return x[index];
    }

    public double y(int index) {
        return y[index];
    }

    public double z(int index) {
        return z[index];
    }

    /** Whether the tracked entity is a player. */
    public boolean isPlayer(int index) {
        return playerEntity[index];
    }

    /** Half of the hitbox width (0.3 for players). */
    public double halfWidth(int index) {
        return halfWidth[index];
    }

    /** Hitbox height; a crouching player reports 1.5. */
    public double height(int index) {
        if (playerEntity[index] && sneaking[index]) {
            return EntityDimensions.PLAYER_SNEAKING_HEIGHT;
        }
        return baseHeight[index];
    }

    /** Y of the hitbox centre. */
    public double centerY(int index) {
        return y[index] + height(index) * 0.5;
    }

    /** Whether the tracked entity is currently invisible for this viewer. */
    public boolean invisible(int index) {
        return invisible[index];
    }

    /** Whether the tracked entity is crouching (player hitbox height 1.5 instead of 1.8). */
    public boolean sneaking(int index) {
        return sneaking[index];
    }

    /** Entity metadata: invisibility flag. Unknown ids are ignored. */
    public void setInvisible(int entityId, boolean value) {
        int index = indexOf(entityId);
        if (index >= 0) {
            invisible[index] = value;
        }
    }

    /** Entity metadata: crouching flag. Unknown ids are ignored. */
    public void setSneaking(int entityId, boolean value) {
        int index = indexOf(entityId);
        if (index >= 0) {
            sneaking[index] = value;
        }
    }

    /** Index of a tracked entity id, or {@code -1}. */
    public int indexOf(int entityId) {
        for (int i = 0; i < size; i++) {
            if (ids[i] == entityId) {
                return i;
            }
        }
        return -1;
    }

    /** A player entity was spawned for this viewer; an existing entry is replaced. */
    public void spawn(int entityId, double positionX, double positionY, double positionZ) {
        spawn(entityId, positionX, positionY, positionZ, true,
                EntityDimensions.PLAYER_WIDTH, EntityDimensions.PLAYER_HEIGHT);
    }

    /**
     * An entity with a known hitbox was spawned for this viewer; an existing entry is replaced.
     * When the array is full, a player evicts a non-player entry; other spawns are ignored.
     */
    public void spawn(int entityId, double positionX, double positionY, double positionZ,
                      boolean isPlayer, double width, double height) {
        int index = indexOf(entityId);
        if (index < 0) {
            if (size >= MAX_TRACKED) {
                int victim = isPlayer ? firstNonPlayer() : -1;
                if (victim < 0) {
                    return;
                }
                removeAt(victim);
            }
            index = size++;
            ids[index] = entityId;
            invisible[index] = false;
            sneaking[index] = false;
        }
        playerEntity[index] = isPlayer;
        halfWidth[index] = width * 0.5;
        baseHeight[index] = height;
        x[index] = positionX;
        y[index] = positionY;
        z[index] = positionZ;
    }

    private int firstNonPlayer() {
        for (int i = 0; i < size; i++) {
            if (!playerEntity[i]) {
                return i;
            }
        }
        return -1;
    }

    /** Absolute position update (teleport / position sync). Unknown ids are ignored. */
    public void moveTo(int entityId, double positionX, double positionY, double positionZ) {
        int index = indexOf(entityId);
        if (index < 0) {
            return;
        }
        x[index] = positionX;
        y[index] = positionY;
        z[index] = positionZ;
    }

    /** Relative movement in blocks. Unknown ids are ignored. */
    public void moveBy(int entityId, double deltaX, double deltaY, double deltaZ) {
        int index = indexOf(entityId);
        if (index < 0) {
            return;
        }
        x[index] += deltaX;
        y[index] += deltaY;
        z[index] += deltaZ;
    }

    /** The entity left the viewer's world (destroy packet). */
    public boolean remove(int entityId) {
        int index = indexOf(entityId);
        if (index < 0) {
            return false;
        }
        removeAt(index);
        return true;
    }

    private void removeAt(int index) {
        int last = --size;
        if (index != last) {
            ids[index] = ids[last];
            x[index] = x[last];
            y[index] = y[last];
            z[index] = z[last];
            invisible[index] = invisible[last];
            sneaking[index] = sneaking[last];
            playerEntity[index] = playerEntity[last];
            halfWidth[index] = halfWidth[last];
            baseHeight[index] = baseHeight[last];
        }
    }

    /** Join/respawn: every entity id of the previous world becomes meaningless. */
    public void clear() {
        size = 0;
    }
}
