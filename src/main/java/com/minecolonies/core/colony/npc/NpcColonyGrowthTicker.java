package com.minecolonies.core.colony.npc;

import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.util.CreativeBuildingStructureHandler;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.Colony;
import org.jetbrains.annotations.NotNull;

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
    private NpcColonyGrowthTicker() {}

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

        // Upgrade one building (lowest level first, ties broken by closest to town hall).
        final IBuilding target = pickUpgradeTarget(colony);
        if (target == null)
        {
            // nothing to upgrade right now (all buildings maxed). Drain points so we don't spam.
            colony.resetNpcGrowthPoints();
            return;
        }

        if (forcePlaceNextLevel(colony, target))
        {
            colony.resetNpcGrowthPoints();
        }
        else
        {
            // Placement failed (e.g. blueprint missing). Halve points to avoid retry storm.
            colony.addNpcGrowthPoints(-Colony.NPC_UPGRADE_THRESHOLD / 2.0);
        }
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
            CreativeBuildingStructureHandler.loadAndPlaceStructureWithRotation(
              colony.getWorld(),
              StructurePacks.getBlueprintFuture(building.getStructurePack(), newPath, colony.getWorld().registryAccess()),
              building.getID(),
              building.getRotationMirror(),
              true,
              null);
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
