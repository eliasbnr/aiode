package net.robinfriedli.aiode.command.commands.customisation;

import com.antkorwin.xsync.XSync;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.robinfriedli.aiode.command.AbstractCommand;
import net.robinfriedli.aiode.command.CommandContext;
import net.robinfriedli.aiode.command.CommandManager;
import net.robinfriedli.aiode.entities.xml.CommandContribution;
import net.robinfriedli.aiode.exceptions.InvalidCommandException;

public class RenameCommand extends AbstractCommand {

    public static final XSync<Long> RENAME_SYNC = new XSync<>();

    private boolean couldChangeNickname;

    public RenameCommand(CommandContribution commandContribution, CommandContext commandContext, CommandManager commandManager, String commandString, boolean requiresInput, String identifier, String description, Category category) {
        super(commandContribution, commandContext, commandManager, commandString, requiresInput, identifier, description, category);
    }

    @Override
    public void doRun() {
        String name = getCommandInput();
        CommandContext context = getContext();
        Guild guild = context.getGuild();

        if (name.length() < 1 || name.length() > 32) {
            throw new InvalidCommandException("Length should be 1 - 32 characters");
        }

        RENAME_SYNC.execute(guild.getIdLong(), () -> {
            invoke(() -> context.getGuildContext().setBotName(name));
            try {
                guild.getSelfMember().modifyNickname(name).complete();
                couldChangeNickname = true;
            } catch (InsufficientPermissionException ignored) {
                couldChangeNickname = false;
            }
        });
    }

    @Override
    public void onSuccess() {
        String name = getContext().getGuildContext().getSpecification().getBotName();
        if (couldChangeNickname) {
            // notification sent by GuildPropertyInterceptor
        } else {
            sendError("I do not have permission to change my nickname, but you can still call me " + name);
        }
    }

}
