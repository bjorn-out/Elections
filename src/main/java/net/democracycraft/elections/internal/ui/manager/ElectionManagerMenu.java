package net.democracycraft.elections.internal.ui.manager;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.democracycraft.elections.Elections;
import net.democracycraft.elections.api.model.Election;
import net.democracycraft.elections.api.service.ElectionsService;
import net.democracycraft.elections.internal.ui.ParentMenuImp;
import net.democracycraft.elections.api.ui.AutoDialog;
import net.democracycraft.elections.internal.ui.common.LoadingMenu;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Parent dialog to manage a specific election, summarizing data and navigating to child menus.
 * All texts are configurable via a per-menu YAML located under data/menus/ElectionManagerMenu.yml.
 */
public class ElectionManagerMenu extends ParentMenuImp {

    private final ElectionsService electionService;
    private final int electionId;
    private final Elections plugin = Elections.getInstance();

    /**
     * @param player player opening the dialog
     * @param electionsService elections service
     * @param electionId election identifier
     */
    public ElectionManagerMenu(Player player, ElectionsService electionsService, int electionId) {
        super(player, "election_manager_" + electionId);
        this.electionService = electionsService;
        this.electionId = electionId;
        this.setDialog(build());
    }

    /**
     * Menu configuration DTO.
     */
    public static class Config implements Serializable {
        public String header = "<gold><bold>Election Manager:</bold></gold> <white><bold>%election_title%</bold></white> <gray>(#%election_id%)</gray>";
        public String notFound = "<red><bold>Election not found.</bold></red>";
        public String notFoundTitle = "<gold><bold>Election Manager</bold></gold>";

        public String statusLabel = "<aqua>Status: </aqua>";
        public String votersLabel = "<aqua>Voters: </aqua>";
        public String pollsLabel = "<aqua>Polls: </aqua>";
        public String candidatesLabel = "<aqua>Candidates: </aqua>";
        public String closesLabel = "<aqua>Closes: </aqua>";
        public String valueGrayFormat = "<gray>%value%</gray>";

        public String ballotsSubmittedBtn = "<green><bold>Ballots submitted:</bold></green> <white>%ballots_count%</white>";
        public String editTitleBtn = "<yellow><bold>Edit Title</bold></yellow>";
        public String pollsBtn = "<yellow><bold>Polls</bold></yellow> <dark_gray>(%polls_count%)</dark_gray>";
        public String durationBtn = "<yellow><bold>Duration</bold></yellow>";
        public String statusBtn = "<yellow><bold>Status:</bold></yellow> <gray>%status%</gray>";
        public String systemAndMinimumBtn = "<yellow><bold>System & Minimum</bold></yellow>";
        public String ballotModeBtn = "<yellow><bold>Ballot Mode</bold></yellow> <gray>%ballot_mode%</gray>";
        public String candidatesBtn = "<yellow><bold>Candidates</bold></yellow> <dark_gray>(%candidates_count%)</dark_gray>";
        public String requirementsBtn = "<yellow><bold>Requirements</bold></yellow>";
        public String deleteBtn = "<red><bold>Delete Election</bold></red>";
        public String deleteConfirmBtn = "<red><bold>Confirm Delete</bold></red>";
        public String deleteConfirmFinalBtn = "<red><bold>Really Delete</bold></red>";
        public String deleteCancelledMsg = "<yellow>Deletion cancelled.</yellow>";
        public String deletedMsg = "<green><bold>Election deleted.</bold></green>";
        public String deleteFailedMsg = "<red><bold>Could not delete election.</bold></red>";
        public String closeBtn = "<red><bold>Close</bold></red>";

        public String statusOpenFormat = "<green><bold>OPEN</bold></green>";
        public String statusClosedFormat = "<red><bold>CLOSED</bold></red>";
        public String statusDeletedFormat = "<red><bold>DELETED</bold></red>";

        public String statusUpdatedMsg = "<green><bold>Status updated.</bold></green>";
        public String statusUpdateFailedMsg = "<red><bold>Could not update status.</bold></red>";
        public String cannotOpenBlockMsg = "<red><bold>Cannot open: minimum votes exceeds number of candidates.</bold></red>";

        public String durationNever = "Never";
        public String durationFormat = "%days% d %hours% h %minutes% m";

        public String yamlHeader = "ElectionManagerMenu configuration. Placeholders: %player%, %election_title%, %election_id%, %status%, %voters_count%, %polls_count%, %candidates_count%, %ballots_count%, %days%, %hours%, %minutes%, %ballot_mode%.";
        /** Loading dialog title and message for async actions (open/close/delete). */
        public String loadingTitle = "<gold><bold>Working</bold></gold>";
        public String loadingMessage = "<gray><italic>Applying changes…</italic></gray>";

        public String permissionDeniedMsg = "<red>You don't have permission.</red>";
        public String deleteConfirmTitle = "<red><bold>Delete Election?</bold></red>";
        public String deleteConfirmFinalTitle = "<red><bold>Really delete?</bold></red>";
        /** Whether the dialog can be closed with Escape. */
        public boolean canCloseWithEscape = true;
        public Config() {}

