package net.democracycraft.elections.internal.command.subcommands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.democracycraft.elections.Elections;
import net.democracycraft.elections.api.model.Candidate;
import net.democracycraft.elections.api.model.Election;
import net.democracycraft.elections.api.model.Voter;
import net.democracycraft.elections.api.service.ElectionsService;
import net.democracycraft.elections.api.service.GitHubGistService;
import net.democracycraft.elections.internal.command.framework.CommandContext;
import net.democracycraft.elections.internal.command.framework.Subcommand;
import net.democracycraft.elections.internal.util.config.DataFolder;
import net.democracycraft.elections.internal.util.export.BallotCsvFormatter;
import net.democracycraft.elections.internal.util.export.ElectionMarkdownFormatter;
import net.democracycraft.elections.internal.util.export.ExportMessagesConfig;
import net.democracycraft.elections.internal.util.export.github.GitHubGistClient;
import net.democracycraft.elections.internal.util.export.local.queue.LocalExportedElectionQueue;
import net.democracycraft.elections.internal.util.text.MiniMessageUtil;
import net.democracycraft.elections.internal.util.yml.AutoYML;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.democracycraft.elections.internal.command.subcommands.ExportCommand.Mode.BOTH;
import static net.democracycraft.elections.internal.command.subcommands.ExportCommand.Mode.LOCAL;

/**
 * Export-related operations grouped under a single subcommand.
 *
 * <p>Supported usages:
 * <ul>
 *     <li>export &lt;id&gt;                          - publish remotely (user-safe)</li>
 *     <li>export local &lt;id&gt;                    - save locally to the queue</li>
 *     <li>export both &lt;id&gt;                     - publish remotely and also save locally</li>
 *     <li>export admin &lt;id&gt;                    - like export &lt;id&gt; but with voter names</li>
 *     <li>export admin local &lt;id&gt;              - local-only with voter names</li>
 *     <li>export admin both &lt;id&gt;               - remote + local with voter names</li>
 *     <li>export delete &lt;id&gt; [confirm]         - delete a remote publication (currently not supported)</li>
 *     <li>export dispatch                       - process the entire local queue (managers)</li>
 *     <li>export ballots &lt;local|online&gt; &lt;id&gt;   - export only ballots (anonymous), JSON array-of-arrays (candidate names)</li>
 *     <li>export ballots admin &lt;local|online&gt; &lt;id&gt; - export only ballots as JSON with voter names</li>
 * </ul>
 * </p>
 */
public class ExportCommand implements Subcommand {

    /** Export destination mode. */
    enum Mode { REMOTE, LOCAL, BOTH }

    @Override
    public List<String> names() {
        return List.of("export");
    }

    @Override
    public String permission() {
        return "elections.export";
    }

    @Override
    public String usage() {
        return "export <id> | export local <id> | export both <id> | export admin [local|both] <id> | export delete <id> [confirm] | export dispatch | export ballots <local|online> <id> | export ballots admin <local|online> <id>";
    }

    @Override
    public void execute(CommandContext context) {
        if (context.args().length < 1) {
            context.usage(usage());
            return;
        }

        // Reload export messages on each invocation so live edits to
        // export-messages.yml are applied immediately.
        AutoYML<ExportMessagesConfig> exportMessagesYml = AutoYML.create(
                ExportMessagesConfig.class,
                "export-messages",
                DataFolder.EXPORT_MESSAGES,
                ExportMessagesConfig.defaultHeader()
        );
        ExportMessagesConfig messages = exportMessagesYml.loadOrCreate(ExportMessagesConfig::new);

        String sub = context.args()[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "admin" -> executeAdminExport(context, messages);
            case "delete" -> executeDelete(context, messages);
            case "dispatch" -> executeDispatch(context, messages);
            case "local" -> executeUserExport(context, Mode.LOCAL, messages);
            case "both" -> executeUserExport(context, Mode.BOTH, messages);
            case "ballots" -> executeBallotsRouter(context, messages);
            default -> executeUserExport(context, Mode.REMOTE, messages);
        }
    }

    // ---------------------------------------------------------------------
    // Standard user export
    // ---------------------------------------------------------------------

