package com.minecolonies.core.colony.npc;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.permissions.IPermissions;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.Colony;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 */
public final class NpcColonyCreator
{
    /** Stable UUID used as primary owner of every NPC-managed colony on the server. */
    public static final UUID NPC_MAYOR_UUID = UUID.fromString("00000000-0000-0000-0000-00000000B017");

    /** Display name of the NPC mayor (shown in the town hall GUI as colony "owner"). */
    public static final String NPC_MAYOR_NAME = "NpcMayor";

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

        final FakePlayer mayor = FakePlayerFactory.get(level, new GameProfile(NPC_MAYOR_UUID, NPC_MAYOR_NAME));
        final IColony colony = IColonyManager.getInstance().createColony(level, pos, mayor, colonyName, pack);
        if (colony == null)
        {
            return null;
        }

        if (colony instanceof Colony c)
        {
            c.setNpcManaged(true);
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
