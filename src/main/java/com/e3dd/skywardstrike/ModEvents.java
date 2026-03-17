package com.e3dd.skywardstrike;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SkywardStrikeMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ModEvents {

    @SubscribeEvent
    public static void onPlayerJump(LivingEvent.LivingJumpEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (player.isCrouching()) {
                int enchantLevel = EnchantmentHelper.getEnchantmentLevel(ModEnchantments.SKYWARD_STRIKE.get(), player);

                if (enchantLevel > 0) {
                    Vec3 currentMovement = player.getDeltaMovement();
                    double verticalBoost = 0.6 + (0.3 * enchantLevel);
                    player.setDeltaMovement(currentMovement.x, verticalBoost, currentMovement.z);
                    player.hurtMarked = true;
                }
            }
        }
    }
}