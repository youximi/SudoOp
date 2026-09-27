package cn.youximi.sudoop.mixin;

import java.util.Collection;

import com.mojang.authlib.GameProfile;

import cn.youximi.sudoop.SudoOp;
import cn.youximi.sudoop.SudoOpMessages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.OpCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OpCommand.class)
@SuppressWarnings("unused")
public abstract class OpCommandMixin {
    @Inject(method = "opPlayers", at = @At("HEAD"), cancellable = true)
    private static void sudoop$protectBackendOps(
            CommandSourceStack source,
            Collection<GameProfile> targets,
            CallbackInfoReturnable<Integer> callback
    ) {
        if (SudoOp.manager() != null && SudoOp.manager().shouldBlockOpManagement(source, targets)) {
            source.sendFailure(SudoOpMessages.forPlayer(source.getPlayer(), "message.sudoop.protected_operation"));
            callback.setReturnValue(0);
        }
    }
}
