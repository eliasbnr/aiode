package net.robinfriedli.botify.command.commands.admin;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.robinfriedli.botify.Botify;
import net.robinfriedli.botify.command.AbstractAdminCommand;
import net.robinfriedli.botify.command.CommandContext;
import net.robinfriedli.botify.command.CommandManager;
import net.robinfriedli.botify.entities.xml.CommandContribution;
import net.robinfriedli.botify.exceptions.InvalidCommandException;

public class QuitCommand extends AbstractAdminCommand {

    public QuitCommand(CommandContribution commandContribution, CommandContext context, CommandManager commandManager, String commandString, boolean requiresInput, String identifier, String description, Category category) {
        super(commandContribution, context, commandManager, commandString, requiresInput, identifier, description, category);
    }

    @Override
    public void runAdmin() {
        StringBuilder confirmationMessageBuilder = new StringBuilder("Do you really want to stop the bot?");
        if (!getCommandInput().isBlank()) {
            confirmationMessageBuilder.append(" Reason: '").append(getCommandInput()).append("'");
        }
        askConfirmation(confirmationMessageBuilder.toString());
    }

    @Override
    public void onSuccess() {
    }

    @Override
    public void withUserResponse(Object chosenOption) {
        if (chosenOption instanceof Collection) {
            throw new InvalidCommandException("Expected a single selection");
        }

        if ((boolean) chosenOption) {
            doQuit();
        }
    }

    private void doQuit() {
        Botify.shutdownListeners();

        try {
            List<CompletableFuture<Message>> futureMessages;
            if (!argumentSet("silent")) {
                EmbedBuilder embedBuilder = new EmbedBuilder();
                embedBuilder.setTitle("Scheduled shutdown");
                embedBuilder.setDescription("The bot is scheduled to shut down after completing queued actions. No commands will be accepted until then.");
                if (!getCommandInput().isBlank()) {
                    embedBuilder.addField("Reason", getCommandInput(), false);
                }
                futureMessages = sendToActiveGuilds(embedBuilder.build());
            } else {
                futureMessages = null;
            }

            // runs in separate thread to avoid deadlock when waiting for commands to finish
            Thread shutdownThread = new Thread(() -> Botify.shutdown(
                getArgumentValue("await", Integer.class, 60) * 1000,
                futureMessages
            ));
            shutdownThread.setName("Shutdown thread");
            shutdownThread.start();
        } catch (Throwable e) {
            Botify.registerListeners();
            throw e;
        }
    }

}
