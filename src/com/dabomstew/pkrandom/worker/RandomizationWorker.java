package com.dabomstew.pkrandom.worker;

import com.dabomstew.pkrandom.*;
import com.dabomstew.pkrandom.cli.CliRandomizer;
import com.dabomstew.pkrandom.pokemon.ExpCurve;
import com.dabomstew.pkrandom.romhandlers.*;

import java.io.*;
import java.util.*;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.*;
import com.google.gson.*;

import javax.servlet.AsyncContext;

import java.io.File;
import java.io.IOException;

import static com.dabomstew.pkrandom.WebConstants.API_KEY;
import static com.dabomstew.pkrandom.WebConstants.API_URL;

public class RandomizationWorker implements Runnable {

    private Settings workerSettings;
    private long workerSeed;
    private String workerRomFilePath;
    private String workerDestinationFilePath;
    private boolean workerSaveAsDirectory;
    private String workerUpdateFilePath;
    private boolean workerSaveLog;

    public RandomizationWorker(Settings settings, long seed, String sourceRomFilePath, String destinationRomFilePath, boolean saveAsDirectory, String updateFilePath, boolean saveLog)
    {
        this.workerSettings = settings;
        this.workerSeed = seed;
        this.workerRomFilePath = sourceRomFilePath;
        this.workerDestinationFilePath = destinationRomFilePath;
        this.workerSaveAsDirectory = saveAsDirectory;
        this.workerUpdateFilePath = updateFilePath;
        this.workerSaveLog = saveLog;
    }

    private final static ResourceBundle bundle = java.util.ResourceBundle.getBundle("com/dabomstew/pkrandom/newgui/Bundle");

