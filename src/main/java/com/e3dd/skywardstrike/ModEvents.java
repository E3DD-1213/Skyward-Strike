package com.e3dd.skywardstrike;

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

    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();
    private static final Map<UUID, Boolean> WAS_JUMPING = new HashMap<>();
    private static final Map<UUID, Boolean> JUMP_STARTED_ON_GROUND = new HashMap<>();
    private static final Map<UUID, Long> LAST_GROUND_JUMP_TIME = new HashMap<>();
    
    private static final int COOLDOWN_TICKS = 100; // 5 seconds
    private static final int DOUBLE_JUMP_WINDOW_TICKS = 25; // ~1.25 seconds window

    private static Field jumpingField;

    static {
        try {
            // Access the "jumping" field via reflection to detect input state
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
        long currentTime = player.level().getGameTime();

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
        boolean wasJumping = WAS_JUMPING.getOrDefault(playerId, false);
        WAS_JUMPING.put(playerId, isJumping);

        // 2. Logic: Handle Jump Inputs

        // RISING EDGE: Player just pressed the jump key
        if (isJumping && !wasJumping) {
            boolean onGround = player.onGround();
            JUMP_STARTED_ON_GROUND.put(playerId, onGround);

            // If the player is in the air, check if this is a valid Second Jump
            if (!onGround) {
                 Long lastGroundJumpTime = LAST_GROUND_JUMP_TIME.get(playerId);
                 
                 // Check if we have a recent ground jump completion
                 if (lastGroundJumpTime != null && (currentTime - lastGroundJumpTime) <= DOUBLE_JUMP_WINDOW_TICKS) {
                     
                     if (player.isCrouching()) {
                        int enchantLevel = EnchantmentHelper.getEnchantmentLevel(ModEnchantments.SKYWARD_STRIKE.get(), player);
                        if (enchantLevel > 0) {
                            if (currentTime >= COOLDOWNS.getOrDefault(playerId, 0L)) {
                                // PERFORM LAUNCH
                                double verticalBoost = 1.0 + (0.5 * enchantLevel);
                                Vec3 currentMotion = player.getDeltaMovement();
                                player.setDeltaMovement(currentMotion.x, verticalBoost, currentMotion.z);
                                player.hurtMarked = true;

                                COOLDOWNS.put(playerId, currentTime + COOLDOWN_TICKS);
                                LAST_GROUND_JUMP_TIME.remove(playerId); // Consume the charge
                            }
                        }
                     }
                 }
            }
        }

        // FALLING EDGE: Player just released the jump key
        if (!isJumping && wasJumping) {
            // Check if the jump that just finished started on the ground
            boolean startedOnGround = JUMP_STARTED_ON_GROUND.getOrDefault(playerId, false);
            
            if (startedOnGround) {
                // Register the completion of a ground jump
                LAST_GROUND_JUMP_TIME.put(playerId, currentTime);
            }
        }
    }
}