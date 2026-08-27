package cn.youximi.sudoop.mixin;

import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestions;

import cn.youximi.sudoop.SudoOp;
import cn.youximi.sudoop.TemporaryOpManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Redirect(
            method = "handleCustomCommandSuggestions",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/brigadier/CommandDispatcher;getCompletionSuggestions(Lcom/mojang/brigadier/ParseResults;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private CompletableFuture<Suggestions> sudoop$filterSuggestions(
            CommandDispatcher<CommandSourceStack> dispatcher,
            ParseResults<CommandSourceStack> parseResults
    ) {
        CompletableFuture<Suggestions> original = dispatcher.getCompletionSuggestions(parseResults);
        TemporaryOpManager manager = SudoOp.manager();
        if (manager == null || !manager.isTemporaryOp(player)) {
            return original;
        }
        return original.thenApply(suggestions -> manager.filterCommandSuggestions(
                player, parseResults.getReader().getString(), suggestions));
    }
}
