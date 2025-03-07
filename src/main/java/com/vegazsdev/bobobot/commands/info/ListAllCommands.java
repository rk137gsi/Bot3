package com.vegazsdev.bobobot.commands.info;

import com.vegazsdev.bobobot.TelegramBot;
import com.vegazsdev.bobobot.core.command.Command;
import com.vegazsdev.bobobot.db.PrefObj;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * That class show commands of the bot.
 */
@SuppressWarnings("unused")
public class ListAllCommands extends Command {
    public ListAllCommands() {
        super("comm");
    }

    @Override
    public void botReply(Update update, TelegramBot bot, PrefObj prefs) {
        String hotkey = prefs.getHotkey();
        StringBuilder allCommandsAsString = new StringBuilder();
        for (int i = 0; i < bot.getActiveCommandsAsCmdObject().size(); i++) {
            allCommandsAsString.append("<b>").append("(").append(i).append(") - ").append("</b>")
                    .append(hotkey).append(bot.getActiveCommandsAsCmdObject().get(i).getAlias()).append("\n\n");
        }
        bot.sendReply(allCommandsAsString.toString(), update);
    }
}