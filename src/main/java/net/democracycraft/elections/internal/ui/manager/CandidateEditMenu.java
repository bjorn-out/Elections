package net.democracycraft.elections.internal.ui.manager;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.democracycraft.elections.Elections;
import net.democracycraft.elections.api.model.Candidate;
import net.democracycraft.elections.api.model.Election;
import net.democracycraft.elections.api.service.ElectionsService;
import net.democracycraft.elections.api.ui.AutoDialog;
import net.democracycraft.elections.api.ui.ParentMenu;
import net.democracycraft.elections.internal.ui.ChildMenuImp;
import net.democracycraft.elections.internal.ui.common.ConfirmationMenu;
import net.democracycraft.elections.internal.ui.common.LoadingMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.Serializable;
import java.util.Map;
import java.util.Optional;

public class CandidateEditMenu extends ChildMenuImp {

    enum Keys { NAME, PARTY }

    private final ElectionsService electionsService;
    private final int electionId;
    private final int candidateId;

    public CandidateEditMenu(Player player, ParentMenu parent, ElectionsService electionsService, int electionId, int candidateId) {
        super(player, parent, "candidate_edit_" + electionId + "_" + candidateId);
        this.electionsService = electionsService;
        this.electionId = electionId;
        this.candidateId = candidateId;
    }

    public static class Config implements Serializable {
        public String title = "<gold><bold>Edit Candidate</bold></gold>";
        public String nameLabel = "<aqua>Name</aqua>";
        public String partyLabel = "<aqua>Party</aqua>";
        public String saveBtn = "<green><bold>Save Changes</bold></green>";
        public String deleteBtn = "<red><bold>Delete Candidate</bold></red>";
        public String backBtn = "<gray>Back</gray>";

        public String confirmDelete = "<red>Are you sure you want to delete this candidate?</red>";
        public String deletedMsg = "<green>Candidate deleted.</green>";
        public String deleteFailed = "<red>Could not delete candidate.</red>";
        public String updatedMsg = "<green>Candidate updated.</green>";
        public String updateFailed = "<red>Could not update candidate. The name may already be in use.</red>";
        public String emptyError = "<red>Value cannot be empty.</red>";

        public String loadingTitleSave = "<gold><bold>Saving</bold></gold>";
        public String loadingMessageSave = "<gray>Saving changes...</gray>";
        public String loadingTitleDelete = "<gold><bold>Deleting</bold></gold>";
        public String loadingMessageDelete = "<gray>Deleting candidate...</gray>";

        public String yamlHeader = "CandidateEditMenu configuration. Placeholders: %candidate_name%, %candidate_party%, %candidate_id%, %election_id%.";
        public boolean canCloseWithEscape = true;

        public Config() {}

        public static void loadConfig() {
            var yml = getOrCreateMenuYml(Config.class, "CandidateEditMenu.yml", new Config().yamlHeader);
            yml.loadOrCreate(Config::new);
        }
    }

    @Override
    public void open() {
        Optional<Election> electionOpt = electionsService.getElection(electionId);
        if (electionOpt.isEmpty()) {
            getParentMenu().open();
            return;
        }
        Optional<Candidate> candidateOpt = electionOpt.get().getCandidates().stream()
                .filter(candidate -> candidate.getId() == candidateId).findFirst();

        if (candidateOpt.isEmpty()) {
            new CandidateListMenu(player, getParentMenu(), electionsService, electionId).open();
            return;
        }
        Candidate candidate = candidateOpt.get();

        new LoadingMenu(player).open();

        Elections.getInstance().getPlayerHeadCache().getPlayerHead(candidate.getName()).thenAccept(head -> {
            new BukkitRunnable() {
                @Override
                public void run() {
                     setDialog(build(candidate, head));
                     CandidateEditMenu.super.open();
                }
            }.runTask(Elections.getInstance());
        });
    }

    private Dialog build(Candidate candidate, ItemStack head) {
        var menuYml = getOrCreateMenuYml(Config.class, getMenuConfigFileName(), new Config().yamlHeader);
        Config config = menuYml.loadOrCreate(Config::new);

        Map<String, String> placeholders = Map.of(
                "%candidate_name%", candidate.getName(),
                "%candidate_party%", candidate.getParty() == null ? "" : candidate.getParty(),
                "%candidate_id%", String.valueOf(candidate.getId()),
                "%election_id%", String.valueOf(electionId)
        );

        AutoDialog.Builder dialogBuilder = getAutoDialogBuilder();
        dialogBuilder.title(miniMessage(config.title, placeholders));
        dialogBuilder.canCloseWithEscape(config.canCloseWithEscape);



        if (head != null && head.getType() != Material.AIR) {
            dialogBuilder.addBody(DialogBody.item(head).showTooltip(true).build());
        }

        dialogBuilder.addInput(DialogInput.text(Keys.NAME.name(), miniMessage(config.nameLabel, placeholders)).initial(candidate.getName()).labelVisible(true).build());
        dialogBuilder.addInput(DialogInput.text(Keys.PARTY.name(), miniMessage(config.partyLabel, placeholders)).initial(candidate.getParty() == null ? "" : candidate.getParty()).labelVisible(true).build());

        dialogBuilder.buttonWithPlayer(miniMessage(config.saveBtn, placeholders), null, (p, response) -> {
            String newName = response.getText(Keys.NAME.name());
            String newParty = response.getText(Keys.PARTY.name());

            if (newName == null || newName.isBlank()) {
                p.sendMessage(miniMessage(config.emptyError, placeholders));
                open();
                return;
            }
            save(p, newName, newParty, config, placeholders);
        });

        dialogBuilder.button(miniMessage(config.deleteBtn, placeholders), context -> {
            new ConfirmationMenu(context.player(), this, config.confirmDelete,player -> {
                  new LoadingMenu(player, miniMessage(config.loadingTitleDelete, placeholders), miniMessage(config.loadingMessageDelete, placeholders)).open();
                   new BukkitRunnable() {
                        @Override
                        public void run() {
                            boolean res = electionsService.removeCandidate(electionId, candidateId, player.getName());
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    if(res) player.sendMessage(miniMessage(config.deletedMsg, placeholders));
                                    else player.sendMessage(miniMessage(config.deleteFailed, placeholders));
                                    new CandidateListMenu(player, getParentMenu(), electionsService, electionId).open();
                                }
                            }.runTask(Elections.getInstance());
                        }
                   }.runTaskAsynchronously(Elections.getInstance());
              }).open();
        });

        dialogBuilder.button(miniMessage(config.backBtn, placeholders), context -> new CandidateListMenu(context.player(), getParentMenu(), electionsService, electionId).open());
        return dialogBuilder.build();
    }

    private void save(Player player, String name, String party, Config config, Map<String, String> placeholders) {
        new LoadingMenu(player, miniMessage(config.loadingTitleSave, placeholders), miniMessage(config.loadingMessageSave, placeholders)).open();
        new BukkitRunnable() {
            @Override
            public void run() {
                Optional<Candidate> result = electionsService.updateCandidate(electionId, candidateId, name, party, player.getName());
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (result.isPresent()) {
                            player.sendMessage(miniMessage(config.updatedMsg, placeholders));
                            new CandidateListMenu(player, getParentMenu(), electionsService, electionId).open();
                        } else {
                            player.sendMessage(miniMessage(config.updateFailed, placeholders));
                            open();
                        }
                    }
                }.runTask(Elections.getInstance());
            }
        }.runTaskAsynchronously(Elections.getInstance());
    }
}
