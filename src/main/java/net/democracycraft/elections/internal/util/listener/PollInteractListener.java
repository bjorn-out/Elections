package net.democracycraft.elections.internal.util.listener;

import net.democracycraft.elections.Elections;
import net.democracycraft.elections.api.model.Election;
import net.democracycraft.elections.api.model.Poll;
import net.democracycraft.elections.api.model.Voter;
import net.democracycraft.elections.api.service.ElectionsService;
import net.democracycraft.elections.internal.data.ElectionStatus;
import net.democracycraft.elections.internal.data.RequirementsDto;
import net.democracycraft.elections.internal.ui.vote.BallotIntroMenu;
import net.democracycraft.elections.internal.util.time.PlayerPlaytimeUtil;
import net.democracycraft.elections.internal.vote.VoteSessionManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bukkit listener that opens the ballot UI when a player right-clicks a block
 * registered as a polling booth (poll) for an open election.
 */
public record PollInteractListener(ElectionsService electionsService, Elections plugin) implements Listener {

    private static final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 500;

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(@NonNull PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Player player = event.getPlayer();

        if (System.currentTimeMillis() - cooldowns.getOrDefault(player.getUniqueId(), 0L) < COOLDOWN_MS) return;
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());

        Optional<Election> match = findElectionByBlock(block);
        if (match.isEmpty()) return;

        Election election = match.get();
        if (election.getStatus() != ElectionStatus.OPEN) {
            player.sendMessage("This election is not open.");
            return;
        }

        // Minimal eligibility: require elections.user and any configured permission nodes
        if (!player.hasPermission("elections.user")) {
            player.sendMessage("You don't have permission to vote.");
            return;
        }
        RequirementsDto req = election.getRequirements();
        if (req != null && req.permissions() != null) {
            for (String node : req.permissions()) {
                if (node == null || node.isBlank()) continue;
                if (!player.hasPermission(node)) {
                    player.sendMessage("You are not eligible to vote in this election.");
                    return;
                }
            }
        }
        // Active playtime validation if applicable
        if (req != null) {
            long minMinutes = req.minActivePlaytimeMinutes();
            if (minMinutes > 0) {
                long playedMinutes = PlayerPlaytimeUtil.getPlaytimeMinutes(player);
                if (playedMinutes < minMinutes) {
                    String requiredTime = formatPlaytime(minMinutes);
                    String currentTime = formatPlaytime(playedMinutes);
                    player.sendMessage("You are not eligible to vote: requires at least " + requiredTime + " of active playtime. You have " + currentTime + ".");
                    return;
                }
            }
        }

        // Register voter off the main thread to avoid DB blocking.
        new BukkitRunnable() {
            @Override
            public void run() {
                Voter voter = electionsService.registerVoterAsync(election.getId(), player.getName()).join();
                // Back on main thread: fetch a fresh election snapshot and guard against duplicate submissions.
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        Election latest = electionsService.getElectionSnapshot(election.getId()).orElse(election);
                        boolean alreadySubmitted = latest.getBallots().stream().anyMatch(b -> b.getVoterId() == voter.getId() && b.isSubmitted());
                        if (alreadySubmitted) {
                            player.sendMessage("You have already submitted a ballot for this election.");
                            return;
                        }
                        VoteSessionManager.open(player.getUniqueId(), election.getId(), voter.getId(), latest.getSystem());
                        new BallotIntroMenu(player, electionsService, election.getId(), plugin).open();
                    }
                }.runTask(Elections.getInstance());
            }
        }.runTaskAsynchronously(Elections.getInstance());
    }

    private Optional<Election> findElectionByBlock(Block block) {
        Location loc = block.getLocation();
        World w = loc.getWorld();
        if (w == null) return Optional.empty();
        String worldName = w.getName();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        for (Election e : electionsService.listElectionsSnapshot()) {
            for (Poll p : e.getPolls()) {
                if (p.getWorld().equalsIgnoreCase(worldName) && p.getX() == x && p.getY() == y && p.getZ() == z) {
                    return Optional.of(e);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Formats playtime minutes into a human-readable string.
     * Shows hours if >= 60 minutes, otherwise shows minutes.
     */
    private String formatPlaytime(long minutes) {
        if (minutes >= 60) {
            long hours = minutes / 60;
            long remainingMinutes = minutes % 60;
            if (remainingMinutes == 0) {
                return hours + " hour" + (hours != 1 ? "s" : "");
            }
            return hours + "h " + remainingMinutes + "m";
        }
        return minutes + " minute" + (minutes != 1 ? "s" : "");
    }
}
