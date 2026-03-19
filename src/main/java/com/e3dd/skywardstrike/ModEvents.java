package com.e3dd.skywardstrike;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
    private static final Map<UUID, Integer> SERVER_LAUNCH_TICKS = new HashMap<>();

    private static final Map<UUID, Long> CLIENT_COOLDOWNS = new HashMap<>();
    private static final Map<UUID, Boolean> CLIENT_WAS_JUMPING = new HashMap<>();
    private static final Map<UUID, Long> CLIENT_LAST_JUMP_RELEASE_TIME = new HashMap<>();
    private static final Map<UUID, Integer> CLIENT_LAUNCH_TICKS = new HashMap<>();
    
    private static final int COOLDOWN_TICKS = 100; // 5 seconds
    private static final int DOUBLE_JUMP_WINDOW_TICKS = 10; // 0.5 seconds window
    private static final int LAUNCH_ANIMATION_DURATION = 30; // 1.5 seconds of particles

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
        Map<UUID, Integer> launchTicksMap = isClient ? CLIENT_LAUNCH_TICKS : SERVER_LAUNCH_TICKS;

        // --- CONTINUOUS PARTICLE ANIMATION ---
        if (launchTicksMap.containsKey(playerId)) {
            int ticksLeft = launchTicksMap.get(playerId);
            if (ticksLeft > 0) {
                // Determine animation progress
                int ticksPassed = LAUNCH_ANIMATION_DURATION - ticksLeft;
                
                // Radius: Moved out slightly from 0.5 -> 0.75
                double baseRadius = 0.75; 

                if (isClient) {
                    // Client Side Particles
                    int particlesPerTick = 4;
                    for (int i = 0; i < particlesPerTick; i++) {
                        // Spiral Angle increases with time
                        double angle = (ticksPassed * 0.5) + (i * Math.PI * 2.0 / particlesPerTick);
                        
                        double radiusRandomness = (Math.random() - 0.5) * 0.2;
                        double radius = baseRadius + radiusRandomness;

                        double offsetX = Math.cos(angle) * radius;
                        double offsetZ = Math.sin(angle) * radius;
                        
                        // Spawn at player's current height, slightly distributed
                        double heightRandomness = (Math.random() - 0.5) * 0.5;
                        
                        player.level().addParticle(ParticleTypes.HAPPY_VILLAGER, 
                                player.getX() + offsetX, player.getY() + 0.5 + heightRandomness, player.getZ() + offsetZ, 
                                0, 0.05, 0); // Mild upward drift
                    }
                } else {
                    // Server Side Particles (for others to see)
                    ServerLevel serverLevel = (ServerLevel) player.level();
                    // Send fewer particles to save bandwidth, relying on client interpolation mostly
                    // But we want them to see the spiral too
                    double angle = ticksPassed * 0.5;
                    double radius = baseRadius;
                    double offsetX = Math.cos(angle) * radius;
                    double offsetZ = Math.sin(angle) * radius;
                    
                    serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, 
                            player.getX() + offsetX, player.getY() + 0.5, player.getZ() + offsetZ, 
                            2, 0.1, 0.2, 0.1, 0.05);
                }

                launchTicksMap.put(playerId, ticksLeft - 1);
            } else {
                launchTicksMap.remove(playerId);
            }
        }
        // -------------------------------------


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

                                // --- INITIAL BURST VISUALS & SOUND ---
                                if (isClient) {
                                    player.level().playSound(player, player.getX(), player.getY(), player.getZ(), 
                                            SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 2.0f, 1.0f);

                                    // Ground Burst (CLOUD)
                                    for (int i = 0; i < 20; i++) {
                                        double angle = i * Math.PI * 2.0 / 20.0;
                                        double radius = 0.6 + (Math.random() * 0.3); // Radius ~0.75 +/- variance
                                        double offsetX = Math.cos(angle) * radius;
                                        double offsetZ = Math.sin(angle) * radius;
                                        
                                        player.level().addParticle(ParticleTypes.CLOUD, 
                                                player.getX() + offsetX, player.getY(), player.getZ() + offsetZ, 
                                                0, 0.05, 0);
                                        
                                        if (i % 2 == 0) {
                                            player.level().addParticle(ParticleTypes.EXPLOSION, 
                                                player.getX() + (Math.random() * 0.2 - 0.1), player.getY(), player.getZ() + (Math.random() * 0.2 - 0.1), 
                                                0, 0, 0);
                                        }
                                    }
                                } else {
                                    ServerLevel serverLevel = (ServerLevel) player.level();
                                    serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(), 
                                            SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 2.0f, 1.0f);
                                    
                                    serverLevel.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY(), player.getZ(), 20, 0.7, 0.1, 0.7, 0.1);
                                    serverLevel.sendParticles(ParticleTypes.EXPLOSION, player.getX(), player.getY(), player.getZ(), 5, 0.2, 0.1, 0.2, 0.0);
                                }
                                // -----------------------

                                // Start Continuous Animation
                                launchTicksMap.put(playerId, LAUNCH_ANIMATION_DURATION);

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