    /**
     * Execute a user-facing export of an election.
     *
     * @param context command context
     * @param mode    export destination mode
     */
    private void executeUserExport(CommandContext context, Mode mode, ExportMessagesConfig messages) {
        Elections plugin = context.plugin();

        int idArgIndex = (mode == Mode.REMOTE ? 0 : 1);
        int electionId = context.requireInt(idArgIndex, "id");

        ElectionsService electionsService = context.electionsService();
        electionsService.getElectionAsync(electionId).whenComplete((optionalElection, throwable) -> {
            if (throwable != null) {
                String raw = messages.errorLookupFailed.replace("%error%", safeError(throwable));
                Component msg = MiniMessageUtil.parseOrPlain(raw);
                Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
                return;
            }

            if (optionalElection.isEmpty()) {
                Component msg = MiniMessageUtil.parseOrPlain(messages.errorElectionNotFound);
                Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Election election = optionalElection.get();
                String json = election.toJson(false, id -> null);
                String markdown = ElectionMarkdownFormatter.toMarkdown(
                        election,
                        null,
                        false
                );

                LocalExportedElectionQueue queue = plugin.getLocalExportQueue();
                GitHubGistService gistService = new GitHubGistClient();

                switch (mode) {
                    case LOCAL -> handleUserLocalExport(context, messages, queue, election, json);
                    case BOTH -> handleUserBothExport(context, messages, electionsService, queue, gistService, election, json, markdown);
                    case REMOTE -> handleUserRemoteExport(context, messages, electionsService, queue, gistService, election, json, markdown);
                }
            });
        });
    }

    private void handleUserLocalExport(CommandContext context,
                                       ExportMessagesConfig messages,
                                       LocalExportedElectionQueue queue,
                                       Election election,
                                       String json) {
        queue.enqueue(election.getId(), json).thenAccept(file -> {
            String raw = messages.userLocalSaved.replace("%file%", file.getName());
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            context.sender().sendMessage(msg);
            context.plugin().getLogger().info("[ExportLocal] actor=" + context.sender().getName() + ", electionId=" + election.getId() + ", file=" + file.getName());
        }).exceptionally(ex -> {
            String raw = messages.errorCouldNotSaveLocal.replace("%error%", safeError(ex));
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            Bukkit.getScheduler().runTask(context.plugin(), () -> context.sender().sendMessage(msg));
            return null;
        });
    }

    private void handleUserBothExport(CommandContext context,
                                      ExportMessagesConfig messages,
                                      ElectionsService electionsService,
                                      LocalExportedElectionQueue queue,
                                      GitHubGistService gistService,
                                      Election election,
                                      String json,
                                      String markdown) {
        gistService.publish(
                "election-" + election.getId() + ".json",
                json,
                "election-" + election.getId() + ".md",
                markdown
        ).thenAccept(url -> {
            electionsService.markExportedAsync(election.getId(), context.sender().getName());
            String raw = messages.userPublished.replace("%url%", url);
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            context.sender().sendMessage(msg);
            context.plugin().getLogger().info("[ExportBoth] actor=" + context.sender().getName() + ", electionId=" + election.getId() + ", url=" + url);
            // also keep a local copy (fire-and-forget)
            queue.enqueue(election.getId(), json);
        }).exceptionally(ex -> {
            // Fallback: save locally
            queue.enqueue(election.getId(), json).thenAccept(file -> {
                String raw = messages.errorRemoteFailedLocalSaved.replace("%file%", file.getName());
                Component msg = MiniMessageUtil.parseOrPlain(raw);
                Bukkit.getScheduler().runTask(context.plugin(), () -> context.sender().sendMessage(msg));
                context.plugin().getLogger().warning("[ExportBoth->LocalFallback] actor=" + context.sender().getName() + ", electionId=" + election.getId() + ", error=" + safeError(ex));
            });
            return null;
        });
    }

    private void handleUserRemoteExport(CommandContext context,
                                        ExportMessagesConfig messages,
                                        ElectionsService electionsService,
                                        LocalExportedElectionQueue queue,
                                        GitHubGistService gistService,
                                        Election election,
                                        String json,
                                        String markdown) {
        gistService.publish(
                "election-" + election.getId() + ".json",
                json,
                "election-" + election.getId() + ".md",
                markdown
        ).thenAccept(url -> {
            electionsService.markExportedAsync(election.getId(), context.sender().getName());
            String raw = messages.userPublished.replace("%url%", url);
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            context.sender().sendMessage(msg);
            context.plugin().getLogger().info("[Export] actor=" + context.sender().getName() + ", electionId=" + election.getId() + ", url=" + url);
        }).exceptionally(ex -> {
            // Fallback: save locally
            queue.enqueue(election.getId(), json).thenAccept(file -> {
                String raw = messages.errorRemoteFailedLocalSaved.replace("%file%", file.getName());
                Component msg = MiniMessageUtil.parseOrPlain(raw);
                Bukkit.getScheduler().runTask(context.plugin(), () -> context.sender().sendMessage(msg));
                context.plugin().getLogger().warning("[Export->LocalFallback] actor=" + context.sender().getName() + ", electionId=" + election.getId() + ", error=" + safeError(ex));
            });
            return null;
        });
    }

    // ---------------------------------------------------------------------
    // Admin export (with voter names)
    // ---------------------------------------------------------------------

    /**
     * Execute an admin export, including voter names and detailed ballots.
     *
     * @param context command context
     */
    private void executeAdminExport(CommandContext context, ExportMessagesConfig messages) {
        Elections plugin = context.plugin();

        if ((!context.sender().hasPermission("elections.export.admin") ||
             !context.sender().hasPermission("elections.manager")) &&
            !context.sender().hasPermission("elections.admin")) {
            Component msg = MiniMessageUtil.parseOrPlain(messages.errorNoPermission);
            context.sender().sendMessage(msg);
            return;
        }

        // Syntax: admin [local|both] <id>
        String[] args = context.args();
        Mode mode;
        int idIndex;
        if (args.length >= 3 && ("local".equalsIgnoreCase(args[1]) || "both".equalsIgnoreCase(args[1]))) {
            mode = "local".equalsIgnoreCase(args[1]) ? LOCAL : BOTH;
            idIndex = 2;
        } else {
            mode = Mode.REMOTE;
            idIndex = 1;
        }

        int electionId = context.requireInt(idIndex, "id");
        ElectionsService electionsService = context.electionsService();

        electionsService.getElectionAsync(electionId).whenComplete((optionalElection, throwable) -> {
            if (throwable != null) {
                String raw = messages.errorLookupFailed.replace("%error%", safeError(throwable));
                Component msg = MiniMessageUtil.parseOrPlain(raw);
                Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
                return;
            }

            if (optionalElection.isEmpty()) {
                Component msg = MiniMessageUtil.parseOrPlain(messages.errorElectionNotFound);
                Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
                return;
            }

            electionsService.listVotersAsync(electionId).whenComplete((voters, votersError) -> {
                if (votersError != null) {
                    String raw = messages.errorLookupFailed.replace("%error%", safeError(votersError));
                    Component msg = MiniMessageUtil.parseOrPlain(raw);
                    Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
                    return;
                }

                Map<Integer, String> voterNameById = voters.stream()
                        .collect(Collectors.toMap(
                                Voter::getId,
                                Voter::getName,
                                (first, second) -> first
                        ));
                Function<Integer, String> voterNameProvider = voterNameById::get;

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    Election election = optionalElection.get();
                    String json = election.toJson(true, voterNameProvider);
                    String markdown = ElectionMarkdownFormatter.toMarkdown(
                            election,
                            voterNameProvider,
                            true
                    );

                    LocalExportedElectionQueue queue = plugin.getLocalExportQueue();
                    GitHubGistService gistService = new GitHubGistClient();

                    switch (mode) {
                        case LOCAL -> handleAdminLocalExport(context, messages, queue, electionId, json);
                        case BOTH -> handleAdminBothExport(context, messages, electionsService, queue, gistService, electionId, json, markdown);
                        case REMOTE -> handleAdminRemoteExport(context, messages, electionsService, queue, gistService, electionId, json, markdown);
                    }
                });
            });
        });
    }

    private void handleAdminLocalExport(CommandContext context,
                                        ExportMessagesConfig messages,
                                        LocalExportedElectionQueue queue,
                                        int electionId,
                                        String json) {
        queue.enqueue(electionId, json).thenAccept(file -> {
            String raw = messages.adminLocalSaved.replace("%file%", file.getName());
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            context.sender().sendMessage(msg);
            context.plugin().getLogger().info("[ExportAdminLocal] actor=" + context.sender().getName() + ", electionId=" + electionId + ", file=" + file.getName());
        }).exceptionally(ex -> {
            String raw = messages.errorCouldNotSaveLocal.replace("%error%", safeError(ex));
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            Bukkit.getScheduler().runTask(context.plugin(), () -> context.sender().sendMessage(msg));
            return null;
        });
    }

    private void handleAdminBothExport(CommandContext context,
                                       ExportMessagesConfig messages,
                                       ElectionsService electionsService,
                                       LocalExportedElectionQueue queue,
                                       GitHubGistService gistService,
                                       int electionId,
                                       String json,
                                       String markdown) {
        gistService.publish(
                "election-admin-" + electionId + ".json",
                json,
                "election-admin-" + electionId + ".md",
                markdown
        ).thenAccept(url -> {
            electionsService.markExportedAsync(electionId, context.sender().getName());
            String raw = messages.adminPublished.replace("%url%", url);
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            context.sender().sendMessage(msg);
            context.plugin().getLogger().info("[ExportAdminBoth] actor=" + context.sender().getName() + ", electionId=" + electionId + ", url=" + url);
            // also keep a local copy
            queue.enqueue(electionId, json);
        }).exceptionally(ex -> {
            // Fallback: save locally
            queue.enqueue(electionId, json).thenAccept(file -> {
                String raw = messages.errorRemoteFailedLocalSaved.replace("%file%", file.getName());
                Component msg = MiniMessageUtil.parseOrPlain(raw);
                Bukkit.getScheduler().runTask(context.plugin(), () -> context.sender().sendMessage(msg));
                context.plugin().getLogger().warning("[ExportAdminBoth->LocalFallback] actor=" + context.sender().getName() + ", electionId=" + electionId + ", error=" + safeError(ex));
            });
            return null;
        });
    }

    private void handleAdminRemoteExport(CommandContext context,
                                         ExportMessagesConfig messages,
                                         ElectionsService electionsService,
                                         LocalExportedElectionQueue queue,
                                         GitHubGistService gistService,
                                         int electionId,
                                         String json,
                                         String markdown) {
        gistService.publish(
                "election-admin-" + electionId + ".json",
                json,
                "election-admin-" + electionId + ".md",
                markdown
        ).thenAccept(url -> {
            electionsService.markExportedAsync(electionId, context.sender().getName());
            String raw = messages.adminPublished.replace("%url%", url);
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            context.sender().sendMessage(msg);
            context.plugin().getLogger().info("[ExportAdmin] actor=" + context.sender().getName() + ", electionId=" + electionId + ", url=" + url);
        }).exceptionally(ex -> {
            // Fallback: save locally
            queue.enqueue(electionId, json).thenAccept(file -> {
                String raw = messages.errorRemoteFailedLocalSaved.replace("%file%", file.getName());
                Component msg = MiniMessageUtil.parseOrPlain(raw);
                Bukkit.getScheduler().runTask(context.plugin(), () -> context.sender().sendMessage(msg));
                context.plugin().getLogger().warning("[ExportAdmin->LocalFallback] actor=" + context.sender().getName() + ", electionId=" + electionId + ", error=" + safeError(ex));
            });
            return null;
        });
    }

    // ---------------------------------------------------------------------
    // Delete (currently unsupported, but kept for UX consistency)
    // ---------------------------------------------------------------------

    /**
     * Handle deletion of a previously exported resource.
     * <p>
     * Currently deletion is not supported for GitHub gists, so this method
     * simply validates permissions and arguments, then informs the user.
     * </p>
     *
     * @param context command context
     */
    private void executeDelete(CommandContext context, ExportMessagesConfig messages) {
        Elections plugin = context.plugin();

        if (!context.sender().hasPermission("elections.delete") &&
            !context.sender().hasPermission("elections.admin")) {
            Component msg = MiniMessageUtil.parseOrPlain(messages.errorNoPermission);
            context.sender().sendMessage(msg);
            return;
        }

        if (context.args().length < 2) {
            Component msg = MiniMessageUtil.parseOrPlain(messages.deleteIdRequired);
            context.sender().sendMessage(msg);
            return;
        }

        String id = context.args()[1];
        plugin.getLogger().info("[ExportDelete] actor=" + context.sender().getName() + ", id=" + id + ", result=unsupported_for_gists");

        String raw = messages.deleteUnsupported
                .replace("%id%", id);
        Component msg = MiniMessageUtil.parseOrPlain(raw);
        context.sender().sendMessage(msg);
    }

    // ---------------------------------------------------------------------
    // Dispatch queue
    // ---------------------------------------------------------------------

    /**
     * Dispatch all locally queued election exports and publish them remotely.
     *
     * @param context command context
     */
    private void executeDispatch(CommandContext context, ExportMessagesConfig messages) {
        Elections plugin = context.plugin();
        LocalExportedElectionQueue queue = plugin.getLocalExportQueue();
        ElectionsService electionsService = context.electionsService();
        GitHubGistService gistService = new GitHubGistClient();

        queue.processAll(gistService, electionsService, context.sender().getName()).thenAccept(report -> {
            String raw = messages.dispatchProcessed
                    .replace("%total%", String.valueOf(report.total()))
                    .replace("%uploaded%", String.valueOf(report.uploaded()))
                    .replace("%skipped%", String.valueOf(report.skipped()))
                    .replace("%failed%", String.valueOf(report.failed()));
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            context.sender().sendMessage(msg);
            plugin.getLogger().info("[ExportDispatch] actor=" + context.sender().getName() + ", total=" + report.total() + ", uploaded=" + report.uploaded() + ", skipped=" + report.skipped() + ", failed=" + report.failed());
        }).exceptionally(ex -> {
            String raw = messages.dispatchFailed.replace("%error%", safeError(ex));
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
            return null;
        });
    }

    // ---------------------------------------------------------------------
    // Ballots export (user)
    // ---------------------------------------------------------------------

    /**
     * Publish only the ballots of a given election as JSON (non-admin).
     *
     * @param context command context
     */
    private void executeBallotsExport(CommandContext context, ExportMessagesConfig messages) {
        Elections plugin = context.plugin();

        if (!context.sender().hasPermission("elections.export.ballots") &&
            !context.sender().hasPermission("elections.export")) {
            Component msg = MiniMessageUtil.parseOrPlain(messages.errorNoPermission);
            context.sender().sendMessage(msg);
            return;
        }

        if (context.args().length < 3) {
            String raw = messages.ballotsUsage
                    .replace("%label%", context.label());
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            context.sender().sendMessage(msg);
            return;
        }

        String mode = context.args()[1].toLowerCase(java.util.Locale.ROOT);
        boolean isLocal = "local".equals(mode);
        boolean isOnline = "online".equals(mode);

        if (!isLocal && !isOnline) {
            Component msg = MiniMessageUtil.parseOrPlain(messages.ballotsInvalidMode);
            context.sender().sendMessage(msg);
            return;
        }

        int electionId = context.requireInt(2, "id");
        ElectionsService electionsService = context.electionsService();
        electionsService.getElectionAsync(electionId).whenComplete((optionalElection, throwable) -> {
            if (throwable != null) {
                String raw = messages.errorLookupFailed.replace("%error%", safeError(throwable));
                Component msg = MiniMessageUtil.parseOrPlain(raw);
                Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
                return;
            }

            if (optionalElection.isEmpty()) {
                Component msg = MiniMessageUtil.parseOrPlain(messages.errorElectionNotFound);
                Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Election election = optionalElection.get();

                Map<Integer, String> candidateNameById = election.getCandidates().stream().collect(
                        Collectors.toMap(
                                Candidate::getId,
                                Candidate::getName,
                                (first, second) -> first,
                                LinkedHashMap::new
                        )
                );

                // Prepare data structure for CSV formatter (needs Map<String, Object>)
                List<Map<String, Object>> ballotsDetailed = election.getBallots().stream().map(vote -> {
                    Map<String, Object> ballot = new LinkedHashMap<>();
                    // No voter info for public export
                    List<String> selections = vote.getSelections().stream()
                            .map(candidateId -> candidateNameById.getOrDefault(candidateId, String.valueOf(candidateId)))
                            .collect(Collectors.toList());
                    ballot.put("selections", selections);
                    return ballot;
                }).collect(Collectors.toList());

                // JSON structure (List of List of Strings)
                List<List<String>> ballotsByName = ballotsDetailed.stream()
                        .map(m -> {
                            @SuppressWarnings("unchecked")
                            List<String> list = (List<String>) m.get("selections");
                            return list;
                        })
                        .collect(Collectors.toList());

                LinkedHashMap<String, Object> root = new LinkedHashMap<>();
                root.put("ballots", ballotsByName);

                Gson gson = new GsonBuilder()
                        .disableHtmlEscaping()
                        .setPrettyPrinting()
                        .create();
                String json = gson.toJson(root);
                String csv = BallotCsvFormatter.toCsv(ballotsDetailed, false);

                if (isLocal) {
                    saveBallotsLocal(context, messages, election.getId(), ballotsByName, json, csv, false);
                } else {
                    publishBallotsOnline(context, messages, election.getId(), ballotsByName, json, csv, false);
                }
            });
        });
    }

    // ---------------------------------------------------------------------
    // Ballots export (admin)
    // ---------------------------------------------------------------------

    /**
     * Publish only the ballots of a given election as JSON with voter names
     * (admin view).
     *
     * @param context command context
     */
    private void executeBallotsAdminExport(CommandContext context, ExportMessagesConfig messages) {
        Elections plugin = context.plugin();

        if (!context.sender().hasPermission("elections.export.ballots.admin") &&
            !context.sender().hasPermission("elections.admin")) {
            Component msg = MiniMessageUtil.parseOrPlain(messages.errorNoPermission);
            context.sender().sendMessage(msg);
            return;
        }

        if (context.args().length < 4) {
            String raw = messages.ballotsAdminUsage
                    .replace("%label%", context.label());
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            context.sender().sendMessage(msg);
            return;
        }

        String mode = context.args()[2].toLowerCase(java.util.Locale.ROOT);
        boolean isLocal = "local".equals(mode);
        boolean isOnline = "online".equals(mode);

        if (!isLocal && !isOnline) {
            Component msg = MiniMessageUtil.parseOrPlain(messages.ballotsInvalidMode);
            context.sender().sendMessage(msg);
            return;
        }

        int electionId = context.requireInt(3, "id");
        ElectionsService electionsService = context.electionsService();
        electionsService.getElectionAsync(electionId).whenComplete((optionalElection, throwable) -> {
            if (throwable != null) {
                String raw = messages.errorLookupFailed.replace("%error%", safeError(throwable));
                Component msg = MiniMessageUtil.parseOrPlain(raw);
                Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
                return;
            }

            if (optionalElection.isEmpty()) {
                Component msg = MiniMessageUtil.parseOrPlain(messages.errorElectionNotFound);
                Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
                return;
            }

            electionsService.listVotersAsync(electionId).whenComplete((voters, votersError) -> {
                if (votersError != null) {
                    String raw = messages.errorLookupFailed.replace("%error%", safeError(votersError));
                    Component msg = MiniMessageUtil.parseOrPlain(raw);
                    Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
                    return;
                }

                Map<Integer, String> voterNameById = voters.stream().collect(
                        Collectors.toMap(
                                Voter::getId,
                                Voter::getName,
                                (first, second) -> first
                        )
                );

                Function<Integer, String> voterNameProvider = voterNameById::get;

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    Election election = optionalElection.get();

                    Map<Integer, String> candidateNameById = election.getCandidates().stream().collect(
                            Collectors.toMap(
                                    Candidate::getId,
                                    Candidate::getName,
                                    (first, second) -> first,
                                    LinkedHashMap::new
                            )
                    );

                    List<Map<String, Object>> ballotsDetailed = election.getBallots().stream().map(vote -> {
                        Map<String, Object> ballot = new LinkedHashMap<>();
                        ballot.put("voter", voterNameProvider.apply(vote.getVoterId()));
                        List<String> selections = vote.getSelections().stream()
                                .map(candidateId -> candidateNameById.getOrDefault(candidateId, String.valueOf(candidateId)))
                                .collect(Collectors.toList());
                        ballot.put("selections", selections);
                        return ballot;
                    }).collect(Collectors.toList());

                    LinkedHashMap<String, Object> root = new LinkedHashMap<>();
                    root.put("ballots", ballotsDetailed);

                    Gson gson = new GsonBuilder()
                            .disableHtmlEscaping()
                            .setPrettyPrinting()
                            .create();
                    String json = gson.toJson(root);
                    String csv = BallotCsvFormatter.toCsv(ballotsDetailed, true);

                    if (isLocal) {
                        saveBallotsLocal(context, messages, election.getId(), ballotsDetailed, json, csv, true);
                    } else {
                        publishBallotsOnline(context, messages, election.getId(), ballotsDetailed, json, csv, true);
                    }
                });
            });
        });
    }

    // ---------------------------------------------------------------------
    // Ballots helpers
    // ---------------------------------------------------------------------

    private void saveBallotsLocal(CommandContext context,
                                  ExportMessagesConfig messages,
                                  int electionId,
                                  Object ballotsPayload,
                                  String json,
                                  String csv,
                                  boolean admin) {
        Elections plugin = context.plugin();
        int count = (ballotsPayload instanceof List<?> list) ? list.size() : 0;

        File base = new File(plugin.getDataFolder(), DataFolder.EXPORTS.getPath());
        File ballotsDir = new File(base, "ballots");

        if (!ballotsDir.exists() && !ballotsDir.mkdirs() && !ballotsDir.exists()) {
            String raw = messages.ballotsDirectoryCreateFailed;
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
            return;
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        File jsonFile = new File(ballotsDir, "ballots-" + electionId + "-" + timestamp + ".json");
        File csvFile = new File(ballotsDir, "ballots-" + electionId + "-" + timestamp + ".csv");

        try {
            try (FileOutputStream outputStream = new FileOutputStream(jsonFile)) {
                outputStream.write(json.getBytes(StandardCharsets.UTF_8));
            }
            try (FileOutputStream outputStream = new FileOutputStream(csvFile)) {
                outputStream.write(csv.getBytes(StandardCharsets.UTF_8));
            }

            String raw = (admin ? messages.ballotsAdminSavedLocal : messages.ballotsSavedLocal)
                    .replace("%file%", jsonFile.getName() + " & " + csvFile.getName())
                    .replace("%count%", String.valueOf(count));
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
            plugin.getLogger().info("[" + (admin ? "ExportBallotsAdminLocal" : "ExportBallotsLocal") + "] actor=" + context.sender().getName() + ", electionId=" + electionId + ", files=" + jsonFile.getName() + "," + csvFile.getName() + ", count=" + count);
        } catch (IOException io) {
            String raw = (admin ? messages.ballotsAdminLocalFailed : messages.ballotsLocalFailed)
                    .replace("%error%", safeError(io));
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
            plugin.getLogger().warning("[" + (admin ? "ExportBallotsAdminLocal" : "ExportBallotsLocal") + "] actor=" + context.sender().getName() + ", electionId=" + electionId + ", error=" + safeError(io));
        }
    }

    private void publishBallotsOnline(CommandContext context,
                                      ExportMessagesConfig messages,
                                      int electionId,
                                      Object ballotsPayload,
                                      String json,
                                      String csv,
                                      boolean admin) {
        Elections plugin = context.plugin();
        int count = (ballotsPayload instanceof List<?> list) ? list.size() : 0;

                GitHubGistService gistService = new GitHubGistClient();

        Map<String, String> files = new HashMap<>();
        files.put("ballots-" + electionId + ".json", json);
        files.put("ballots-" + electionId + ".csv", csv);

        gistService.publish(files).thenAccept(url -> {
            String raw = (admin ? messages.ballotsAdminPublished : messages.ballotsPublished)
                    .replace("%url%", url)
                    .replace("%count%", String.valueOf(count));
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
            plugin.getLogger().info("[" + (admin ? "ExportBallotsAdminOnline" : "ExportBallotsOnline") + "] actor=" + context.sender().getName() + ", electionId=" + electionId + ", url=" + url + ", count=" + count);
        }).exceptionally(ex -> {
            String raw = (admin ? messages.ballotsAdminPublishFailed : messages.ballotsPublishFailed)
                    .replace("%error%", safeError(ex));
            Component msg = MiniMessageUtil.parseOrPlain(raw);
            Bukkit.getScheduler().runTask(plugin, () -> context.sender().sendMessage(msg));
            plugin.getLogger().warning("[" + (admin ? "ExportBallotsAdminOnline" : "ExportBallotsOnline") + "] actor=" + context.sender().getName() + ", electionId=" + electionId + ", error=" + safeError(ex));
            return null;
        });
    }

    // ---------------------------------------------------------------------
    // Ballots router
    // ---------------------------------------------------------------------

    /**
     * Route ballots-related exports:
     * <ul>
     *     <li>{@code ballots <local|online> <id>}</li>
     *     <li>{@code ballots admin <local|online> <id>}</li>
     * </ul>
     *
     * @param context command context
     */
    private void executeBallotsRouter(CommandContext context, ExportMessagesConfig messages) {
        String[] args = context.args();
        if (args.length >= 2 && "admin".equalsIgnoreCase(args[1])) {
            executeBallotsAdminExport(context, messages);
        } else {
            executeBallotsExport(context, messages);
        }
    }

    // ---------------------------------------------------------------------
    // Tab completion
    // ---------------------------------------------------------------------

    @Override
    public List<String> complete(CommandContext context) {
        String[] args = context.args();

        if (args.length == 1) {
            List<String> base = context.filter(List.of("admin", "delete", "dispatch", "local", "both", "ballots"), args[0]);
            List<String> ids = context.filter(context.electionIds(), args[0]);
            return java.util.stream.Stream.concat(ids.stream(), base.stream()).distinct().toList();
        }

        if (args.length == 2) {
            if ("admin".equalsIgnoreCase(args[0])) {
                return context.filter(List.of("local", "both"), args[1]);
            }
            if ("delete".equalsIgnoreCase(args[0]) || "dispatch".equalsIgnoreCase(args[0])) {
                return List.of();
            }
            if ("ballots".equalsIgnoreCase(args[0])) {
                return context.filter(List.of("local", "online", "admin"), args[1]);
            }
            if ("local".equalsIgnoreCase(args[0]) || "both".equalsIgnoreCase(args[0])) {
                return context.filter(context.electionIds(), args[1]);
            }
            return List.of();
        }

        if (args.length == 3) {
            if ("admin".equalsIgnoreCase(args[0])) {
                return context.filter(context.electionIds(), args[2]);
            }
            if ("delete".equalsIgnoreCase(args[0])) {
                return context.filter(List.of("confirm"), args[2]);
            }
            if ("ballots".equalsIgnoreCase(args[0])) {
                if ("admin".equalsIgnoreCase(args[1])) {
                    return context.filter(List.of("local", "online"), args[2]);
                }
                return context.filter(context.electionIds(), args[2]);
            }
        }

        if (args.length == 4) {
            if ("ballots".equalsIgnoreCase(args[0]) && "admin".equalsIgnoreCase(args[1])) {
                return context.filter(context.electionIds(), args[3]);
            }
        }

        return List.of();
    }

    // ---------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------

    /**
     * Utility to safely extract a message from a {@link Throwable} without
     * leaking nulls into user-visible output.
     *
     * @param throwable error
     * @return non-null message
     */
    private String safeError(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }
}
