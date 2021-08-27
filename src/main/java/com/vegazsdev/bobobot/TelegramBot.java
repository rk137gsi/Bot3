package com.vegazsdev.bobobot;

import com.vegazsdev.bobobot.core.bot.Bot;
import com.vegazsdev.bobobot.core.command.CommandWithClass;
import com.vegazsdev.bobobot.db.DbThings;
import com.vegazsdev.bobobot.db.PrefObj;
import com.vegazsdev.bobobot.utils.FileTools;
import com.vegazsdev.bobobot.utils.XMLs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.LeaveChat;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutionException;

@SuppressWarnings("rawtypes")
public class TelegramBot extends TelegramLongPollingBot {

    private static final Logger logger = LoggerFactory.getLogger(TelegramBot.class);
    /*
     * Don't be angry guys, just a joke
     */
    private final String[] messages = {
            "Go back to the kitchen", "Uninstall the telegram, no one will care",
            "Have you ever wondered why you were born? There is no reason", "Go sleep",
            "Why do you use this command? Well, nobody cares", "Life is random chaos, ordered by time",
            "They got lost again, bunch of idiots!", "Breaking the rules is worse than trash, but abandoning your friends is worse than that.",
            "A chance in a million is better than no chance!", "When you love, there is a risk of hating.",
            "Have you washed the dishes yet?", "You're cringe.",
            "owo", "lmao...", "( ͡° ͜ʖ ͡°)"
    };
    private final Bot bot;
    private PrefObj chatPrefs;
    private ArrayList<Class> commandClasses;

    TelegramBot(Bot bot, ArrayList<Class> commandClasses) {
        this.bot = bot;
        this.commandClasses = commandClasses;
    }

    public TelegramBot(Bot bot) {
        this.bot = bot;
    }

    public static PrefObj getPrefs(double chatId) {
        PrefObj prefObj = DbThings.selectIntoPrefsTable(chatId);
        if (prefObj == null) {
            DbThings.insertIntoPrefsTable(chatId);
        }
        return prefObj;
    }

    @Override
    public void onUpdateReceived(Update update) {
        /*
         * Avoid hotkey problem
         */
        if (!FileTools.checkFileExistsCurPath("databases/prefs.db")) {
            DbThings.createNewDatabase("prefs.db");
            DbThings.createTable("prefs.db",
                    "CREATE TABLE IF NOT EXISTS chat_prefs ("
                            + "group_id real UNIQUE PRIMARY KEY,"
                            + "able_to_send_random_messages real DEFAULT 1,"
                            + "hotkey text DEFAULT '/',"
                            + "lang text DEFAULT 'strings-en.xml'"
                            + ");"
            );
        }

        if (update.getMessage() != null) {
            /*
             * PrefObj, chatPrefs
             */
            chatPrefs = getPrefs(Double.parseDouble(Objects.requireNonNull(update.getMessage().getChatId().toString())));

            /*
             * Check if exists that chat in our db
             */
            if (chatPrefs == null) {
                try {
                    chatPrefs = new PrefObj(0, "strings-en.xml", "!", 1);
                } catch (Exception exception) {
                    logger.error(exception.getMessage());
                }
            }

            /*
             * Create thread to run commands (it can run without thread)
             */
            new Thread(new Runnable() {
                private TelegramBot tBot;

                /*
                 * Create TelegramBot object using init()
                 */
                Runnable init(TelegramBot tBot) {
                    this.tBot = tBot;
                    return this;
                }

                /*
                 * Check conditional, command & other things
                 */
                @Override
                public void run() {
                    if (update.hasMessage() && update.getMessage().getText() != null
                            && !update.getMessage().getText().equals("")
                            && Objects.requireNonNull(XMLs.getFromStringsXML(Main.DEF_CORE_STRINGS_XML, "possible_hotkeys"))
                            .indexOf(update.getMessage().getText().charAt(0)) >= 0) {

                        String msg = update.getMessage().getText();
                        long usrId = update.getMessage().getFrom().getId();

                        /*
                         * It is ok to run and send command
                         */
                        if (chatPrefs.getHotkey() != null && msg.startsWith(Objects.requireNonNull(chatPrefs.getHotkey()))) {
                            for (CommandWithClass commandWithClass : getActiveCommandsAsCmdObject()) {
                                String adjustCommand = msg.replace(Objects.requireNonNull(chatPrefs.getHotkey()), "");

                                if (adjustCommand.contains(" ")) {
                                    adjustCommand = adjustCommand.split(" ")[0];
                                }

                                if (commandWithClass.getAlias().equals(adjustCommand)) {
                                    try {
                                        logger.info(Objects.requireNonNull(XMLs.getFromStringsXML(Main.DEF_CORE_STRINGS_XML, "command_ok"))
                                                .replace("%1", update.getMessage().getFrom().getFirstName() + " (" + usrId + ")")
                                                .replace("%2", adjustCommand));
                                        runMethod(commandWithClass.getClazz(), update, tBot, chatPrefs);
                                    } catch (Exception e) {
                                        logger.error(Objects.requireNonNull(XMLs.getFromStringsXML(Main.DEF_CORE_STRINGS_XML, "command_failure"))
                                                .replace("%1", commandWithClass.getAlias())
                                                .replace("%2", e.getMessage()), e);
                                    }
                                }
                            }
                        }
                    } else {
                        if (!(update.getMessage().getFrom().getId() == Float.parseFloat(String.valueOf(777000)))) {
                            if (chatPrefs.getAbleToSendRandomMessage() == 1) {
                                /*
                                 * Random number/XP (or lucky)
                                 */
                                Random random = new Random();
                                int low = 0, high = 15, lowLucky = 0, highLucky = 1000;
                                int randomInt = random.nextInt(high - low) + low;
                                int randomXP = random.nextInt(highLucky - lowLucky) + lowLucky;

                                if (randomInt > randomXP) {
                                    sendReply(messages[randomInt], update);
                                }
                            }
                        }
                    }
                }
            }.init(this)).start();
        }
    }

