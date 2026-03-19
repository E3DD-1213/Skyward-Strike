package com.e3dd.skywardstrike;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = SkywardStrikeMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ModEvents {

    // Separate maps for Client and Server to avoid race conditions in Single Player
    private static final Map<UUID, Long> SERVER_COOLDOWNS = new HashMap<>();
    private static final Map<UUID, Boolean> SERVER_WAS_JUMPING = new HashMap<>();
    private static final Map<UUID, Long> SERVER_LAST_JUMP_RELEASE_TIME = new HashMap<>();

    private static final Map<UUID, Long> CLIENT_COOLDOWNS = new HashMap<>();
    private static final Map<UUID, Boolean> CLIENT_WAS_JUMPING = new HashMap<>();
    private static final Map<UUID, Long> CLIENT_LAST_JUMP_RELEASE_TIME = new HashMap<>();
    
    private static final int COOLDOWN_TICKS = 100; // 5 seconds
    private static final int DOUBLE_JUMP_WINDOW_TICKS = 10; // 0.5 seconds window

    private static Field jumpingField;

    static {
        try {
            jumpingField = LivingEntity.class.getDeclaredField("jumping");
            jumpingField.setAccessible(true);
        } catch (Exception e) {
            System.err.println("SkywardStrike: Could not find 'jumping' field via reflection.");
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Player player = event.player;
        UUID playerId = player.getUUID();
        boolean isClient = player.level().isClientSide();
        long currentTime = player.level().getGameTime();

        // Select the correct maps for the current thread/side
        Map<UUID, Long> cooldowns = isClient ? CLIENT_COOLDOWNS : SERVER_COOLDOWNS;
        Map<UUID, Boolean> wasJumpingMap = isClient ? CLIENT_WAS_JUMPING : SERVER_WAS_JUMPING;
        Map<UUID, Long> lastReleaseMap = isClient ? CLIENT_LAST_JUMP_RELEASE_TIME : SERVER_LAST_JUMP_RELEASE_TIME;

        // Check for Cooldown Expiry Notification (Client Only for Chat)
        if (isClient && cooldowns.containsKey(playerId)) {
            long cooldownEnd = cooldowns.get(playerId);
            if (currentTime >= cooldownEnd) {
                player.sendSystemMessage(Component.literal("§aSkyward Strike Ready!"));
                cooldowns.remove(playerId);
            }
        }
        // Cleanup Server Cooldowns silently
        if (!isClient && cooldowns.containsKey(playerId)) {
            if (currentTime >= cooldowns.get(playerId)) {
                cooldowns.remove(playerId);
            }
        }

        // 1. Detect Input (Spacebar/Jump)
        boolean isJumping = false;
        try {
            if (jumpingField != null) {
                isJumping = jumpingField.getBoolean(player);
            }
        } catch (IllegalAccessException e) {
            // Ignore
        }

        // Get previous input state
        boolean wasJumping = wasJumpingMap.getOrDefault(playerId, false);
        wasJumpingMap.put(playerId, isJumping);

        // 2. Logic: Handle Jump Inputs

        // RISING EDGE: Player just pressed the jump key
        if (isJumping && !wasJumping) {
            
            // Check if we have a recent jump release
            Long lastJumpReleaseTime = lastReleaseMap.get(playerId);
            
            if (lastJumpReleaseTime != null) {
                long gap = currentTime - lastJumpReleaseTime;
                
                // Must be within the window (0.5s)
                if (gap <= DOUBLE_JUMP_WINDOW_TICKS) {
                    
                    if (player.isCrouching()) {
                        int enchantLevel = EnchantmentHelper.getEnchantmentLevel(ModEnchantments.SKYWARD_STRIKE.get(), player);
                        if (enchantLevel > 0) {
                            if (!cooldowns.containsKey(playerId)) {
                                // PERFORM LAUNCH
                                double verticalBoost = 1.0 + (0.5 * enchantLevel);
                                Vec3 currentMotion = player.getDeltaMovement();
                                player.setDeltaMovement(currentMotion.x, verticalBoost, currentMotion.z);
                                player.hurtMarked = true;

                                cooldowns.put(playerId, currentTime + COOLDOWN_TICKS);
                                lastReleaseMap.remove(playerId); // Consume the charge
                            }
                        }
                    }
                }
            }
        }

        // FALLING EDGE: Player just released the jump key
        if (!isJumping && wasJumping) {
            // Register the completion of a jump press
            lastReleaseMap.put(playerId, currentTime);
        }
    }
}