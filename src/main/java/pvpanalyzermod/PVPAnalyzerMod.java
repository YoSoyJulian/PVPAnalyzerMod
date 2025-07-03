package pvpanalyzermod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.entity.player.PlayerEntity;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

import net.fabricmc.api.ClientModInitializer; // Usamos ClientModInitializer para un mod puramente cliente
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback; // Necesario para detectar clics fallidos
import net.minecraft.entity.LivingEntity; // Para daño y muerte
// Para daño recibido
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand; // Para CPS
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Map;
import java.time.Duration; // Para tiempo en combate

// ... imports idénticos (mantenlos todos como en tu versión original)

public class PVPAnalyzerMod implements ClientModInitializer {
	public static final String MOD_ID = "pvpanalyzermod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static boolean analysisActive = false;
	private static long startTime = 0;
	private static long lastCombatActivityTime = 0;

	private static int hitsGiven = 0;
	private static int hitsMissed = 0;
	private static float totalDamageDealt = 0.0f;
	private static float totalDamageReceived = 0.0f;
	private static int kills = 0;
	private static int deaths = 0;
	private static final Map<Long, Integer> cpsClicks = new HashMap<>();
	private static long lastLeftClickTime = 0;
	private static long lastRightClickTime = 0;

	@Override
	public void onInitializeClient() {
		LOGGER.info("PVP Analyzer Mod initialized for client-side!");

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				literal("pvpstats")
						.then(literal("toggle")
								.executes(context -> {
									PlayerEntity player = MinecraftClient.getInstance().player;
									if (analysisActive) {
										endPVPAnalysis(player, "Análisis finalizado manualmente por comando.");
									} else {
										startPVPAnalysis(player);
										assert player != null;
										player.sendMessage(Text.literal("§a[PVP Analyzer]§r Análisis iniciado. Usa /pvpstats stats para ver las estadísticas."), false);
									}
									return 1;
								})
						)
						.then(literal("stats")
								.executes(context -> {
									PlayerEntity player = MinecraftClient.getInstance().player;
									showPVPStats(player, "Estadísticas solicitadas manualmente.");
									return 1;
								})
						)
		));

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (analysisActive && world.isClient() && player.equals(MinecraftClient.getInstance().player)) {
				if (entity instanceof LivingEntity) {
					hitsGiven++;
					lastCombatActivityTime = world.getTime();
				}
			}
			return ActionResult.PASS;
		});

		boolean[] wasDeadLastTick = {false};

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!analysisActive || client.player == null || client.world == null) return;

			long currentTime = client.world.getTime();

			// Daño recibido (estimación)
			float missingHealth = client.player.getMaxHealth() - client.player.getHealth();
			if (missingHealth > totalDamageReceived) {
				totalDamageReceived = missingHealth;
			}

			if (client.player.isDead()) {
				if (!wasDeadLastTick[0]) {
					deaths++;
					endPVPAnalysis(client.player, "Has muerto.");
				}
				wasDeadLastTick[0] = true;
			} else {
				wasDeadLastTick[0] = false;
			}

			if (client.crosshairTarget != null && client.options.attackKey.isPressed()) {
				lastCombatActivityTime = currentTime;
			}

			// CLIC IZQUIERDO
			if (client.options.attackKey.isPressed()) {
				if (currentTime - lastLeftClickTime >= 1) {
					long second = currentTime / 20;
					cpsClicks.put(second, cpsClicks.getOrDefault(second, 0) + 1);

					HitResult target = client.crosshairTarget;
					if (!(target instanceof EntityHitResult entityHit) || !(entityHit.getEntity() instanceof LivingEntity)) {
						hitsMissed++;
					}
				}
				lastLeftClickTime = currentTime;
			}

			// CLIC DERECHO (opcional para CPS)
			if (client.options.useKey.isPressed()) {
				if (currentTime - lastRightClickTime >= 1) {
					long second = currentTime / 20;
					cpsClicks.put(second, cpsClicks.getOrDefault(second, 0) + 1);
				}
				lastRightClickTime = currentTime;
			}

			// Inactividad
			if (currentTime - lastCombatActivityTime > 20 * 30) {
				endPVPAnalysis(client.player, "Inactividad en el combate.");
			}
		});
	}

	private static void startPVPAnalysis(PlayerEntity player) {
		if (player == null) return;
		analysisActive = true;
		startTime = player.getWorld().getTime();
		lastCombatActivityTime = startTime;
		hitsGiven = 0;
		hitsMissed = 0;
		totalDamageDealt = 0.0f;
		totalDamageReceived = 0.0f;
		kills = 0;
		deaths = 0;
		cpsClicks.clear();
		lastLeftClickTime = 0;
		lastRightClickTime = 0;
		LOGGER.info("PVP analysis started for {}", player.getName().getString());
	}

	private static void endPVPAnalysis(PlayerEntity player, String reason) {
		if (player == null) {
			LOGGER.warn("Tried to end PVP analysis but player was null. Reason: {}", reason);
			resetPVPStats();
			return;
		}

		analysisActive = false;
		showPVPStats(player, reason);
		resetPVPStats();
		LOGGER.info("PVP analysis ended for {}. Reason: {}", player.getName().getString(), reason);
	}

	private static void showPVPStats(PlayerEntity player, String reason) {
		if (player == null) return;

		long totalTicks = player.getWorld().getTime() - startTime;
		Duration combatDuration = Duration.ofMillis(totalTicks * 50);

		// Estimar clicks totales
		double totalClicks = 0;
		for (int count : cpsClicks.values()) totalClicks += count;

		if (totalClicks > hitsGiven) {
			hitsMissed = (int) (totalClicks - hitsGiven);
			if (hitsMissed < 0) hitsMissed = 0;
		}

		double accuracy = (hitsGiven + hitsMissed > 0) ? ((double) hitsGiven / (hitsGiven + hitsMissed)) * 100.0 : 0.0;

		double totalCps = 0;
		int secondsCounted = 0;
		for (Map.Entry<Long, Integer> entry : cpsClicks.entrySet()) {
			totalCps += entry.getValue();
			secondsCounted++;
		}
		double averageCps = (secondsCounted > 0) ? totalCps / secondsCounted : 0.0;

		player.sendMessage(Text.literal(""), false);
		player.sendMessage(Text.literal("--- §b[Resumen de Análisis PVP]§r ---"), false);
		player.sendMessage(Text.literal("Estado: " + (analysisActive ? "§aACTIVO" : "§cINACTIVO")), false);
		player.sendMessage(Text.literal("Motivo: §7" + reason), false);
		player.sendMessage(Text.literal("Tiempo en combate: §e" + formatDuration(combatDuration)), false);
		player.sendMessage(Text.literal("---------------------------"), false);
		player.sendMessage(Text.literal("Golpes Acertados: §a" + hitsGiven), false);
		player.sendMessage(Text.literal("Golpes Fallados (estimado): §6" + hitsMissed), false);
		player.sendMessage(Text.literal(String.format("Precisión: §d%.2f%%", accuracy)), false);
		player.sendMessage(Text.literal(String.format("Daño Infligido: §c%.2f", totalDamageDealt) + "♥"), false);
		player.sendMessage(Text.literal(String.format("Daño Recibido (estimado): §c%.2f", totalDamageReceived) + "♥"), false);
		player.sendMessage(Text.literal("Asesinatos: §b" + kills), false);
		player.sendMessage(Text.literal("Muertes: §4" + deaths), false);
		player.sendMessage(Text.literal(String.format("CPS Promedio: §9%.2f", averageCps)), false);
		player.sendMessage(Text.literal("---------------------------"), false);

		String advice = getPVPAdvice(hitsGiven, (int) totalDamageReceived, hitsMissed, accuracy);
		player.sendMessage(Text.literal("Consejo: §f" + advice), false);
		player.sendMessage(Text.literal(""), false);
	}

	private static void resetPVPStats() {
		analysisActive = false;
		startTime = 0;
		lastCombatActivityTime = 0;
		hitsGiven = 0;
		hitsMissed = 0;
		totalDamageDealt = 0.0f;
		totalDamageReceived = 0.0f;
		kills = 0;
		deaths = 0;
		cpsClicks.clear();
		lastLeftClickTime = 0;
		lastRightClickTime = 0;
		LOGGER.info("PVP analysis state reset.");
	}

	private static String formatDuration(Duration duration) {
		long seconds = duration.getSeconds();
		long absSeconds = Math.abs(seconds);
		String positive = String.format("%d min %02d seg", absSeconds / 60, absSeconds % 60);
		return seconds < 0 ? "-" + positive : positive;
	}

	private static String getPVPAdvice(int hitsGiven, int intReceived, int hitsMissed, double hitRatio) {
		if (hitsMissed > hitsGiven * 0.4 && hitsGiven > 5) {
			return "Estás fallando muchos golpes. Concéntrate en tu puntería.";
		} else if (hitRatio < 50.0 && hitsGiven > 5) {
			return "Tu puntería necesita mejorar. Intenta concentrarte más en conectar cada golpe.";
		} else if (intReceived > hitsGiven * 1.5 && hitsGiven > 5) {
			return "Estás recibiendo muchos golpes. Trabaja en tu movimiento y defensa (strafe, bloqueo).";
		} else if (hitsGiven < 3 && intReceived < 3 && !analysisActive) {
			return "El combate fue muy corto o inactivo. Busca un enfoque más agresivo o defensivo.";
		} else if (hitsGiven > 10 && hitRatio > 75.0) {
			return "¡Excelente puntería! Sigue así. Considera cómo encadenar combos más largos.";
		} else if (hitsGiven > 5 && intReceived < hitsGiven && hitRatio > 60.0) {
			return "Buen equilibrio. Intenta maximizar tus combos y aprovechar cada apertura.";
		}
		return "Buen combate. Sigue practicando para perfeccionar tus habilidades.";
	}
}
