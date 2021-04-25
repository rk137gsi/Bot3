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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@SuppressWarnings({"SpellCheckingInspection", "unused"})
public class ErfanGSIs extends Command {

    private static final Logger logger = LoggerFactory.getLogger(ErfanGSIs.class);
    private static final ArrayList<GSICmdObj> queue = new ArrayList<>();
    private static boolean isPorting = false;
    private final String toolPath = "ErfanGSIs/";

    private final File[] supportedGSIs9 = new File(toolPath + "roms/9").listFiles(File::isDirectory);
    private final File[] supportedGSIs10 = new File(toolPath + "roms/10").listFiles(File::isDirectory);
    private final File[] supportedGSIs11 = new File(toolPath + "roms/11").listFiles(File::isDirectory);

    private String infoGSI = "";

    public ErfanGSIs() {
        super("jurl2gsi", "Can port gsi");
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

        String msg = update.getMessage().getText();
        String idAsString = update.getMessage().getFrom().getId().toString();

        if (msg.contains(" allowuser") && Objects.equals(Config.getDefConfig("bot-master"), idAsString)) {
            if (update.getMessage().getReplyToMessage() != null) {
                String userid = update.getMessage().getReplyToMessage().getFrom().getId().toString();
                if (addPortPerm(userid)) {
                    bot.sendReply(prefs.getString("egsi_allowed").replace("%1", userid), update);
                }
            } else if (msg.contains(" ")) {
                String userid = msg.split(" ")[2];
                if (userid != null && userid.trim().equals("") && addPortPerm(userid)) {
                    bot.sendReply(prefs.getString("egsi_allowed").replace("%1", userid), update);
                }
            } else {
                bot.sendReply(prefs.getString("egsi_allow_by_reply").replace("%1", prefs.getHotkey())
                        .replace("%2", this.getAlias()), update);
            }
        } else if (msg.contains(" queue")) {
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
        } else if (msg.contains(" cancel")) {

            // cancel by now, maybe work, not tested
            // will exit only on when porting is "active" (when url2gsi.sh is running)
            // after that when port already already finished (eg. uploading, zipping)
            // so this cancel needs more things to fully work

            ProcessBuilder pb;
            pb = new ProcessBuilder("/bin/bash", "-c", "kill -TERM -- -$(ps ax | grep url2GSI.sh | grep -v grep | awk '{print $1;}')");
            try {
                pb.start();
            } catch (IOException ex) {
                //Logger.getLogger(BotTelegram.class.getName()).log(Level.SEVERE, null, ex);
            }
        } else {

            boolean userHasPermissions = userHasPortPermissions(idAsString);

            if (userHasPermissions) {
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
                                                .replace("]", "")), update);
                    }
                }


            } else {
                bot.sendReply("No Permissions", update);
            }

        }
    }

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

    private GSICmdObj isCommandValid(Update update) {

        GSICmdObj gsiCmdObj = new GSICmdObj();
        String msg = update.getMessage().getText().replace(Config.getDefConfig("bot-hotkey") + this.getAlias() + " ", "");
        String url;
        String gsi;
        String param;

        try {
            url = msg.split(" ")[1];
            gsiCmdObj.setUrl(url);
            gsi = msg.split(" ")[2];
            gsiCmdObj.setGsi(gsi);
            param = msg.replace(url + " ", "").replace(gsi, "").trim();
            param = try2AvoidCodeInjection(param);
            gsiCmdObj.setParam(param);
            gsiCmdObj.setUpdate(update);
            return gsiCmdObj;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    private boolean isGSIValid(String gsi) {
        return true;
    }

    private boolean userHasPortPermissions(String idAsString) {
        if (Objects.equals(Config.getDefConfig("bot-master"), idAsString)) {
            return true;
        }
        String portConfigFile = "configs/allowed2port.json";
        return Arrays.asList(Objects.requireNonNull(JSONs.getArrayFromJSON(portConfigFile)).toArray()).contains(idAsString);
    }

    private void createGSI(GSICmdObj gsiCmdObj, TelegramBot bot) {
        Update update = gsiCmdObj.getUpdate();
        ProcessBuilder pb;

        pb = new ProcessBuilder("/bin/bash", "-c",
                "cd " + toolPath + " ; ./url2GSI.sh '" + gsiCmdObj.getUrl() + "' " + gsiCmdObj.getGsi() + " " + gsiCmdObj.getParam()
        );

        boolean success = false;

        StringBuilder fullLogs = new StringBuilder();
        fullLogs.append("Starting process!");

        InputStream inputStream = null;
        InputStreamReader inputStreamReader = null;
        BufferedReader bufferedReader = null;

        int id = bot.sendReply(fullLogs.toString(), update);

        try {
            pb.redirectErrorStream(true);
            Process process = pb.start();

            inputStream = process.getInputStream();
            inputStreamReader = new InputStreamReader(inputStream);
            bufferedReader = new BufferedReader(inputStreamReader);

            String line;

            boolean weDontNeedAria2Logs = true;

            while ((line = bufferedReader.readLine()) != null) {
                System.out.println(line);
                line = "`" + line + "`";
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
                    if (line.contains("GSI done on:")) {
                        success = true;
                    }
                }
            }

            if (success) {
                fullLogs.append("\n").append("Creating gzip...");
                bot.editMessage(fullLogs.toString(), update, id);

                String[] gzipFiles = listFilesForFolder(new File("ErfanGSIs" + "/output"));
                for (String gzipFile : gzipFiles) {
                    new FileTools().gzipFile(gzipFile, gzipFile + ".gz");
                }

                ArrayList<String> arr = new ArrayList<>();

                AtomicReference<String> aonly = new AtomicReference<>("");
                AtomicReference<String> ab = new AtomicReference<>("");

                try (Stream<Path> paths = Files.walk(Paths.get("ErfanGSIs/output/"))) {
                    paths
                            .filter(Files::isRegularFile)
                            .forEach(fileName -> {
                                if (fileName.toString().endsWith(".img.gz")) {
                                    arr.add(fileName.toString());
                                    if (fileName.toString().contains("Aonly")) {
                                        aonly.set(FilenameUtils.getBaseName(fileName.toString()) + "." + FilenameUtils.getExtension(fileName.toString()));
                                    } else {
                                        ab.set(FilenameUtils.getBaseName(fileName.toString()) + "." + FilenameUtils.getExtension(fileName.toString()));
                                    }
                                }
                                if (fileName.toString().contains(".txt")) {
                                    infoGSI = fileName.toString();
                                }
                            });
                } catch (IOException e) {
                    logger.error(e.getMessage());
                }

                fullLogs.append("\n").append("Sending files to SF...");
                bot.editMessage(fullLogs.toString(), update, id);

                String re = new SourceForgeUpload().uploadGsi(arr, gsiCmdObj.getGsi());
                re = re + "/";

                if (gsiCmdObj.getGsi().contains(":")) {
                    gsiCmdObj.setGsi(gsiCmdObj.getGsi().split(":")[1]);
                    gsiCmdObj.setGsi(gsiCmdObj.getGsi().replace("-", " "));
                }

                StringBuilder generateLinks = new StringBuilder();

                if (!aonly.toString().trim().equals("")) {
                    generateLinks.append("\n*Download A-Only:* [SourceForge](https://sourceforge.net/projects/").append(SourceForgeSetup.getSfConf("bot-sf-proj")).append("/files/").append(re).append(aonly).append(")");
                }
                if (!ab.toString().trim().equals("")) {
                    generateLinks.append("\n*Download AB:* [SourceForge](https://sourceforge.net/projects/").append(SourceForgeSetup.getSfConf("bot-sf-proj")).append("/files/").append(re).append(ab).append(")");
                }

                generateLinks.append("\n*Folder:* [SourceForge](https://sourceforge.net/projects/").append(SourceForgeSetup.getSfConf("bot-sf-proj")).append("/files/").append(re).append(")");

                String descGSI = "" + new FileTools().readFile(infoGSI).trim();

                bot.sendReply("Job Finished", update);

                try {
                    if (Objects.equals(SourceForgeSetup.getSfConf("bot-send-announcement"), "true")) {
                        try {
                            bot.sendMessage2ID("*GSI: " + gsiCmdObj.getGsi() + "*\n\n"
                                    + "*Firmware Base: *" + "[URL](" + gsiCmdObj.getUrl() + ")"
                                    + "\n\n*Information:*\n`" + descGSI
                                    + "`\n" + generateLinks
                                    + "\n\nFile not found? wait some minutes\nSlow downloads? try a mirror :)"
                                    + "\n\n*Thanks to:* [Contributors List](https://github.com/erfanoabdi/ErfanGSIs/graphs/contributors)"
                                    + "\n\n[Ported using ErfanGSIs Tool](https://github.com/erfanoabdi/ErfanGSIs)", Long.parseLong(Objects.requireNonNull(SourceForgeSetup.getSfConf("bot-announcement-id"))));
                        } catch (Exception e) {
                            logger.error("bot-announcement-id looks wrong or not set");
                        }
                    }
                } catch (Exception e) {
                    logger.warn("bot-send-announcement is not set");
                }

                fullLogs.append("\n").append("Finished!");
                bot.editMessage(fullLogs.toString(), update, id);
                FileUtils.deleteDirectory(new File(toolPath + "output"));
            } else {
                throw new Exception("Task finished without generating a valid GSI");
            }
        } catch (Exception ex) {
            logger.error(String.valueOf(fullLogs));
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
    }

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
}