        public static void loadConfig() {
            var yml = getOrCreateMenuYml(ElectionManagerMenu.Config.class, "ElectionManagerMenu.yml", new ElectionManagerMenu.Config().yamlHeader);
            yml.loadOrCreate(ElectionManagerMenu.Config::new);
        }
    }

    /**
     * Builds the dialog, hiding destructive actions when election is DELETED.
     * @return Dialog instance to show
     */
    private Dialog build() {
        // Load or create config YML for this menu with header
        var menuYml = getOrCreateMenuYml(Config.class, getMenuConfigFileName(), new Config().yamlHeader);
        Config config = menuYml.loadOrCreate(Config::new);

        Optional<Election> optional = electionService.getElection(electionId);
        if (optional.isEmpty()) {
            AutoDialog.Builder dialogBuilder = getAutoDialogBuilder();
            dialogBuilder.title(miniMessage(config.notFoundTitle));
            dialogBuilder.addBody(DialogBody.plainMessage(miniMessage(config.notFound)));
            return dialogBuilder.build();
        }
        Election election = optional.get();

        // Prepare placeholders
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%election_title%", election.getTitle());
        placeholders.put("%election_id%", String.valueOf(election.getId()));

        String statusFormatted = switch (election.getStatus()) {
            case OPEN -> config.statusOpenFormat;
            case CLOSED -> config.statusClosedFormat;
            case DELETED -> config.statusDeletedFormat;
        };
        placeholders.put("%status%", statusFormatted);

        placeholders.put("%voters_count%", String.valueOf(election.getVoterCount()));
        placeholders.put("%polls_count%", String.valueOf(election.getPolls().size()));
        placeholders.put("%candidates_count%", String.valueOf(election.getCandidates().size()));
        placeholders.put("%ballots_count%", String.valueOf(election.getBallots().size()));
        placeholders.put("%ballot_mode%", election.getBallotMode().name());

        Integer durationDays = election.getDurationDays();
        var durationTimeDto = election.getDurationTime();
        int daysCount = durationDays == null ? 0 : durationDays;
        int hoursCount = durationTimeDto == null ? 0 : durationTimeDto.hour();
        int minutesCount = durationTimeDto == null ? 0 : durationTimeDto.minute();
        String durationString = (durationDays == null && durationTimeDto == null) ? config.durationNever
                : applyPlaceholders(config.durationFormat, Map.of("%days%", String.valueOf(daysCount), "%hours%", String.valueOf(hoursCount), "%minutes%", String.valueOf(minutesCount)));

        AutoDialog.Builder builder = getAutoDialogBuilder();
        builder.title(miniMessage(config.header, placeholders));
        builder.canCloseWithEscape(config.canCloseWithEscape);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        // Overview/info body
        Component overviewBody = Component.newline()
                .append(miniMessage(config.statusLabel, placeholders)).append(miniMessage(applyPlaceholders(config.valueGrayFormat, Map.of("%value%", placeholders.get("%status%"))), null))
                .appendNewline().append(miniMessage(config.votersLabel, placeholders)).append(miniMessage(applyPlaceholders(config.valueGrayFormat, Map.of("%value%", placeholders.get("%voters_count%"))), null))
                .appendNewline().append(miniMessage(config.pollsLabel, placeholders)).append(miniMessage(applyPlaceholders(config.valueGrayFormat, Map.of("%value%", placeholders.get("%polls_count%"))), null))
                .appendNewline().append(miniMessage(config.candidatesLabel, placeholders)).append(miniMessage(applyPlaceholders(config.valueGrayFormat, Map.of("%value%", placeholders.get("%candidates_count%"))), null))
                .appendNewline().append(miniMessage(config.closesLabel, placeholders)).append(miniMessage(applyPlaceholders(config.valueGrayFormat, Map.of("%value%", durationString)), null));
        builder.addBody(DialogBody.plainMessage(overviewBody));

        // Buttons
        builder.button(miniMessage(config.ballotsSubmittedBtn, placeholders), context -> new ElectionManagerMenu(context.player(), electionService, electionId).open());
        builder.button(miniMessage(config.editTitleBtn, placeholders), context -> new TitleEditMenu(context.player(), this, electionService, electionId).open());
        builder.button(miniMessage(config.pollsBtn, placeholders), context -> new PollsConfigMenu(context.player(), this, plugin, electionService, electionId).open());
        builder.button(miniMessage(config.durationBtn, placeholders), context -> new DurationMenu(context.player(), this, electionService, electionId).open());

        builder.button(miniMessage(config.statusBtn, placeholders), context -> {
            // Determine action and perform DB writes asynchronously
            switch (election.getStatus()) {
                case OPEN -> {
                    new LoadingMenu(context.player(), miniMessage(config.loadingTitle, placeholders), miniMessage(config.loadingMessage, placeholders)).open();
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            boolean ok = electionService.closeElection(electionId, context.player().getName());
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    context.player().sendMessage(ok ? miniMessage(config.statusUpdatedMsg, placeholders) : miniMessage(config.statusUpdateFailedMsg, placeholders));
                                    new ElectionManagerMenu(context.player(), electionService, electionId).open();
                                }
                            }.runTask(plugin);
                        }
                    }.runTaskAsynchronously(plugin);
                }
                case CLOSED, DELETED -> {
                    // Guard on main: for BLOCK, ensure minVotes <= candidates
                    var e = electionService.getElection(electionId).orElse(null);
                    if (e != null && e.getSystem() == net.democracycraft.elections.internal.data.VotingSystem.BLOCK) {
                        int cands = e.getCandidates().size();
                        int min = Math.max(1, e.getMinimumVotes());
                        if (min > cands) {
                            context.player().sendMessage(miniMessage(config.cannotOpenBlockMsg, placeholders));
                            new ElectionManagerMenu(context.player(), electionService, electionId).open();
                            return;
                        }
                    }
                    new LoadingMenu(context.player(), miniMessage(config.loadingTitle, placeholders), miniMessage(config.loadingMessage, placeholders)).open();
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            boolean ok = electionService.openElection(electionId, context.player().getName());
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    context.player().sendMessage(ok ? miniMessage(config.statusUpdatedMsg, placeholders) : miniMessage(config.statusUpdateFailedMsg, placeholders));
                                    new ElectionManagerMenu(context.player(), electionService, electionId).open();
                                }
                            }.runTask(plugin);
                        }
                    }.runTaskAsynchronously(plugin);
                }
                default -> new BukkitRunnable() {
                    @Override
                    public void run() {
                        boolean ok = electionService.openElection(electionId, context.player().getName());
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                context.player().sendMessage(ok ? miniMessage(config.statusUpdatedMsg, placeholders) : miniMessage(config.statusUpdateFailedMsg, placeholders));
                                new ElectionManagerMenu(context.player(), electionService, electionId).open();
                            }
                        }.runTask(plugin);
                    }
                }.runTaskAsynchronously(plugin);
            }
        });

        builder.button(miniMessage(config.systemAndMinimumBtn, placeholders), context -> new SystemAndMinimumMenu(context.player(), this, electionService, electionId).open());
        builder.button(miniMessage(config.ballotModeBtn, placeholders), context -> new BallotModeMenu(context.player(), this, electionService, electionId).open());
        builder.button(miniMessage(config.candidatesBtn, placeholders), context -> new CandidatesAddMenu(context.player(), this, electionService, electionId).open());
        builder.button(miniMessage(config.requirementsBtn, placeholders), context -> new RequirementsMenu(context.player(), this, electionService, electionId).open());

        // Delete with double confirmation (requires manager and delete permission); hidden for DELETED elections
        if (election.getStatus() != net.democracycraft.elections.internal.data.ElectionStatus.DELETED) {
            builder.button(miniMessage(config.deleteBtn, placeholders), context -> {
                if ((!context.player().hasPermission("elections.manager") || !context.player().hasPermission("elections.delete")) && !context.player().hasPermission("elections.admin")) {
                    context.player().sendMessage(miniMessage(config.permissionDeniedMsg));
                    return;
                }
                AutoDialog.Builder confirm1 = getAutoDialogBuilder();
                confirm1.title(miniMessage(config.deleteConfirmTitle, placeholders));
                confirm1.afterAction(DialogBase.DialogAfterAction.CLOSE);
                confirm1.button(miniMessage(config.deleteConfirmBtn, placeholders), c1 -> {
                    AutoDialog.Builder confirm2 = getAutoDialogBuilder();
                    confirm2.title(miniMessage(config.deleteConfirmFinalTitle, placeholders));
                    confirm2.afterAction(DialogBase.DialogAfterAction.CLOSE);
                    confirm2.button(miniMessage(config.deleteConfirmFinalBtn, placeholders), c2 -> {
                        new LoadingMenu(c2.player(), miniMessage(config.loadingTitle, placeholders), miniMessage(config.loadingMessage, placeholders)).open();
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                boolean ok = electionService.deleteElection(electionId, c2.player().getName());
                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        if (ok) {
                                            plugin.getLogger().info("[DeleteElection] actor=" + c2.player().getName() + ", electionId=" + electionId);
                                            c2.player().sendMessage(miniMessage(config.deletedMsg));
                                        } else {
                                            c2.player().sendMessage(miniMessage(config.deleteFailedMsg));
                                        }
                                        new ElectionManagerMenu(c2.player(), electionService, electionId).open();
                                    }
                                }.runTask(plugin);
                            }
                        }.runTaskAsynchronously(plugin);
                    });
                    confirm2.button(miniMessage(config.closeBtn, placeholders), c2 -> new ElectionManagerMenu(c2.player(), electionService, electionId).open());
                    c1.player().showDialog(confirm2.build());
                });
                confirm1.button(miniMessage(config.closeBtn, placeholders), c1 -> { c1.player().sendMessage(miniMessage(config.deleteCancelledMsg)); new ElectionManagerMenu(c1.player(), electionService, electionId).open(); });
                context.player().showDialog(confirm1.build());
            });
        }

        builder.button(miniMessage(config.closeBtn, placeholders), context -> {});

        return builder.build();
    }
}
