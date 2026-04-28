package com.minecolonies.core.commands.colonycommands;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.core.colony.npc.NpcColonyCreator;
import com.minecolonies.core.commands.commandTypes.IMCCommand;
import com.minecolonies.core.commands.commandTypes.IMCOPCommand;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Operator-only command that creates an NPC-managed colony at the executing player's position.
 *
 * <p>Usage: <code>/mc colony npccreate [name] [pack]</code></p>
 *
 * <p>NPC-managed colonies have a {@link NpcColonyCreator#NPC_MAYOR_NAME fake-player owner}, grow
 * automatically over time, and add the executing player as an OFFICER (they can visit and see the
 * town hall GUI but cannot delete or transfer ownership).</p>
 */
public class CommandNpcCreateColony implements IMCOPCommand
{
    private static final String NAME_ARG = "name";
    private static final String PACK_ARG = "pack";

    @Override
    public int onExecute(final CommandContext<CommandSourceStack> context)
    {
        final Entity sender = context.getSource().getEntity();
        if (!(sender instanceof ServerPlayer player))
        {
            context.getSource().sendFailure(Component.literal("Only players can run /npccreate."));
            return 0;
        }

        String name;
        try { name = StringArgumentType.getString(context, NAME_ARG); }
        catch (final IllegalArgumentException e) { name = "NpcVillage"; }

        String pack;
        try { pack = StringArgumentType.getString(context, PACK_ARG); }
        catch (final IllegalArgumentException e) { pack = "Default"; }

        final IColony colony = NpcColonyCreator.create((ServerLevel) player.level(), player.blockPosition(), name, pack, player);
        if (colony == null)
        {
            context.getSource().sendFailure(Component.literal(
              "Could not create NPC colony — check distance to existing colonies."));
            return 0;
        }

        final int colonyId = colony.getID();
        context.getSource().sendSuccess(() -> Component.literal(
          "Created NPC-managed colony #" + colonyId + " (" + colony.getName() + "). It will grow on its own over time."), true);
        return 1;
    }

    @Override
    public String getName()
    {
        return "npccreate";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build()
    {
        return IMCCommand.newLiteral(getName())
                 .executes(this::checkPreConditionAndExecute)
                 .then(IMCCommand.newArgument(NAME_ARG, StringArgumentType.string())
                         .executes(this::checkPreConditionAndExecute)
                         .then(IMCCommand.newArgument(PACK_ARG, StringArgumentType.string())
                                 .executes(this::checkPreConditionAndExecute)));
    }
}