    private String performDirectRandomization() {
        //Update API status to show as RANDOMIZING

        HttpClient httpClient = HttpClientBuilder.create().build();
        RequestConfig.Builder reqconfigconbuilder= RequestConfig.custom();
        HttpHost proxyHost = new HttpHost("localhost", 8888, "http");
        reqconfigconbuilder = reqconfigconbuilder.setProxy(proxyHost);
        RequestConfig config = reqconfigconbuilder.build();
        try {
            HttpPatch httpRequest = new HttpPatch(API_URL + "randomizations/" + workerDestinationFilePath);
            StringEntity params = new StringEntity("status=RANDOMIZING");
            httpRequest.addHeader("X-Authorization", API_KEY);
            httpRequest.addHeader("Content-Type", "application/x-www-form-urlencoded");
            httpRequest.setEntity(params);
            httpRequest.setConfig(config);
            HttpResponse httpResponse = httpClient.execute(httpRequest);
        } catch (Exception ex) {
        } finally {
            // @Deprecated httpClient.getConnectionManager().shutdown();
        }

        // borrowed directly from NewRandomizerGUI()
        RomHandler.Factory[] checkHandlers = new RomHandler.Factory[] {
                new Gen1RomHandler.Factory(),
                new Gen2RomHandler.Factory(),
                new Gen3RomHandler.Factory(),
                new Gen4RomHandler.Factory(),
                new Gen5RomHandler.Factory(),
                new Gen6RomHandler.Factory(),
                new Gen7RomHandler.Factory()
        };


        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream log;
        try {
            log = new PrintStream(baos, false, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            log = new PrintStream(baos);
        }

        final PrintStream verboseLog = log;

        try {
            File romFileHandler = new File(this.workerRomFilePath);
            RomHandler romHandler = null;

            for (RomHandler.Factory rhf : checkHandlers) {
                if (rhf.isLoadable(romFileHandler.getAbsolutePath())) {
                    romHandler = rhf.create(RandomSource.instance());
                    romHandler.loadRom(romFileHandler.getAbsolutePath());
                    if (this.workerUpdateFilePath != null && romHandler.generationOfPokemon() == 6 || romHandler.generationOfPokemon() == 7) {
                        romHandler.loadGameUpdate(this.workerUpdateFilePath);
                        if (!this.workerSaveAsDirectory) {
                            printWarning("Forcing save as directory since a game update was supplied.");
                        }
                        this.workerSaveAsDirectory = true;
                    }
                    if (this.workerSaveAsDirectory && romHandler.generationOfPokemon() != 6 && romHandler.generationOfPokemon() != 7) {
                        this.workerSaveAsDirectory = false;
                        printWarning("Saving as directory does not make sense for non-3DS games, ignoring \"-d\" flag...");
                    }
                    //settings.tweakForRom(romHandler);
                    displaySettingsWarnings(this.workerSettings, romHandler);

                    File fh = new File(this.workerDestinationFilePath);
                    if (!this.workerSaveAsDirectory) {
                        List<String> extensions = new ArrayList<>(Arrays.asList("sgb", "gbc", "gba", "nds", "cxi"));
                        extensions.remove(romHandler.getDefaultExtension());

                        fh = FileFunctions.fixFilename(fh, romHandler.getDefaultExtension(), extensions);
                        if (romHandler instanceof AbstractDSRomHandler || romHandler instanceof Abstract3DSRomHandler) {
                            String currentFN = romHandler.loadedFilename();
                            if (currentFN.equals(fh.getAbsolutePath())) {
                                printError(bundle.getString("GUI.cantOverwriteDS"));
                                return "error";
                            }
                        }
                    }

                    String filename = fh.getAbsolutePath();

                    Randomizer randomizer = new Randomizer(this.workerSettings, romHandler, bundle, this.workerSaveAsDirectory);
                    if (this.workerSeed == 0)
                    {
                        this.workerSeed = RandomSource.pickSeed();
                        randomizer.randomize(filename, verboseLog, this.workerSeed);
                    }
                    else
                    {
                        randomizer.randomize(filename, verboseLog, this.workerSeed);
                    }
                    verboseLog.close();
                    byte[] out = baos.toByteArray();
                    if (this.workerSaveLog) {
                        try {
                            FileOutputStream fos = new FileOutputStream(filename + ".log");
                            fos.write(0xEF);
                            fos.write(0xBB);
                            fos.write(0xBF);
                            fos.write(out);
                            fos.close();
                        } catch (IOException e) {
                            printWarning("Could not write log.");
                        }
                    }
                    printSuccess("Finished randomizing - update status via API");
                    httpClient = HttpClientBuilder.create().build();
                    reqconfigconbuilder= RequestConfig.custom();
                    reqconfigconbuilder = reqconfigconbuilder.setProxy(proxyHost);
                    config = reqconfigconbuilder.build();
                    printSuccess(filename);
                    try {
                        HttpPatch httpRequest = new HttpPatch(API_URL + "randomizations/" + workerDestinationFilePath);
                        StringEntity params = new StringEntity("status=UPLOADING&local_path=" + filename);
                        httpRequest.addHeader("X-Authorization", API_KEY);
                        httpRequest.addHeader("Content-Type", "application/x-www-form-urlencoded");
                        httpRequest.setEntity(params);
                        httpRequest.setConfig(config);
                        HttpResponse httpResponse = httpClient.execute(httpRequest);
                    } catch (Exception ex) {
                    } finally {
                        // @Deprecated httpClient.getConnectionManager().shutdown();
                    }
                    String jsonString = new JSONObject()
                            .put("status", 200)
                            .put("data", new JSONObject().put("fileName", fh.getName())
                                    .put("seed", this.workerSeed)
                                    .put("settingsString", this.workerSettings.toString()))
                            .toString();
                    return jsonString;
                    // this is the only successful exit, everything else will return false at the end of the function
                    //return true;
                }
            }
            // if we get here it means no rom handlers matched the ROM file
            System.err.printf(bundle.getString("GUI.unsupportedRom") + "%n", romFileHandler.getName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    private static void displaySettingsWarnings(Settings settings, RomHandler romHandler) {
        Settings.TweakForROMFeedback feedback = settings.tweakForRom(romHandler);
        if (feedback.isChangedStarter() && settings.getStartersMod() == Settings.StartersMod.CUSTOM) {
            printWarning(bundle.getString("GUI.starterUnavailable"));
        }
        if (settings.isUpdatedFromOldVersion()) {
            printWarning(bundle.getString("GUI.settingsFileOlder"));
        }
    }



    private static void printError(String text) {
        System.err.println("ERROR: " + text);
    }

    private static void printSuccess(String text) {
        System.out.println(text);
    }

    private static void printWarning(String text) {
        System.err.println("WARNING: " + text);
    }

    @Override
    public void run() {
        performDirectRandomization();
    }
}
