package com.vegazsdev.bobobot.commands.gsi;

import com.vegazsdev.bobobot.TelegramBot;
import com.vegazsdev.bobobot.core.command.Command;
import com.vegazsdev.bobobot.core.gsi.GSICmdObj;
import com.vegazsdev.bobobot.core.gsi.SourceForgeUpload;
import com.vegazsdev.bobobot.db.PrefObj;
import com.vegazsdev.bobobot.utils.Config;
import com.vegazsdev.bobobot.utils.FileTools;
import com.vegazsdev.bobobot.utils.JSONs;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Main command of the bot specialized in making GSI (Erfan tool).
 * <p>
 * This class consists of doing GSI using the Erfan Abdi tool, named ErfanGSIs.
 * <p>
 * Some methods:
 * <ul>
 *     <li>{@link #isCommandValid(Update)}</li>
 *     <li>{@link #try2AvoidCodeInjection(String)}</li>
 *     <li>{@link #isGSIValid(String)}</li>
 *     <li>{@link #createGSI(GSICmdObj, TelegramBot)}</li>
 *     <li>{@link #userHasPortPermissions(String)}</li>
 *     <li>{@link #getModelOfOutput()}</li>
 *     <li>{@link #addPortPerm(String)}</li>
 *
 * </ul>
 */
@SuppressWarnings({"SpellCheckingInspection", "unused"})
public class ErfanGSIs extends Command {

    /**
     * Logger: To send warning, info & errors to terminal.
     */
    private static final Logger logger = LoggerFactory.getLogger(ErfanGSIs.class);

    /**
     * Main variables to GSI process.
     */
    private static final ArrayList<GSICmdObj> queue = new ArrayList<>();
    private static boolean isPorting = false;
    private final String toolPath = "ErfanGSIs/";

    /**
     * Get supported versions from ErfanGSIs tool.
     */
    private final File[] supportedGSIs9 = new File(toolPath + "roms/9").listFiles(File::isDirectory);
    private final File[] supportedGSIs10 = new File(toolPath + "roms/10").listFiles(File::isDirectory);
    private final File[] supportedGSIs11 = new File(toolPath + "roms/11").listFiles(File::isDirectory);
    private final File[] supportedGSIs12 = new File(toolPath + "roms/S").listFiles(File::isDirectory);

    /**
     * Some workarounds.
     */
    private String messageError = "";
    private String infoGSI = "";
    private String noticeGSI = "";

    public ErfanGSIs() {
        super("jurl2gsi");
    }

    private static String[] listFilesForFolder(final File folder) {
        StringBuilder paths = new StringBuilder();
        for (final File fileEntry : Objects.requireNonNull(folder.listFiles())) {
            if (fileEntry.isDirectory()) {
                listFilesForFolder(fileEntry);
            } else {
                if (fileEntry.getName().contains(".img")) {
                    paths.append(fileEntry.getAbsolutePath()).append("\n");
                }
            }
        }
        return paths.toString().split("\n");
    }

    @Override
    public void botReply(Update update, TelegramBot bot, PrefObj prefs) {
        String[] msgComparableRaw = update.getMessage().getText().split(" ");
        if (update.getMessage().getText().contains(" ")) {
            switch (msgComparableRaw[1]) {
                case "allowuser" -> {
                    if (update.getMessage().getReplyToMessage() != null) {
                        String userid = update.getMessage().getReplyToMessage().getFrom().getId().toString();
                        if (addPortPerm(userid)) {
                            bot.sendReply(prefs.getString("egsi_allowed").replace("%1", userid), update);
                        }
                    } else {
                        bot.sendReply(prefs.getString("egsi_allow_by_reply").replace("%1", prefs.getHotkey())
                                .replace("%2", this.getAlias()), update);
                    }
                }
                case "queue" -> {
                    if (!queue.isEmpty()) {
                        StringBuilder v = new StringBuilder();
                        for (int i = 0; i < queue.size(); i++) {
                            v.append("#").append(i + 1).append(": ").append(queue.get(i).getGsi()).append("\n");
                        }
                        bot.sendReply(prefs.getString("egsi_current_queue")
                                .replace("%2", v.toString())
                                .replace("%1", String.valueOf(queue.size())), update);
                    } else {
                        bot.sendReply(prefs.getString("egsi_no_ports_queue"), update);
                    }
                }
                case "cancel" -> {
                    if (isPorting) {
                        ProcessBuilder pb;
                        pb = new ProcessBuilder("/bin/bash", "-c", "kill -TERM -- -$(ps ax | grep url2GSI.sh | grep -v grep | awk '{print $1;}')");
                        try {
                            pb.start();
                        } catch (IOException ignored) {}

                        if (FileTools.checkIfFolderExists(toolPath + "output")) {
                            if (FileTools.deleteFolder(toolPath + "output")) {
                                logger.info("Output folder deleted");
                            }
                        }
                    } else {
                        bot.sendReply(prefs.getString("egsi_no_ports_queue"), update);
                    }
                }
                case "list", "roms", "gsis" -> sendSupportedROMs(update, bot, prefs);
                default -> {
                    messageError = prefs.getString("egsi_fail_to_build_gsi");
                    if (userHasPortPermissions(update.getMessage().getFrom().getId().toString())) {
                        if (!FileTools.checkIfFolderExists("ErfanGSIs")) {
                            bot.sendReply(prefs.getString("egsi_dont_exists_tool_folder"), update);
                        } else {
                            GSICmdObj gsiCommand = isCommandValid(update);
                            if (gsiCommand != null) {
                                boolean isGSITypeValid = isGSIValid(gsiCommand.getGsi());
                                if (isGSITypeValid) {
                                    if (!isPorting) {
                                        isPorting = true;
                                        createGSI(gsiCommand, bot);
                                        while (queue.size() != 0) {
                                            GSICmdObj portNow = queue.get(0);
                                            queue.remove(0);
                                            createGSI(portNow, bot);
                                        }
                                        isPorting = false;
                                    } else {
                                        queue.add(gsiCommand);
                                        bot.sendReply(prefs.getString("egsi_added_to_queue"), update);
                                    }
                                } else {
                                    sendSupportedROMs(update, bot, prefs);
                                }
                            }
                        }
                    }
                }
            }
        } else {
            bot.sendReply(prefs.getString("bad_usage"), update);
        }
    }

    /**
     * Avoid shell usage on jurl2gsi command.
     */
    private String try2AvoidCodeInjection(String parameters) {
        try {
            parameters = parameters.replace("&", "")
                    .replace("\\", "").replace(";", "").replace("<", "")
                    .replace(">", "").replace("|", "");
        } catch (Exception e) {
            return parameters;
        }
        return parameters;
    }

    /**
     * Check if the args passed to jurl2gsi command is valid.
     */
    private GSICmdObj isCommandValid(Update update) {
        GSICmdObj gsiCmdObj = new GSICmdObj();
        String[] msgComparableRaw = update.getMessage().getText().split(" ");
        String msg = update.getMessage().getText().replace(Config.getDefConfig("bot-hotkey") + this.getAlias() + " ", "");
        String url, gsi, param;

        if (msgComparableRaw.length >= 3) {
            try {
                url = msg.split(" ")[1];
                gsiCmdObj.setUrl(url);
                gsi = msg.split(" ")[2];
                gsiCmdObj.setGsi(gsi);
                param = msg.replace(url + " ", "").replace(gsi, "").trim();
                param = try2AvoidCodeInjection(param);

                if (param.contains("-nv")) {
                    noticeGSI = "<b>Notice</b>\nThis GSI requires the vendor to have the same version of the system, check <a href=\"https://t.me/TrebleExperience_chat/10308\">this</a>\n\n";
                }

                gsiCmdObj.setParam(param);
                gsiCmdObj.setUpdate(update);
                return gsiCmdObj;
            } catch (Exception e) {
                logger.error(e.getMessage());
                return null;
            }
        } else {
            return null;
        }
    }

    /**
     * Check if the GSI is valid.
     * It checks if the tool is updated (if has R/S support), check if the ROM exists too.
     */
    private boolean isGSIValid(String gsi) {
        File[] supportedGSIsPandQ = ArrayUtils.addAll(supportedGSIs9, supportedGSIs10);
        File[] supportedGSIsRandS = ArrayUtils.addAll(supportedGSIs11, supportedGSIs12);

        if (supportedGSIsPandQ == null || supportedGSIsRandS == null) return false;

        boolean canRunYet = true;

        try {
            String gsi2 = null;

            if (gsi.contains(":")) {
                gsi2 = gsi.split(":")[0];
            }

            for (File supportedGSI : Objects.requireNonNull(supportedGSIsPandQ)) {
                canRunYet = false;
                if (Objects.requireNonNullElse(gsi2, gsi).equals(supportedGSI.getName())) return true;
            }

            if (canRunYet) {
                for (File supportedGSI : Objects.requireNonNull(supportedGSIsRandS)) {
                    if (Objects.requireNonNullElse(gsi2, gsi).equals(supportedGSI.getName())) return true;
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
            return false;
        }
        return false;
    }

    /**
     * Avoid users abuse, only users with port permission can use jurl2gsi command.
     */
    private boolean userHasPortPermissions(String idAsString) {
        if (Objects.equals(Config.getDefConfig("bot-master"), idAsString)) {
            return true;
        }
        String portConfigFile = "configs/allowed2port.json";
        return Arrays.asList(Objects.requireNonNull(JSONs.getArrayFromJSON(portConfigFile)).toArray()).contains(idAsString);
    }

    /**
     * Get model/codename of the device.
     */
    private String getModelOfOutput() {
        StringBuilder fullLogs = new StringBuilder();

        InputStream inputStream = null;
        InputStreamReader inputStreamReader = null;
        BufferedReader bufferedReader = null;

        try {
            ProcessBuilder processBuilder;
            processBuilder = new ProcessBuilder("/bin/bash", "-c",
                    "grep -oP \"(?<=^Model: ).*\" -hs \"$(pwd)\"/ErfanGSIs/output/*txt | head -1"
            );
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            inputStream = process.getInputStream();
            inputStreamReader = new InputStreamReader(inputStream);
            bufferedReader = new BufferedReader(inputStreamReader);

            String line;

            /*
             * Get codename
             */
            while ((line = bufferedReader.readLine()) != null) {
                if (line.length() < 1)
                    line = "Generic";
                else if (line.toLowerCase().contains("x00qd"))
                    line = "Asus Zenfone 5";
                else if (line.toLowerCase().contains("qssi"))
                    line = "Qualcomm Single System Image";
                else if (line.toLowerCase().contains("miatoll"))
                    line = "MiAtoll";
                else if (line.toLowerCase().contains("surya"))
                    line = "Poco X3";
                else if (line.toLowerCase().contains("lavender"))
                    line = "Redmi Note 7";
                else if (line.toLowerCase().contains("ginkgo"))
                    line = "Redmi Note 8";
                else if (line.toLowerCase().contains("raphael"))
                    line = "Mi 9T Pro";
                else if (line.toLowerCase().contains("mainline"))
                    line = "AOSP/Pixel (Mainline) Device";
                else if (line.toLowerCase().contains("sm6250"))
                    line = "Atoll device";
                else if (line.toLowerCase().contains("msi"))
                    line = "Motorola System Image";
                else if (line.toLowerCase().contains("mssi"))
                    line = "MIUI Single System Image";
                else if (line.toLowerCase().contains("a30"))
                    line = "Samsung Galaxy A30";
                else if (line.toLowerCase().contains("a20"))
                    line = "Samsung Galaxy A20";
                else if (line.toLowerCase().contains("a10"))
                    line = "Samsung Galaxy A10";
                else if (line.equals(" "))
                    line = "Generic";

                fullLogs.append(line);
            }

            /*
             * First check
             */
            String stringToBeCheked = fullLogs.toString().toLowerCase();
            boolean testPass = false;

            char[] characterSearch = {
                    'q', 'w', 'e', 'r', 't', 'y', 'u',
                    'i', 'o', 'p', 'a', 's', 'd', 'f',
                    'g', 'h', 'j', 'k', 'l', 'z', 'x',
                    'c', 'v', 'b', 'n', 'm',
            };

            for (int i = 0; i < stringToBeCheked.length(); i++) {
                char character = stringToBeCheked.charAt(i);
                for (char search : characterSearch) {
                    if (search == character) {
                        testPass = true;
                        break;
                    }
                }
            }

            /*
             * Second check
             */
            if (!testPass) return "Generic";
            return String.valueOf(fullLogs);
        } catch (Exception e) {
            logger.error(e.getMessage());
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ioException) {
                    logger.error(ioException.getMessage(), ioException);
                }
            }

            if (inputStreamReader != null) {
                try {
                    inputStreamReader.close();
                } catch (IOException ioException) {
                    logger.error(ioException.getMessage(), ioException);
                }
            }

            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException ioException) {
                    logger.error(ioException.getMessage(), ioException);
                }
            }
        }
        return "Generic";
    }

    /**
     * Create a GSI with one method.
     */
    private void createGSI(GSICmdObj gsiCmdObj, TelegramBot bot) {
        /*
         * Variables to bash
         */
        ProcessBuilder pb;
        InputStream inputStream = null;
        InputStreamReader inputStreamReader = null;
        BufferedReader bufferedReader = null;

        /*
         * Pre-final GSI process variables
         */
        boolean success = false;
        Update update = gsiCmdObj.getUpdate();
        StringBuilder fullLogs = new StringBuilder();
        String builder = update.getMessage().getFrom().getFirstName();
        Long builderID = update.getMessage().getFrom().getId();

        /*
         * Start the GSI process
         */
        pb = new ProcessBuilder("/bin/bash", "-c",
                "cd " + toolPath + " ; ./url2GSI.sh '" + gsiCmdObj.getUrl() + "' " + gsiCmdObj.getGsi() + " " + gsiCmdObj.getParam()
        );
        fullLogs.append("<code>-> Starting process...</code>");

        /*
         * Send the message, it's GSI time!
         */
        int id = bot.sendReply(fullLogs.toString(), update);

        /*
         * GSI build process
         */
        try {
            /*
             * Start process
             */
            pb.redirectErrorStream(true);
            Process process = pb.start();

            /*
             * Prepare in/output log
             */
            inputStream = process.getInputStream();
            inputStreamReader = new InputStreamReader(inputStream);
            bufferedReader = new BufferedReader(inputStreamReader);

            /*
             * Some variables (to get buffer output using readLine())
             */
            String line;

            /*
             * Avoid aria2 logs
             */
            boolean weDontNeedAria2Logs = true;

            while ((line = bufferedReader.readLine()) != null) {
                line = "<code>" + line + "</code>";
                if (line.contains("Downloading firmware to:")) {
                    weDontNeedAria2Logs = false;
                    fullLogs.append("\n").append(line);
                    bot.editMessage(fullLogs.toString(), update, id);
                }

                if (line.contains("Create Temp and out dir")) {
                    weDontNeedAria2Logs = true;
                }

                if (weDontNeedAria2Logs) {
                    fullLogs.append("\n").append(line);
                    bot.editMessage(fullLogs.toString(), update, id);
                    if (line.contains("GSI done!")) {
                        success = true;
                    }
                }
            }

            /*
             * If the GSI got true boolean, it will create gzip, upload, prepare message and send it to channel/group
             */
            if (success) {
                fullLogs.append("\n").append("<code>Creating gzip...</code>");
                bot.editMessage(fullLogs.toString(), update, id);

                /*
                 * Get files inside ErfanGSIs/output
                 */
                String[] gzipFiles = listFilesForFolder(new File("ErfanGSIs" + "/output"));

                /*
                 * Gzip the files
                 */
                for (String gzipFile : gzipFiles) {
                    new FileTools().gzipFile(gzipFile, gzipFile + ".gz");
                }

                /*
                 * Create ArrayList to save A/B, Aonly & vendorOverlays files
                 */
                ArrayList<String> arr = new ArrayList<>();

                /*
                 * A/B, Aonly & vendorOverlay Atomic variables
                 */
                AtomicReference<String> aonly = new AtomicReference<>("");
                AtomicReference<String> ab = new AtomicReference<>("");
                AtomicReference<String> vendorOverlays = new AtomicReference<>("");
                AtomicReference<String> odmOverlays = new AtomicReference<>("");

                /*
                 * Try to get files inside ErfanGSIs/output and set into correct variable (ex: A/B image to A/B variable)
                 */
                try (Stream<Path> paths = Files.walk(Paths.get("ErfanGSIs/output/"))) {
                    paths
                            .filter(Files::isRegularFile)
                            .forEach(fileName -> {
                                if (fileName.toString().endsWith(".gz") || fileName.toString().endsWith("System-Tree.txt")) {
                                    arr.add(fileName.toString());
                                    if (fileName.toString().contains("Aonly")) {
                                        aonly.set(FilenameUtils.getBaseName(fileName.toString()) + "." + FilenameUtils.getExtension(fileName.toString()));
                                    } else if (fileName.toString().contains("AB")) {
                                        ab.set(FilenameUtils.getBaseName(fileName.toString()) + "." + FilenameUtils.getExtension(fileName.toString()));
                                    } else if (fileName.toString().contains("VendorOverlays")) {
                                        vendorOverlays.set(FilenameUtils.getBaseName(fileName.toString()) + "." + FilenameUtils.getExtension(fileName.toString()));
                                    } else if (fileName.toString().contains("ODMOverlays")) {
                                        odmOverlays.set(FilenameUtils.getBaseName(fileName.toString()) + "." + FilenameUtils.getExtension(fileName.toString()));
                                    }
                                }
                                if (fileName.toString().contains(".txt") && !fileName.toString().contains("System-Tree")) {
                                    infoGSI = fileName.toString();
                                }
                            });
                } catch (IOException e) {
                    logger.error(e.getMessage());
                }

                /*
                 * Now say the bot will upload files to SourceForge
                 */
                fullLogs.append("\n").append("<code>Uploading to SF...</code>");
                bot.editMessage(fullLogs.toString(), update, id);

                /*
                 * SourceForge upload time
                 */
                String re = new SourceForgeUpload().uploadGsi(arr, gsiCmdObj.getGsi());
                re = re + "/";

                /*
                 * Check the GSI name has special name, like this:
                 * !jurl2gsi <url link> Generic:StatiXOS-Nuclear
                 * The name of this ROM is 'StatiXOS Nuclear' (without quotes), the '-' (char) will be the replacement char, to be used as a space
                 */
                if (gsiCmdObj.getGsi().contains(":")) {
                    gsiCmdObj.setGsi(gsiCmdObj.getGsi().split(":")[1]);
                    gsiCmdObj.setGsi(gsiCmdObj.getGsi().replace("-", " "));
                }

                /*
                 * Prepare GSI message
                 */
                SendMessage sendMessage = new SendMessage();
                sendMessage.setDisableWebPagePreview(true);
                sendMessage.enableHtml(true);

                /*
                 * Prepare InlineKeyboardButton
                 */
                InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

                if (!aonly.toString().trim().equals("")) {
                    List<InlineKeyboardButton> rowInline2 = new ArrayList<>();
                    InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                    inlineKeyboardButton.setText("Aonly Download");
                    inlineKeyboardButton.setUrl("https://sourceforge.net/projects/gsis137/files/GSI/" + re + aonly);
                    rowInline2.add(inlineKeyboardButton);
                    rowsInline.add(rowInline2);
                }

                if (!ab.toString().trim().equals("")) {
                    List<InlineKeyboardButton> rowInline = new ArrayList<>();
                    InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                    inlineKeyboardButton.setText("A/B Download");
                    inlineKeyboardButton.setUrl("https://sourceforge.net/projects/gsis137/files/GSI/" + re + ab);
                    rowInline.add(inlineKeyboardButton);
                    rowsInline.add(rowInline);
                }

                if (!vendorOverlays.toString().trim().equals("")) {
                    List<InlineKeyboardButton> rowInline = new ArrayList<>();
                    InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                    inlineKeyboardButton.setText("Vendor Overlays Download");
                    inlineKeyboardButton.setUrl("https://sourceforge.net/projects/gsis137/files/GSI/" + re + vendorOverlays);
                    rowInline.add(inlineKeyboardButton);
                    rowsInline.add(rowInline);
                }

                if (!odmOverlays.toString().trim().equals("")) {
                    List<InlineKeyboardButton> rowInline = new ArrayList<>();
                    InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                    inlineKeyboardButton.setText("ODM Overlays Download");
                    inlineKeyboardButton.setUrl("https://sourceforge.net/projects/gsis137/files/GSI/" + re + odmOverlays);
                    rowInline.add(inlineKeyboardButton);
                    rowsInline.add(rowInline);
                }

                /*
                 * Finish InlineKeyboardButton setup
                 */
                markupInline.setKeyboard(rowsInline);
                sendMessage.setReplyMarkup(markupInline);

                /*
                 * Info of GSI image
                 */
                String descGSI = "" + new FileTools().readFile(infoGSI).trim();

                /*
                 * Prepare message id
                 */
                int idGSI;

                /*
                 * Send GSI message
                 */
                sendMessage.setText("<b>" + gsiCmdObj.getGsi() + " GSI</b>"
                        + "\n<b>From</b> " + getModelOfOutput()
                        + "\n\n<b>Information</b>\n<code>" + descGSI
                        + "</code>\n\n"
                        + noticeGSI
                        + "<b>✵ RK137 GSI ✵</b>" + "\n"
                        + "<a href=\"https://t.me/rk137gsi\">GSI Channel</a> | <a href=\"https://t.me/oemgsichannel\">Port Channel</a> | <a href=\"https://sourceforge.net/projects/gsis137/files/GSI\">SF Folder</a>"
                        + "\n\n<b>Credits :</b>" + "\n"
                        + "<a href=\"https://github.com/Erfanoabdi\">Erfan Abdi</a>" + " | "
                        + "<a href=\"https://github.com/TrebleExperience/Bot3\">Bo³+t</a>" + " | "
                        + "<a href=\"https://t.me/Velosh\">Velosh</a>"                    
                );
                sendMessage.setChatId(Objects.requireNonNull(SourceForgeSetup.getSfConf("bot-announcement-id")));
                idGSI = bot.sendMessageAsyncBase(sendMessage, update);

                fullLogs.append("\n").append("Finished!");
                bot.editMessage(fullLogs.toString(), update, id);

                /*
                 * Reply kthx
                 */
                if (idGSI != 0) bot.sendReply("Done! Here the <a href=\"" + "https://t.me/" + Config.getDefConfig("publicChannel")  + "/" + idGSI + "\">link</a> post", update);

                /*
                 * Delete output/input folder with two codes (The first seems not worked so to make sure, use other code for it)
                 */
                FileUtils.deleteDirectory(new File(toolPath + "output"));
                if (FileTools.checkIfFolderExists(toolPath + "output")) {
                    if (FileTools.deleteFolder(toolPath + "output")) {
                        logger.info("Output folder deleted");
                    }
                }

                FileUtils.deleteDirectory(new File(toolPath + "input"));
                if (FileTools.checkIfFolderExists(toolPath + "input")) {
                    if (FileTools.deleteFolder(toolPath + "input")) {
                        logger.info("Input folder deleted");
                    }
                }

                /*
                 * Cleanup variables
                 */
                ab.set(null);
                aonly.set(null);
                vendorOverlays.set(null);
                odmOverlays.set(null);
                infoGSI = null;
                arr.clear();
                gsiCmdObj.clean();
            } else {
                bot.sendReply(messageError, update);
            }
        } catch (Exception ex) {
            bot.sendReply(messageError, update);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ioException) {
                    logger.error(ioException.getMessage());
                }
            }

            if (inputStreamReader != null) {
                try {
                    inputStreamReader.close();
                } catch (IOException ioException) {
                    logger.error(ioException.getMessage());
                }
            }

            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException ioException) {
                    logger.error(ioException.getMessage());
                }
            }
        }
    }

    /**
     * Add port permission using user id.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean addPortPerm(String id) {
        try {
            if (FileTools.checkFileExistsCurPath("configs/allowed2port.json")) {
                ArrayList arrayList = JSONs.getArrayFromJSON("configs/allowed2port.json");
                if (arrayList != null) {
                    arrayList.add(id);
                }
                JSONs.writeArrayToJSON(arrayList, "configs/allowed2port.json");
            } else {
                ArrayList<String> arrayList = new ArrayList<>();
                arrayList.add(id);
                JSONs.writeArrayToJSON(arrayList, "configs/allowed2port.json");
            }
            return true;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return false;
        }
    }

    /**
     * Common message for list/jurl2gsi args
     */
    public void sendSupportedROMs(Update update, TelegramBot bot, PrefObj prefs) {
        File[] supportedGSIsPandQ = ArrayUtils.addAll(supportedGSIs9, supportedGSIs10);
        File[] supportedGSIsRandS = ArrayUtils.addAll(supportedGSIs11, supportedGSIs12);

        if (supportedGSIsPandQ != null && supportedGSIsRandS != null) {
            bot.sendReply(prefs.getString("egsi_supported_types")
                    .replace("%1",
                            Arrays.toString(supportedGSIs9).replace(toolPath + "roms/9/", "")
                                    .replace("[", "")
                                    .replace("]", ""))
                    .replace("%2",
                            Arrays.toString(supportedGSIs10).replace(toolPath + "roms/10/", "")
                                    .replace("[", "")
                                    .replace("]", ""))
                    .replace("%3",
                            Arrays.toString(supportedGSIs11).replace(toolPath + "roms/11/", "")
                                    .replace("[", "")
                                    .replace("]", ""))
                    .replace("%4",
                            Arrays.toString(supportedGSIs12).replace(toolPath + "roms/S/", "")
                                    .replace("[", "")
                                    .replace("]", "")), update);
        } else {
            bot.sendReply(prefs.getString("egsi_something_is_wrong"), update);
        }
    }
}
