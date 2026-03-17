package com.e3dd.skywardstrike;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(SkywardStrikeMod.MODID)
public class SkywardStrikeMod {
    public static final String MODID = "skywardstrike";

    public SkywardStrikeMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModEnchantments.register(modEventBus);

        MinecraftForge.EVENT_BUS.register(this);
    }
}