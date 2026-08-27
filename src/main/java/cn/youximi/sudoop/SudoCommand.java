package cn.youximi.sudoop;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class SudoCommand {
    private SudoCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, String commandName) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(commandName)
                .requires(source -> source.getEntity() instanceof net.minecraft.server.level.ServerPlayer)
                .executes(context -> SudoOp.manager().request(context.getSource().getPlayer(), null));
        root.then(Commands.argument("password", StringArgumentType.greedyString())
                .executes(context -> SudoOp.manager().request(
                        context.getSource().getPlayer(), StringArgumentType.getString(context, "password"))));
        dispatcher.register(root);
    }
}
