package cn.youximi.sudoop;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PermissionsChangedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

@Mod(SudoOp.MOD_ID)
public final class SudoOp {
    public static final String MOD_ID = "sudoop";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static TemporaryOpManager manager;

    public SudoOp(IEventBus modEventBus, ModContainer modContainer) {
        manager = new TemporaryOpManager();
        modContainer.registerConfig(ModConfig.Type.SERVER, SudoOpConfig.SPEC);
        NeoForge.EVENT_BUS.register(this);
    }

    public static TemporaryOpManager manager() {
        return manager;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        manager.registerCommand(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        manager.onServerAboutToStart(event.getServer());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        manager.onServerTick(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        manager.onServerStopping(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        manager.onServerStopped(event.getServer());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            manager.onPlayerLoggedIn(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            manager.onPlayerLoggedOut(player);
        }
    }

    @SubscribeEvent
    public void onPermissionsChanged(PermissionsChangedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            manager.onPermissionsChanged(player, event.getOldLevel(), event.getNewLevel());
        }
    }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        manager.onCommand(event);
    }
}
