package com.vegazsdev.bobobot;

import com.google.common.reflect.ClassPath;
import com.vegazsdev.bobobot.commands.owner.Chat2Shell;
import com.vegazsdev.bobobot.core.bot.Bot;
import com.vegazsdev.bobobot.core.bot.BuildInfo;
import com.vegazsdev.bobobot.core.command.Command;
import com.vegazsdev.bobobot.core.command.annotations.DisableCommand;
import com.vegazsdev.bobobot.core.shell.ShellStatus;
import com.vegazsdev.bobobot.db.DbThings;
import com.vegazsdev.bobobot.db.PrefObj;
import com.vegazsdev.bobobot.exception.BotTokenException;
import com.vegazsdev.bobobot.utils.Config;
import com.vegazsdev.bobobot.utils.FileTools;
import com.vegazsdev.bobobot.utils.XMLs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

public class Main {

    /**
     * Logger: To send warning, info & errors to terminal.
     */
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * That variable is used to get core strings.
     */
    public static String DEF_CORE_STRINGS_XML = "core-strings.xml";

    /**
     * That variable is for shell command.
     */
    public static ShellStatus shellStatus;

    @SuppressWarnings({"SpellCheckingInspection", "UnstableApiUsage", "rawtypes"})
    public static void main(String[] args) {
        /*
         * Start-up of all
         */
        logger.info(XMLs.getFromStringsXML(DEF_CORE_STRINGS_XML, "bot_init"));

        /*
         * Check if exists props file and other things
         */
        if (!FileTools.checkFileExistsCurPath("configs/" + XMLs.getFromStringsXML(DEF_CORE_STRINGS_XML, "config_file"))) {
            logger.info(XMLs.getFromStringsXML(DEF_CORE_STRINGS_XML, "config_file_not_found"));
            new Config().createDefConfig();
            logger.error(XMLs.getFromStringsXML(DEF_CORE_STRINGS_XML, "config_file_info"));
            if (!FileTools.checkFileExistsCurPath("configs/allowed2port.json"))
                Chat2Shell.runBash("echo \"[]\" >> configs/allowed2port.json");
            System.exit(0);
        }

        /*
         * Create ArrayList (Class) to save classes, we'll use it to botsApi.registerBot()
         */
        ArrayList<Class> commandClasses = new ArrayList<>();

        /*
         * Create ClassLoader to get class name, method and other things
         */
        final ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try {
            for (final ClassPath.ClassInfo info : ClassPath.from(loader).getTopLevelClasses()) {
                if (info.getName().startsWith("com.vegazsdev.bobobot.commands")) {
                    /*
                     * Prepare clazz var (To get class (info var of for{}) info)
                     */
                    final Class<?> clazz = info.load();

                    /*
                     * Check if it is a valid command
                     */
                    if (clazz.getGenericSuperclass().toString().equals(String.valueOf(Command.class))) {
                        /*
                         * If valid: Check if has DisableCommand annotation, if has, say: failed to initialize
                         * Else, add the class into commandClasses
                         */
                        if (clazz.isAnnotationPresent(DisableCommand.class)) {
                            logger.warn(Objects.requireNonNull(
                                    XMLs.getFromStringsXML(DEF_CORE_STRINGS_XML, "cc_failed_to_init"))
                                    .replace("%1", clazz.getSimpleName()));
                        } else {
                            /*
                             * If valid, add the validated class to commandClasses
                             */
                            try {
                                commandClasses.add(clazz);
                                logger.info(Objects.requireNonNull(
                                        XMLs.getFromStringsXML(DEF_CORE_STRINGS_XML, "cc_init_cmd"))
                                        .replace("%1", clazz.getSimpleName()));
                            } catch (Exception e) {
                                logger.error(e.getMessage());
                            }
                        }
                    } else {
                        /*
                         * If the command does not have a superclass of the command (core) class, give a warning and ignore
                         */
                        logger.warn(Objects.requireNonNull(
                                XMLs.getFromStringsXML(DEF_CORE_STRINGS_XML, "cc_not_valid_command"))
                                .replace("%1", clazz.getSimpleName()));
                    }
                }
            }
        } catch (IOException e) {
            logger.error(e.getMessage());
        }

        /*
         * Check if the props is ok or no
         */
        if (((Config.getDefConfig("bot-token") != null && Objects.requireNonNull(Config.getDefConfig("bot-token")).contains(" "))
                || (Config.getDefConfig("bot-username") != null && Objects.requireNonNull(Config.getDefConfig("bot-username")).contains(" ")))
                || Objects.requireNonNull(Config.getDefConfig("bot-master")).contains(" ") || (Objects.requireNonNull(Config.getDefConfig("publicChannel")).contains(" "))) {
            logger.error(XMLs.getFromStringsXML(DEF_CORE_STRINGS_XML, "config_file_info"));
            System.exit(0);
        }

        /*
         * Create BuildInfo object, it is used in TelegramBots class to About class, just for info (you can remove it fine)
         */
        BuildInfo buildInfo = new BuildInfo(true);

        /*
         * Create ShellStatus object, is necesary to avoid multiples instances when using Shell command
         */
        shellStatus = new ShellStatus();
        shellStatus.unlockStatus();

        /*
         * Create Bot object
         */
        Bot bot = null;
        try {
            bot = new Bot(
                    Objects.requireNonNull(Config.getDefConfig("bot-token")),
                    Config.getDefConfig("bot-username"),
                    buildInfo.getVersion());
        } catch (BotTokenException e) {
            logger.error(e.getMessage());
            System.exit(1);
        }

        /*
         * Create database if don't exists
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

        /*
         * Create some checks
         * Check if the private/public chats has own its prefs in database
         */
        if ((Config.getDefConfig("privateChat") == null || Objects.equals(Config.getDefConfig("privateChat"), "")) || (Config.getDefConfig("requestChat") == null) || !Objects.requireNonNull(Config.getDefConfig("requestChat")).startsWith("-") && !Objects.requireNonNull(Config.getDefConfig("privateChat")).startsWith("-")) {
            logger.info(XMLs.getFromStringsXML("strings-en.xml", "issue_with_index_chat"));
        } else {
            /*
             * Get public & private chats and set into temp String[] var
             */
            String[] chatIDs = {
                    Config.getDefConfig("privateChat"), Config.getDefConfig("requestChat")
            };

            /*
             * Check with for{}
             */
            for (String chatID : chatIDs) {
                /*
                 * PrefObj, chatPrefs
                 */
                PrefObj chatPrefs = TelegramBot.getPrefs(Double.parseDouble(Objects.requireNonNull(chatID)));

                /*
                 * Check if the database exists for public/request chats (request command)
                 */
                if (chatPrefs == null) {
                    logger.info("There is no database for: " + chatID + ", creating one...");
                    new PrefObj(0, "strings-en.xml", "!", 1);
                }
            }
        }

        /*
         * Time to run bot
         */
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new TelegramBot(bot, commandClasses));
            logger.info(XMLs.getFromStringsXML(DEF_CORE_STRINGS_XML, "bot_started"));
        } catch (Exception e) {
            logger.error(e.toString());
        }
    }
}