    public void sendMessageAsync(String msg, Update update) {
        /*
         * Prepare SendMessage base
         */
        SendMessage sendMessage = new SendMessage();
        sendMessage.setText(msg);
        sendMessage.setChatId(String.valueOf(update.getMessage().getChatId()));
        sendMessage.enableMarkdown(true);
        sendMessage.disableWebPagePreview();

        /*
         * Execute executeAsync() method
         */
        try {
            executeAsync(sendMessage).get().getMessageId();
        } catch (TelegramApiException | ExecutionException | InterruptedException exception) {
            logger.error(exception.getMessage() + " (CID: " + update.getMessage().getChat().getId() + " | UID: " + update.getMessage().getFrom().getId() + ")");
        }
    }

    public int sendMessageAsyncBase(SendMessage sendMessage, Update update) {
        /*
         * Execute executeAsync() method & use existent SendMessage object
         */
        try {
            return executeAsync(sendMessage).get().getMessageId();
        } catch (TelegramApiException | ExecutionException | InterruptedException exception) {
            logger.error(exception.getMessage() + " (CID: " + update.getMessage().getChat().getId() + " | UID: " + update.getMessage().getFrom().getId() + ")");
        }
        return 0;
    }

    public void deleteMessage(String chatID, Integer messageID, Update update) {
        /*
         * Prepare DeleteMessage base
         */
        DeleteMessage deleteMessage = new DeleteMessage();
        deleteMessage.setMessageId(messageID);
        deleteMessage.setChatId(chatID);

        /*
         * Execute executeAsync() method
         */
        try {
            executeAsync(deleteMessage);
        } catch (TelegramApiException telegramApiException) {
            logger.error(telegramApiException.getMessage() + " (CID: " + update.getMessage().getChat().getId() + " | UID: " + update.getMessage().getFrom().getId() + ")");
        }
    }

    public int sendReply(String msg, Update update) {
        /*
         * Prepare SendMessage base
         */
        SendMessage sendMessage = new SendMessage();
        sendMessage.setText(msg);
        sendMessage.setChatId(String.valueOf(update.getMessage().getChatId()));
        sendMessage.enableHtml(true);
        sendMessage.setReplyToMessageId(update.getMessage().getMessageId());
        sendMessage.disableWebPagePreview();

        /*
         * Execute executeAsync() method
         */
        try {
            return executeAsync(sendMessage).get().getMessageId();
        } catch (TelegramApiException | ExecutionException | InterruptedException exception) {
            logger.error(exception.getMessage() + " (CID: " + update.getMessage().getChat().getId() + " | UID: " + update.getMessage().getFrom().getId() + ")");
        }
        return 0;
    }

