package com.minecolonies.core.colony.npc;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.util.CreativeBuildingStructureHandler;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.Colony;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Drives growth of NPC-managed colonies. Called from {@link Colony#onWorldTick} once per server tick.
 *
 * <p>The ticker only runs when {@link Colony#isNpcManaged()} is true. Each tick adds a small amount
 * of growth points (scaled by current citizen count) and once the threshold is crossed the lowest-tier
 * eligible building is upgraded by 1 level via the existing creative blueprint placement pipeline
 * ({@link CreativeBuildingStructureHandler#loadAndPlaceStructureWithRotation}). No worker (builder) is
 * required — this is a purely server-side, automatic growth path.</p>
 */
public final class NpcColonyGrowthTicker
{
    /**
     * Building expansion plan for an NPC-managed colony. Each entry is a {@link PlannedBuilding}
     * describing a level-1 blueprint to creative-place at a fixed offset from the town-hall.
     *
     * <p>Order matters — earlier entries are placed first. Citizens auto-hire to these buildings
     * because the colony's town-hall has {@code AUTO_HIRING_MODE} and {@code AUTO_HOUSING_MODE}
     * defaulted to true (see {@link com.minecolonies.core.colony.buildings.modules.BuildingModules}),
     * so as soon as a worker hut exists an unemployed citizen will start working there.</p>
     */
    static final List<PlannedBuilding> EXPANSION_PLAN = List.of(
      // Extra housing first so the townhall's citizen cap actually fills up.
      new PlannedBuilding("fundamentals/residence1.blueprint", -25, 0),
      // Builder is needed for any future player-driven build orders.
      new PlannedBuilding("fundamentals/builder1.blueprint",   25, 0),
      // Cook feeds workers so they don't starve.
      new PlannedBuilding("fundamentals/cook1.blueprint",       0, -25),
      // Wood + stone production.
      new PlannedBuilding("fundamentals/lumberjack1.blueprint", 0, 25),
      new PlannedBuilding("fundamentals/miner1.blueprint",    -25, -25),
      // More residences for population growth.
      new PlannedBuilding("fundamentals/residence1.blueprint", 25, 25),
      new PlannedBuilding("fundamentals/residence1.blueprint",-25, 25),
      new PlannedBuilding("fundamentals/residence1.blueprint", 25, -25)
    );

    private NpcColonyGrowthTicker() {}

    /**
     * Static record describing one planned building in {@link #EXPANSION_PLAN}.
     *
     * @param blueprintPath path of the level-1 blueprint, e.g. {@code "fundamentals/builder1.blueprint"}.
     * @param dx            X-offset from the colony's town-hall position.
     * @param dz            Z-offset from the colony's town-hall position.
     */
    record PlannedBuilding(String blueprintPath, int dx, int dz) {}

    /**
     * Advances NPC growth for the given colony by exactly one server tick. Safe no-op when the colony
     * is not NPC-managed.
     *
     * @param colony the colony to advance.
     */
    public static void onColonyTick(@NotNull final Colony colony)
    {
        if (!colony.isNpcManaged() || colony.getWorld() == null)
        {
            return;
        }

        final int citizenCount = colony.getCitizenManager().getCitizens().size();
        final double bonus = Colony.NPC_GROWTH_PER_TICK * (1.0 + 0.1 * citizenCount);
        colony.addNpcGrowthPoints(bonus);

        if (colony.getNpcGrowthPoints() < Colony.NPC_UPGRADE_THRESHOLD)
        {
            return;
        }

        // Each growth cycle does up to two things: expand the colony footprint (place the next
        // planned worker hut) AND upgrade an existing building. Doing both keeps the colony
        // visibly progressing on every cycle and means citizens get jobs much sooner than if we
        // had to fully exhaust the expansion plan before any townhall upgrades happened.
        boolean didSomething = false;

        final PlannedBuilding next = pickNextPlannedBuilding(colony);
        if (next != null)
        {
            didSomething |= placePlannedBuilding(colony, next);
        }

        final IBuilding target = pickUpgradeTarget(colony);
        if (target != null)
        {
            didSomething |= forcePlaceNextLevel(colony, target);
        }

        if (didSomething)
        {
            colony.resetNpcGrowthPoints();
        }
        else
        {
            // Nothing to do (plan exhausted and every building maxed) or both attempts failed.
            // Halve the points so we don't immediately retry next tick but also don't drain to
            // zero in case the world state changes (chunks load in, etc.).
            colony.addNpcGrowthPoints(-Colony.NPC_UPGRADE_THRESHOLD / 2.0);
        }
    }

    /**
     * Returns the first {@link PlannedBuilding} in {@link #EXPANSION_PLAN} whose target world
     * position does not yet contain a registered colony building, or {@code null} when every
     * planned slot is already filled.
     */
    @Nullable
    private static PlannedBuilding pickNextPlannedBuilding(@NotNull final Colony colony)
    {
        for (final PlannedBuilding candidate : EXPANSION_PLAN)
        {
            final BlockPos target = colony.getCenter().offset(candidate.dx(), 0, candidate.dz());
            if (colony.getServerBuildingManager().getBuilding(target) != null)
            {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /**
     * Creative-place the given planned building's level-1 blueprint at its target offset. The
     * placement uses the same fancy-placement pipeline as a regular builder finishing a build,
     * so the inner hut block is automatically registered with the colony via
     * {@code AbstractColonyBlock.setPlacedBy → addNewBuilding}.
     */
    private static boolean placePlannedBuilding(@NotNull final Colony colony, @NotNull final PlannedBuilding planned)
    {
        final BlockPos target = colony.getCenter().offset(planned.dx(), 0, planned.dz());
        final String pack = pickStructurePack(colony);
        try
        {
            // loadAndPlaceStructureWithRotation internally swallows IllegalStateException and
            // returns null when the blueprint cannot be loaded — we MUST inspect the return
            // value or we will report success and reset growth points for a no-op placement,
            // permanently jamming the expansion plan on the failing entry.
            final Blueprint result = CreativeBuildingStructureHandler.loadAndPlaceStructureWithRotation(
              colony.getWorld(),
              StructurePacks.getBlueprintFuture(pack, planned.blueprintPath(), colony.getWorld().registryAccess()),
              target,
              RotationMirror.NONE,
              true,
              null);
            if (result == null)
            {
                Log.getLogger().warn("[NPC] blueprint load returned null for '{}' at {} (pack '{}')",
                  planned.blueprintPath(), target, pack);
                return false;
            }
            Log.getLogger().info("[NPC] queued expansion blueprint {} at {} for colony {}",
              planned.blueprintPath(), target, colony.getID());
            return true;
        }
        catch (final Exception e)
        {
            Log.getLogger().warn("[NPC] failed to place expansion blueprint '{}' at {}: {}",
              planned.blueprintPath(), target, e.getMessage());
            return false;
        }
    }

    /**
     * Pick a structure pack to use for expansion blueprints. Prefer the pack of an existing
     * building (so all huts in a colony stay visually consistent); fall back to {@code Default}
     * if no building has a pack set.
     */
    private static String pickStructurePack(@NotNull final Colony colony)
    {
        for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values())
        {
            final String p = b.getStructurePack();
            if (p != null && !p.isEmpty())
            {
                return p;
            }
        }
        return "Default";
    }

    private static IBuilding pickUpgradeTarget(@NotNull final Colony colony)
    {
        final List<IBuilding> candidates = new ArrayList<>();
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            if (building.getBuildingLevel() < building.getMaxBuildingLevel())
            {
                candidates.add(building);
            }
        }
        if (candidates.isEmpty())
        {
            return null;
        }
        candidates.sort(Comparator.<IBuilding>comparingInt(IBuilding::getBuildingLevel)
                          .thenComparingDouble(b -> b.getID().distSqr(colony.getCenter())));
        return candidates.get(0);
    }

    private static boolean forcePlaceNextLevel(@NotNull final Colony colony, @NotNull final IBuilding building)
    {
        final int newLevel = building.getBuildingLevel() + 1;
        final String currentPath = building.getBlueprintPath();
        final String newPath = bumpBlueprintLevel(currentPath, newLevel);
        if (newPath == null)
        {
            Log.getLogger().warn("[NPC] cannot derive next-level blueprint path from '{}'", currentPath);
            return false;
        }

        try
        {
            final Blueprint result = CreativeBuildingStructureHandler.loadAndPlaceStructureWithRotation(
              colony.getWorld(),
              StructurePacks.getBlueprintFuture(building.getStructurePack(), newPath, colony.getWorld().registryAccess()),
              building.getID(),
              building.getRotationMirror(),
              true,
              null);
            if (result == null)
            {
                // Same null-on-failure contract as in placePlannedBuilding — do not advance the
                // building's level/path if the blueprint never actually got queued for placement.
                Log.getLogger().warn("[NPC] blueprint load returned null for upgrade {} -> {}", currentPath, newPath);
                return false;
            }
            building.setBlueprintPath(newPath);
            building.setBuildingLevel(newLevel);
            building.markDirty();
            return true;
        }
        catch (final Exception e)
        {
            Log.getLogger().warn("[NPC] blueprint placement failed for {} -> {}", currentPath, newPath, e);
            return false;
        }
    }

    /**
     * Replace the trailing level digits of a MineColonies blueprint path with the new level. The
     * input may include a trailing {@code .blueprint} extension; if present, it is stripped before
     * digit detection and re-appended on the result so callers do not have to normalise.
     * Examples:
     *   "fisher/fisher1"            + 2 -> "fisher/fisher2"
     *   "town/townhall1"            + 3 -> "town/townhall3"
     *   "library4"                  + 5 -> "library5"
     *   "fundamentals/townhall1.blueprint" + 2 -> "fundamentals/townhall2.blueprint"
     *
     * @return the new path, or null when the (extension-stripped) input does not end with digits.
     */
    static String bumpBlueprintLevel(final String path, final int newLevel)
    {
        if (path == null || path.isEmpty())
        {
            return null;
        }
        final String suffix = ".blueprint";
        final boolean hasExt = path.endsWith(suffix);
        final String base = hasExt ? path.substring(0, path.length() - suffix.length()) : path;
        int end = base.length();
        int start = end;
        while (start > 0 && Character.isDigit(base.charAt(start - 1)))
        {
            start--;
        }
        if (start == end)
        {
            return null;
        }
        final String bumped = base.substring(0, start) + newLevel;
        return hasExt ? bumped + suffix : bumped;
    }
}
