package com.minecolonies.core.colony.npc;

import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.api.RotationMirror;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.blocks.AbstractColonyBlock;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.permissions.IPermissions;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.api.util.CreativeBuildingStructureHandler;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Helper that creates an NPC-managed colony — i.e. a colony whose primary OWNER is a deterministic
 * {@link FakePlayer}. The colony auto-grows over time via {@link NpcColonyGrowthTicker}.
 *
 * <p>The {@code initiator} (the player who triggered creation) is added as an OFFICER so they can
 * see the colony in their HUD and visit it, but they are not the owner — the NPC mayor is.</p>
 *
 * <p>Creation steps (mirror the regular townhall placement pipeline):
 * <ol>
 *   <li>Place {@link ModBlocks#blockHutTownHall} at the requested position.</li>
 *   <li>Configure the resulting {@link TileEntityColonyBuilding} with the chosen structure pack and
 *       the level-1 townhall blueprint path.</li>
 *   <li>Register the colony via {@link IColonyManager#createColony} with the FakePlayer mayor.</li>
 *   <li>Register the building with the colony's building manager.</li>
 *   <li>Creative-place the level-1 townhall blueprint over the block so the colony has a visible,
 *       finished townhall building from the start.</li>
 * </ol>
 * </p>
 */
public final class NpcColonyCreator
{
    /** Stable UUID used as primary owner of every NPC-managed colony on the server. */
    public static final UUID NPC_MAYOR_UUID = UUID.fromString("00000000-0000-0000-0000-00000000B017");

    /** Display name of the NPC mayor (shown in the town hall GUI as colony "owner"). */
    public static final String NPC_MAYOR_NAME = "NpcMayor";

    /**
     * Path (relative to the structure pack root) of the level-1 townhall blueprint used to
     * creative-place the starting building. Matches the layout used by the bundled "Default" pack
     * and most community packs that follow the same convention.
     */
    private static final String DEFAULT_TOWNHALL_PATH = "fundamentals/townhall1.blueprint";

    private NpcColonyCreator() {}

    /**
     * Creates an NPC-managed colony at the given position.
     *
     * @param level     the server level to create the colony in.
     * @param pos       the town hall position.
     * @param colonyName name to assign to the colony (e.g. "NpcVillage").
     * @param pack      structure pack to use (e.g. "Default").
     * @param initiator the player who issued the command. Will be added as an OFFICER. May be null
     *                  for fully unattended creation.
     * @return the new colony, or null on failure (already a colony nearby, world cap missing, etc.).
     */
    @Nullable
    public static IColony create(@NotNull final ServerLevel level,
                                 @NotNull final BlockPos pos,
                                 @NotNull final String colonyName,
                                 @NotNull final String pack,
                                 @Nullable final ServerPlayer initiator)
    {
        if (!IColonyManager.getInstance().isFarEnoughFromColonies(level, pos))
        {
            Log.getLogger().info("[NPC] refused to create NPC colony at {} — too close to an existing colony", pos);
            return null;
        }

        // 1) Place the actual townhall hut block in the world. Use the AbstractBlockHut FACING
        // property so the BE renders correctly and the colony has a real physical anchor.
        final AbstractBlockHut<?> townHallBlock = ModBlocks.blockHutTownHall;
        final BlockState placedState = townHallBlock.defaultBlockState()
                                         .setValue(AbstractColonyBlock.FACING, Direction.NORTH);
        if (!level.setBlock(pos, placedState, Block.UPDATE_ALL))
        {
            Log.getLogger().warn("[NPC] failed to place townhall block at {}", pos);
            return null;
        }

        // 2) Configure the BE with structure pack and starting blueprint so subsequent calls
        // (createColony, addNewBuilding, blueprint placement) have valid metadata to work from.
        final BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof TileEntityColonyBuilding hut))
        {
            Log.getLogger().warn("[NPC] no TileEntityColonyBuilding present after placing townhall at {}", pos);
            level.removeBlock(pos, false);
            return null;
        }
        try
        {
            hut.setStructurePack(StructurePacks.getStructurePack(pack));
        }
        catch (final Exception e)
        {
            Log.getLogger().warn("[NPC] structure pack '{}' not loaded; continuing without explicit pack: {}", pack, e.getMessage());
        }
        hut.setBlueprintPath(DEFAULT_TOWNHALL_PATH);

        // 3) Register the colony with a deterministic FakePlayer mayor as primary OWNER.
        final FakePlayer mayor = FakePlayerFactory.get(level, new GameProfile(NPC_MAYOR_UUID, NPC_MAYOR_NAME));
        final IColony colony = IColonyManager.getInstance().createColony(level, pos, mayor, colonyName, pack);
        if (colony == null)
        {
            Log.getLogger().warn("[NPC] IColonyManager.createColony returned null for {}", pos);
            level.removeBlock(pos, false);
            return null;
        }

        // 4) Register the freshly placed townhall as the colony's first building.
        try
        {
            colony.getServerBuildingManager().addNewBuilding((AbstractTileEntityColonyBuilding) hut, level);
        }
        catch (final Exception e)
        {
            Log.getLogger().warn("[NPC] addNewBuilding failed for new NPC colony {}: {}", colony.getID(), e.getMessage());
        }

        if (colony instanceof Colony c)
        {
            c.setNpcManaged(true);
        }

        // 5) Creative-place the level-1 townhall blueprint over the anchor so the player sees a
        // finished townhall structure (not just an unfinished single block).
        try
        {
            CreativeBuildingStructureHandler.loadAndPlaceStructureWithRotation(
              level,
              StructurePacks.getBlueprintFuture(pack, DEFAULT_TOWNHALL_PATH, level.registryAccess()),
              pos,
              RotationMirror.NONE,
              true,
              null);
        }
        catch (final Exception e)
        {
            Log.getLogger().warn("[NPC] failed to creative-place initial townhall blueprint '{}' from pack '{}': {}",
              DEFAULT_TOWNHALL_PATH, pack, e.getMessage());
        }

        if (initiator != null)
        {
            colony.getPermissions().addPlayer(initiator.getGameProfile(),
              colony.getPermissions().getRanks().get(IPermissions.OFFICER_RANK_ID));
            colony.getPackageManager().addImportantColonyPlayer(initiator);
        }

        Log.getLogger().info("[NPC] created NPC-managed colony id={} at {} initiator={}",
          colony.getID(), pos, initiator == null ? "none" : initiator.getName().getString());
        return colony;
    }
}