    public void sendReply2ID(String msg, int id, Update update) {
        /*
         * Prepare SendMessage base
         */
        SendMessage sendMessage = new SendMessage();
        sendMessage.setText(msg);
        sendMessage.setChatId(String.valueOf(update.getMessage().getChatId()));
        sendMessage.enableHtml(true);
        sendMessage.setReplyToMessageId(id);
        sendMessage.disableWebPagePreview();

        /*
         * Execute executeAsync() method
         */
        try {
            executeAsync(sendMessage).get().getMessageId();
        } catch (TelegramApiException | ExecutionException | InterruptedException exception) {
            logger.error(exception.getMessage() + " (CID: " + update.getMessage().getChat().getId() + " | UID: " + update.getMessage().getFrom().getId() + ")");
        }
    }

    public void editMessage(String msg, Update update, int id) {
        /*
         * Prepare EditMessageText base
         */
        EditMessageText editMessageText = new EditMessageText();
        editMessageText.setText(msg);
        editMessageText.setChatId(String.valueOf(update.getMessage().getChatId()));
        editMessageText.setMessageId(id);
        editMessageText.enableHtml(true);

        /*
         * Execute executeAsync() method
         */
        try {
            executeAsync(editMessageText);
        } catch (TelegramApiException telegramApiException) {
            logger.error(telegramApiException.getMessage() + " (CID: " + update.getMessage().getChat().getId() + " | UID: " + update.getMessage().getFrom().getId() + ")");
        }
    }

    public boolean isPM(String userID, String chatID) {
        return !chatID.equals(userID);
    }

    public boolean isAdmin(String userID, String chatID) {
        if (userID.equals(chatID)) {
            /*
             * https://github.com/TrebleExperience/Bot3/commit/0f31e973edecce5ea25a92a6b3b841aaae1b333c
             */
            return false;
        } else {
            try {
                /*
                 * Create GetChatMember() object to get info of the user
                 */
                GetChatMember getChatMember = new GetChatMember();
                getChatMember.setChatId(chatID);
                getChatMember.setUserId(Long.valueOf(userID));

                /*
                 * Execute GetChatMember() (to get info) using ChatMember().execute()
                 */
                ChatMember chatMember = execute(getChatMember);

                /*
                 * If executed fine, we'll be able to check status
                 */
                return switch (chatMember.getStatus()) {
                    case "administrator", "creator" -> true;
                    default -> false;
                };
            } catch (Exception exception) {
                logger.error(exception.getMessage() + " (CID: " + chatID + " | UID: " + userID + ")");
                return false;
            }
        }
    }

    public boolean leaveChat(String chatID) {
        /*
         * Prepare LeaveChat base
         */
        LeaveChat leaveChat = new LeaveChat();
        leaveChat.setChatId(chatID);

        /*
         * Execute executeAsync() method
         */
        try {
            return executeAsync(leaveChat).thenApply(response -> /* Just a workaround */ response.toString().equals("true")).get();
        } catch (TelegramApiException | ExecutionException | InterruptedException telegramApiException) {
            logger.error(telegramApiException.getMessage() + " (CID to leave: " + chatID + ")");
            return false;
        }
    }

    private void runMethod(Class aClass, Update update, TelegramBot tBot, PrefObj prefs) {
        try {
            Object instance = ((Class<?>) aClass).getDeclaredConstructor().newInstance();
            Method method = ((Class<?>) aClass).getDeclaredMethod("botReply", Update.class, TelegramBot.class, PrefObj.class);
            method.invoke(instance, update, tBot, prefs);
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
    }

    public ArrayList<CommandWithClass> getActiveCommandsAsCmdObject() {
        ArrayList<CommandWithClass> allCommandsArObj = new ArrayList<>();
        for (Class clazz : commandClasses) {
            try {
                Object instance = ((Class<?>) clazz).getDeclaredConstructor().newInstance();
                Method methodAli = ((Class<?>) clazz).getSuperclass().getDeclaredMethod("getAlias");
                String alias = (String) methodAli.invoke(instance);
                CommandWithClass c = new CommandWithClass(clazz, alias);
                allCommandsArObj.add(c);
            } catch (Exception e) {
                logger.error(e.getMessage());
            }
        }
        return allCommandsArObj;
    }

    @Override
    public String getBotUsername() {
        return bot.getUsername();
    }

    @Override
    public String getBotToken() {
        return bot.getToken();
    }

    public String getVersionID() {
        return bot.getVersionID();
    }
}
