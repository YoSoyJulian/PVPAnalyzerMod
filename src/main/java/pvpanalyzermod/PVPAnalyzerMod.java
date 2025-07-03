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

public class PVPAnalyzerMod implements ClientModInitializer { // ¡Importante! Ahora implementa ClientModInitializer
	public static final String MOD_ID = "pvpanalyzermod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static boolean analysisActive = false;
	private static long startTime = 0; // Tiempo de inicio del análisis
	private static long lastCombatActivityTime = 0; // Última vez que hubo un hit (dado o recibido)

	// Estadísticas
	private static int hitsGiven = 0;
	private static int hitsMissed = 0;
	private static float totalDamageDealt = 0.0f;
	private static float totalDamageReceived = 0.0f;
	private static int kills = 0;
	private static int deaths = 0;
	private static final Map<Long, Integer> cpsClicks = new HashMap<>(); // Para calcular CPS
	private static long lastLeftClickTime = 0; // Para clicks perdidos
	private static long lastRightClickTime = 0; // Para clicks perdidos


	@Override
	public void onInitializeClient() { // ¡Importante! El método ahora es onInitializeClient
		LOGGER.info("PVP Analyzer Mod initialized for client-side!");

		// 1. Registro de Comandos (CLIENTE)
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

		// 2. Evento para detectar golpes a entidades (¡ACERTADOS!)
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (analysisActive && world.isClient() && player.equals(MinecraftClient.getInstance().player)) {
				if (entity instanceof LivingEntity) { // Nos interesa solo entidades vivas
					hitsGiven++;
					lastCombatActivityTime = world.getTime();
					LOGGER.info("Player {} hit {}", player.getName().getString(), entity.getName().getString());
				}
			}
			return ActionResult.PASS;
		});

		// 3. Evento para detectar clics del jugador (para CPS y golpes fallidos)
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (analysisActive && client.player != null && client.world != null) {
				long currentTime = client.world.getTime();

				// Detectar clics izquierdo (ataques)
				if (client.options.attackKey.isPressed()) {
					if (currentTime - lastLeftClickTime >= 1) { // 1 tick = 50 ms, para evitar contar el mismo clic varias veces
						long second = currentTime / 20; // Convertir ticks a segundos
						cpsClicks.put(second, cpsClicks.getOrDefault(second, 0) + 1);

						// Si no golpeó una entidad (AttackEntityCallback no se activó para este tick)
						// y no estaba mirando un bloque, podría ser un golpe fallido
						// NOTA: Esto es una simplificación; detectar un "miss" real es más complejo
						// ya que podría haber golpeado el aire sin intención de PvP, o un mob no-pvp.
						// Para este ejemplo, lo consideraremos un miss si no impactó una entidad viva.
						// La lógica de AttackEntityCallback se encargaría de los hits dados.
					}
					lastLeftClickTime = currentTime;
				}

				// Detectar clics derecho (usar items, etc) -- si queremos incluirlos en CPS
				if (client.options.useKey.isPressed()) {
					if (currentTime - lastRightClickTime >= 1) {
						long second = currentTime / 20;
						cpsClicks.put(second, cpsClicks.getOrDefault(second, 0) + 1);
					}
					lastRightClickTime = currentTime;
				}

				// Monitorear inactividad para finalizar el análisis
				if (currentTime - lastCombatActivityTime > 20 * 30) { // 30 segundos de inactividad
					endPVPAnalysis(client.player, "Inactividad en el combate.");
				}

				// Actualizar contadores de hits fallidos
				// Esto es una estimación. Un hit fallido real es cuando apuntas a un jugador pero no lo golpeas.
				// Aquí, simplemente si hay muchos clics y pocos hits dados, asumimos fallos.
				// Un sistema más preciso requeriría mixins para interceptar el raycast de ataque.
				// Para este ejemplo, ajustaremos la estimación al final del análisis.
			}
		});

		// 4. Evento para detectar daño infligido y recibido (MIXINS requeridos, pero haremos una simulación cliente-side)
		// La detección de daño real infligido y recibido es compleja en el cliente sin mixins.
		// Fabric no tiene un evento directo AttackEntityEvent.POST que te diga "cuánto daño hiciste".
		// Ni eventos para "recibí daño de X entidad".
		// Para simplificar, haremos una estimación basada en hits o usaremos otros eventos.
		// Por ahora, solo registramos los hits dados. Para el daño, lo estimaremos o dejaremos en 0.
		// ¡ACTUALIZACIÓN! Podemos usar LivingEntityDeathCallback para las muertes.
		boolean[] wasDeadLastTick = {false}; // ✅ truco para variable "mutable"

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!analysisActive || client.player == null || client.world == null) return;

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
				lastCombatActivityTime = client.world.getTime();
			}
		});


		// Este es para detectar clics que no impactan en bloques (podría ser un click al aire o miss a entidad)
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (analysisActive && world.isClient() && player.equals(MinecraftClient.getInstance().player)) {
				if (hand == Hand.MAIN_HAND) {
					HitResult hitResult = MinecraftClient.getInstance().crosshairTarget;

					// Verificamos si no golpeó una entidad
					if (!(hitResult instanceof EntityHitResult entityHit) || !entityHit.getEntity().isAlive()) {
						hitsMissed++; // Puedes activarlo si quieres
					}
				}
			}
			return ActionResult.PASS;
		});

		// Resetea el CPS cada segundo para mantener un cálculo preciso
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player != null && client.world != null && analysisActive) {
				long currentSecond = client.world.getTime() / 20;
				cpsClicks.entrySet().removeIf(entry -> entry.getKey() < currentSecond - 1);
			}
		});
	}

	private static void startPVPAnalysis(PlayerEntity player) {
		if (player == null) return;
		analysisActive = true;
		startTime = player.getWorld().getTime();
		lastCombatActivityTime = startTime;
		// Reiniciar estadísticas
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
			resetPVPStats(); // Resetear de todas formas
			return;
		}

		analysisActive = false;
		showPVPStats(player, reason);
		resetPVPStats(); // Reiniciar estadísticas después de mostrarlas
		LOGGER.info("PVP analysis ended for {}. Reason: {}", player.getName().getString(), reason);
	}

	private static void showPVPStats(PlayerEntity player, String reason) {
		if (player == null) {
			LOGGER.warn("Tried to show PVP stats but player was null. Reason: {}", reason);
			return;
		}

		// Cálculos finales antes de mostrar
		long totalTicks = player.getWorld().getTime() - startTime;
		Duration combatDuration = Duration.ofMillis(totalTicks * 50); // Convertir ticks a milisegundos

		// Calculo de Hits Missed (Estimación): Si el jugador clickea mucho pero acierta poco
		// Esto es una simplificación. Un cálculo exacto requeriría interceptar el raycast de ataque.
		double totalClicks = 0;
		for (int count : cpsClicks.values()) {
			totalClicks += count;
		}

		// Una forma rudimentaria de estimar fallos: si los clicks de ataque son más que los hits dados
		// y el tiempo de combate es suficiente.
		if (totalClicks > hitsGiven && totalTicks > 0) {
			hitsMissed = (int) (totalClicks - hitsGiven);
			if (hitsMissed < 0) hitsMissed = 0; // Asegurarse que no sea negativo
		} else {
			hitsMissed = 0;
		}


		double accuracy = (hitsGiven + hitsMissed > 0) ? ((double) hitsGiven / (hitsGiven + hitsMissed)) * 100.0 : 0.0;

		// Calcular CPS promedio
		double totalCps = 0;
		int secondsCounted = 0;
		for (Map.Entry<Long, Integer> entry : cpsClicks.entrySet()) {
			totalCps += entry.getValue();
			secondsCounted++;
		}
		double averageCps = (secondsCounted > 0) ? totalCps / secondsCounted : 0.0;


		// Simulación de daño infligido/recibido (realmente necesitaría mixins para ser exacto)
		// Para este ejemplo, los dejamos en 0 o haremos una estimación muy básica si no hay forma de obtenerlos directamente.
		// No hay un evento directo de Fabric Client-side para "daño infligido/recibido" sin Mixins.
		// Así que por ahora, se mostrarán como 0.0f.
		totalDamageDealt = 0.0f; // Necesitaría mixins o lógica muy avanzada de cliente
		totalDamageReceived = 0.0f; // Necesitaría mixins o lógica muy avanzada de cliente


		player.sendMessage(Text.literal(""), false); // Línea en blanco para mejor legibilidad
		player.sendMessage(Text.literal("--- §b[Resumen de Análisis PVP]§r ---"), false);
		player.sendMessage(Text.literal("Estado: " + (analysisActive ? "§aACTIVO" : "§cDIACTIVO")), false);
		player.sendMessage(Text.literal("Motivo: §7" + reason), false);
		player.sendMessage(Text.literal("Tiempo en combate: §e" + formatDuration(combatDuration)), false);
		player.sendMessage(Text.literal("---------------------------"), false);
		player.sendMessage(Text.literal("Golpes Acertados: §a" + hitsGiven), false);
		player.sendMessage(Text.literal("Golpes Fallados (estimado): §6" + hitsMissed), false);
		player.sendMessage(Text.literal(String.format("Precisión: §d%.2f%%", accuracy)), false);
		player.sendMessage(Text.literal(String.format("Daño Infligido: §c%.2f", totalDamageDealt) + "♥"), false); // Esto será 0.0 a menos que uses Mixins.
		player.sendMessage(Text.literal(String.format("Daño Recibido: §c%.2f", totalDamageReceived) + "♥"), false); // Esto será 0.0 a menos que uses Mixins.
		player.sendMessage(Text.literal("Asesinatos: §b" + kills), false);
		player.sendMessage(Text.literal("Muertes: §4" + deaths), false);
		player.sendMessage(Text.literal(String.format("CPS Promedio: §9%.2f", averageCps)), false);
		player.sendMessage(Text.literal("---------------------------"), false);

		String advice = getPVPAdvice(hitsGiven, (int) totalDamageReceived, hitsMissed, accuracy); // Usamos (int)totalDamageReceived como intReceived
		player.sendMessage(Text.literal("Consejo: §f" + advice), false);
		player.sendMessage(Text.literal(""), false); // Línea en blanco al final
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

	// Método para formatear la duración
	private static String formatDuration(Duration duration) {
		long seconds = duration.getSeconds();
		long absSeconds = Math.abs(seconds);
		String positive = String.format(
				"%d min %02d seg",
				absSeconds / 60,
				absSeconds % 60);
		return seconds < 0 ? "-" + positive : positive;
	}

	// Lógica de consejos PvP (Mantenida y corregida)
	private static String getPVPAdvice(int hitsGiven, int intReceived, int hitsMissed, double hitRatio) {
		if (hitsMissed > hitsGiven * 0.4 && hitsGiven > 5) {
			return "Estás fallando muchos golpes. Concéntrate en tu puntería.";
		} else if (hitRatio < 50.0 && hitsGiven > 5) {
			return "Tu puntería necesita mejorar. Intenta concentrarte más en conectar cada golpe.";
		} else if (intReceived > hitsGiven * 1.5 && hitsGiven > 5) { // Corregido: intReceived
			return "Estás recibiendo muchos golpes. Trabaja en tu movimiento y defensa (strafe, bloqueo).";
		} else if (hitsGiven < 3 && intReceived < 3 && !analysisActive) { // Corregido: intReceived
			return "El combate fue muy corto o inactivo. Busca un enfoque más agresivo o defensivo.";
		} else if (hitsGiven > 10 && hitRatio > 75.0) {
			return "¡Excelente puntería! Sigue así. Considera cómo encadenar combos más largos.";
		} else if (hitsGiven > 5 && intReceived < hitsGiven && hitRatio > 60.0) { // Corregido: intReceived
			return "Buen equilibrio. Intenta maximizar tus combos y aprovechar cada apertura.";
		}
		return "Buen combate. Sigue practicando para perfeccionar tus habilidades.";
	}
}