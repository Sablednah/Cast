package com.sablednah.cast;

import com.mojang.logging.LogUtils;
import com.sablednah.cast.neoforge.CastCommands;
import com.sablednah.cast.neoforge.CastEvents;
import com.sablednah.cast.neoforge.CastPermissions;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/**
 * Cast — NPCs a vanilla client can see, for other mods to give roles to.
 *
 * <p>Two bodies. A <b>human</b> is a phantom: a player-model entity that
 * exists only as packets to the players who can see it, never added to a
 * level, so it is not a sleep-count member, a spawn anchor, a chunk loader or
 * a {@code /list} entry. A <b>mob</b> is a real vanilla creature Cast owns
 * from spawn, with its goals rebuilt and, for a brain-driven body such as a
 * villager, its behaviours removed every second.</p>
 *
 * <p>Depends on nothing. Chronicler and LegendQuest StoryTeller import
 * {@code com.sablednah.cast.api} from one guarded class each.</p>
 */
@Mod(CastMod.MODID)
public class CastMod {
    public static final String MODID = "cast";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CastMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Cast initialising");
        modContainer.registerConfig(ModConfig.Type.COMMON, CastConfig.SPEC);
        NeoForge.EVENT_BUS.register(CastEvents.class);
        NeoForge.EVENT_BUS.register(CastPermissions.class);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                CastCommands.register(event.getDispatcher()));
    }
}
