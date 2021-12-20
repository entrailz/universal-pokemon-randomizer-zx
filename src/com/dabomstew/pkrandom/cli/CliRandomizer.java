package com.dabomstew.pkrandom.cli;

import com.dabomstew.pkrandom.*;
import com.dabomstew.pkrandom.pokemon.ExpCurve;
import com.dabomstew.pkrandom.romhandlers.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.*;
import com.google.gson.*;
import static spark.Spark.*;

public class CliRandomizer {

    private final static ResourceBundle bundle = java.util.ResourceBundle.getBundle("com/dabomstew/pkrandom/newgui/Bundle");
    public static RomHandler romHandler;

    private static String performDirectRandomization(Settings settings, String sourceRomFilePath,
                                                      String destinationRomFilePath, boolean saveAsDirectory,
                                                      String updateFilePath, boolean saveLog)
    {
        return performDirectRandomization(settings, 0, sourceRomFilePath, destinationRomFilePath, saveAsDirectory, updateFilePath, saveLog);
    }

    private static String performDirectRandomization(Settings settings, long seed, String sourceRomFilePath,
                                                      String destinationRomFilePath, boolean saveAsDirectory,
                                                      String updateFilePath, boolean saveLog) {
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
            File romFileHandler = new File(sourceRomFilePath);
            //RomHandler romHandler;

            for (RomHandler.Factory rhf : checkHandlers) {
                if (rhf.isLoadable(romFileHandler.getAbsolutePath())) {
                    if (romHandler == null) {
                        romHandler = rhf.create(RandomSource.instance());
                        romHandler.loadRom(romFileHandler.getAbsolutePath());
                    }
                    if (updateFilePath != null && romHandler.generationOfPokemon() == 6 || romHandler.generationOfPokemon() == 7) {
                        romHandler.loadGameUpdate(updateFilePath);
                        if (!saveAsDirectory) {
                            printWarning("Forcing save as directory since a game update was supplied.");
                        }
                        saveAsDirectory = true;
                    }
                    if (saveAsDirectory && romHandler.generationOfPokemon() != 6 && romHandler.generationOfPokemon() != 7) {
                        saveAsDirectory = false;
                        printWarning("Saving as directory does not make sense for non-3DS games, ignoring \"-d\" flag...");
                    }
                    settings.tweakForRom(romHandler);
                    CliRandomizer.displaySettingsWarnings(settings, romHandler);

                    File fh = new File(destinationRomFilePath);
                    if (!saveAsDirectory) {
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

                    Randomizer randomizer = new Randomizer(settings, romHandler, saveAsDirectory);
                    if (seed == 0)
                    {
                        seed = RandomSource.pickSeed();
                        randomizer.randomize(filename, verboseLog, seed);
                    }
                    else
                    {
                        randomizer.randomize(filename, verboseLog, seed);
                    }

                    verboseLog.close();
                    byte[] out = baos.toByteArray();
                    if (saveLog) {
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
                    String jsonString = new JSONObject()
                            .put("status", 200)
                            .put("data", new JSONObject().put("fileName", fh.getName())
                                    .put("seed", seed)
                                    .put("settingsString", settings.toString()))
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

    public static String printObject(Object object) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(object);
    }

    public static int invoke(String[] args) {
        post("/generate", (request, response) -> {
            // Init variables for later use.
            String updateFile = null;
            long providedSeed = 0;
            Settings settings;

            response.header("Content-Type", "application/json");
            //The file parameter is generated by an array in the PHP script (each game will map to a location). The file parameter is the location of the ROM on the server.
            String filePath = request.queryParams("file");

            if (request.queryParams("settings") != null && !request.queryParams("settings").isEmpty())
            {
                //BASE64 encoded string that is generated by the generator has been passed to the endpoint, parse it and get the settings from that.
                settings = getSettingsFromString(request.queryParams("settings"));
            }
            else if (request.queryParams("jsonSettings") != null && !request.queryParams("jsonSettings").isEmpty())
            {
                //No pre-determined settings, generate it from the passed JSON.
                settings = createSettingsFromString(request.queryParams("jsonSettings"));
            }
            else
            {
                //We can't really do anything if we don't have the base64 string or the generation string... return error.
                response.status(500);
                return new JSONObject()
                        .put("status", "500")
                        .put("data", "No settings string or settings code provided.").toString();
            }
            if (request.queryParams("seed") != null && !request.queryParams("seed").isEmpty())
            {
                //Allow users to provide their own seed value, we need to verify that it is in-fact a valid long value.
                try
                {
                    providedSeed = Long.parseLong(request.queryParams("seed"));
                }
                catch (Exception ex)
                {
                    response.status(500);
                    return new JSONObject()
                            .put("status", "500")
                            .put("data", "Invalid seed.").toString();
                }

            }
            if (request.queryParams("updateFile") != null && !request.queryParams("updateFile").isEmpty())
            {
                //Some ROMs can have an update file (new functions/pokemon etc) this allow
                updateFile = request.queryParams("updateFile");
            }
            String outFileName = randomStringGenerator.generateString();
            return performDirectRandomization(settings, providedSeed, filePath, outFileName, false, updateFile, false);
        });

        post("/settingsToJson", (request, response) -> {
            response.header("Content-Type", "application/json");
            if (request.queryParams("settings") != null && !request.queryParams("settings").isEmpty())
            {
                //BASE64 encoded string that is generated by the generator has been passed to the endpoint, parse it and get the settings from that.
                Settings settings = getSettingsFromString(request.queryParams("settings"));
                return printObject(settings);
            }
            else
            {
                response.status(500);
                return new JSONObject()
                        .put("status", "500")
                        .put("data", "No settings provided").toString();
            }
        });

        post("/settingsFromJson", (request, response) -> {
            response.header("Content-Type", "application/json");
            if (request.queryParams("jsonSettings") != null && !request.queryParams("jsonSettings").isEmpty())
            {
                //BASE64 encoded string that is generated by the generator has been passed to the endpoint, parse it and get the settings from that.
                Settings settings = createSettingsFromString(request.queryParams("settingsString"));
                return printObject(settings);
            }
            else
            {
                response.status(500);
                return new JSONObject()
                        .put("status", "500")
                        .put("data", "No settings provided").toString();
            }
        });


        return 0;
    }

    private static Settings getSettingsFromString(String settingsString) throws UnsupportedEncodingException {
        int settingsStringVersionNumber = Integer.parseInt(settingsString.substring(0, 3));
        if (settingsStringVersionNumber < Version.VERSION) {
            String updatedSettingsString = new SettingsUpdater().update(settingsStringVersionNumber, settingsString.substring(3));
            return Settings.fromString(updatedSettingsString);
        }
        return Settings.fromString(settingsString);
    }

    public static boolean isNumeric(String strNum) {
        if (strNum == null) {
            return false;
        }
        try {
            Integer d = Integer.parseInt(strNum);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }

    private static Settings createSettingsFromString(String settingsString)
    {
        return new Gson().fromJson(settingsString, Settings.class);
    }

    private static Settings oldSettingsFromString(String settingsString, String filePath) {
        String[] splitSettings = settingsString.split("#");
        RomHandler.Factory[] checkHandlers = new RomHandler.Factory[] {
                new Gen1RomHandler.Factory(),
                new Gen2RomHandler.Factory(),
                new Gen3RomHandler.Factory(),
                new Gen4RomHandler.Factory(),
                new Gen5RomHandler.Factory(),
                new Gen6RomHandler.Factory(),
                new Gen7RomHandler.Factory()
        };
        for (RomHandler.Factory rhf : checkHandlers) {
            File chosenFile = new File(filePath);
            if (rhf.isLoadable(chosenFile.getAbsolutePath()))
            {
                romHandler = rhf.create(RandomSource.instance());
                romHandler.loadRom(filePath);
            }
        }
        //romHandler.loadRom(splitSettings[0] + "/" +  splitSettings[1]);
        Boolean limitPokemonCheckBox = Boolean.valueOf(splitSettings[2]);
        Boolean raceModeCheckBox = Boolean.valueOf(splitSettings[3]);
        Boolean peChangeImpossibleEvosCheckBox = Boolean.valueOf(splitSettings[4]);
        Boolean mdUpdateMovesCheckBox = Boolean.valueOf(splitSettings[5]);
        Integer mdUpdateComboBox = Integer.valueOf(splitSettings[6]);
        Boolean tpRandomizeTrainerNamesCheckBox = Boolean.valueOf(splitSettings[7]);
        Boolean tpRandomizeTrainerClassNamesCheckBox = Boolean.valueOf(splitSettings[8]);
        Boolean pbsUnchangedRadioButton = Boolean.valueOf(splitSettings[9]);
        Boolean pbsShuffleRadioButton = Boolean.valueOf(splitSettings[10]);
        Boolean pbsRandomRadioButton = Boolean.valueOf(splitSettings[11]);
        Boolean pbsFollowEvolutionsCheckBox = Boolean.valueOf(splitSettings[12]);
        Boolean pbsUpdateBaseStatsCheckBox = Boolean.valueOf(splitSettings[13]);
        Integer pbsUpdateComboBox = Integer.valueOf(splitSettings[14]);
        Boolean pbsStandardizeEXPCurvesCheckBox = Boolean.valueOf(splitSettings[15]);
        Boolean pbsLegendariesSlowRadioButton = Boolean.valueOf(splitSettings[16]);
        Boolean pbsStrongLegendariesSlowRadioButton = Boolean.valueOf(splitSettings[17]);
        Boolean pbsAllMediumFastRadioButton = Boolean.valueOf(splitSettings[18]);
        Integer pbsEXPCurveComboBox = Integer.valueOf(splitSettings[19]);
        Boolean pbsFollowMegaEvosCheckBox = Boolean.valueOf(splitSettings[20]);
        Boolean pbsAssignEvoStatsRandomlyCheckBox = Boolean.valueOf(splitSettings[21]);
        Boolean paUnchangedRadioButton = Boolean.valueOf(splitSettings[22]);
        Boolean paRandomRadioButton = Boolean.valueOf(splitSettings[23]);
        Boolean paAllowWonderGuardCheckBox = Boolean.valueOf(splitSettings[24]);
        Boolean paFollowEvolutionsCheckBox = Boolean.valueOf(splitSettings[25]);
        Boolean paTrappingAbilitiesCheckBox = Boolean.valueOf(splitSettings[26]);
        Boolean paNegativeAbilitiesCheckBox = Boolean.valueOf(splitSettings[27]);
        Boolean paBadAbilitiesCheckBox = Boolean.valueOf(splitSettings[28]);
        Boolean paFollowMegaEvosCheckBox = Boolean.valueOf(splitSettings[29]);
        Boolean paWeighDuplicatesTogetherCheckBox = Boolean.valueOf(splitSettings[30]);
        Boolean ptUnchangedRadioButton = Boolean.valueOf(splitSettings[31]);
        Boolean ptRandomFollowEvolutionsRadioButton = Boolean.valueOf(splitSettings[32]);
        Boolean ptRandomCompletelyRadioButton = Boolean.valueOf(splitSettings[33]);
        Boolean ptFollowMegaEvosCheckBox = Boolean.valueOf(splitSettings[34]);
        Boolean pmsNoGameBreakingMovesCheckBox = Boolean.valueOf(splitSettings[35]);
        Boolean peMakeEvolutionsEasierCheckBox = Boolean.valueOf(splitSettings[36]);
        Boolean peRemoveTimeBasedEvolutionsCheckBox = Boolean.valueOf(splitSettings[37]);
        Boolean spUnchangedRadioButton = Boolean.valueOf(splitSettings[38]);
        Boolean spCustomRadioButton = Boolean.valueOf(splitSettings[39]);
        Boolean spRandomCompletelyRadioButton = Boolean.valueOf(splitSettings[40]);
        Boolean spRandomTwoEvosRadioButton = Boolean.valueOf(splitSettings[41]);
        Boolean spRandomizeStarterHeldItemsCheckBox = Boolean.valueOf(splitSettings[42]);
        Boolean spBanBadItemsCheckBox = Boolean.valueOf(splitSettings[43]);
        Boolean spAllowAltFormesCheckBox = Boolean.valueOf(splitSettings[44]);
        Integer spComboBox1 = Integer.valueOf(splitSettings[45]);
        Integer spComboBox2 = Integer.valueOf(splitSettings[46]);
        Integer spComboBox3 = Integer.valueOf(splitSettings[47]);
        Boolean peUnchangedRadioButton = Boolean.valueOf(splitSettings[48]);
        Boolean peRandomRadioButton = Boolean.valueOf(splitSettings[49]);
        Boolean peSimilarStrengthCheckBox = Boolean.valueOf(splitSettings[50]);
        Boolean peSameTypingCheckBox = Boolean.valueOf(splitSettings[51]);
        Boolean peLimitEvolutionsToThreeCheckBox = Boolean.valueOf(splitSettings[52]);
        Boolean peForceChangeCheckBox = Boolean.valueOf(splitSettings[53]);
        Boolean peAllowAltFormesCheckBox = Boolean.valueOf(splitSettings[54]);
        Boolean mdRandomizeMoveAccuracyCheckBox = Boolean.valueOf(splitSettings[55]);
        Boolean mdRandomizeMoveCategoryCheckBox = Boolean.valueOf(splitSettings[56]);
        Boolean mdRandomizeMovePowerCheckBox = Boolean.valueOf(splitSettings[57]);
        Boolean mdRandomizeMovePPCheckBox = Boolean.valueOf(splitSettings[58]);
        Boolean mdRandomizeMoveTypesCheckBox = Boolean.valueOf(splitSettings[59]);
        Boolean pmsUnchangedRadioButton = Boolean.valueOf(splitSettings[60]);
        Boolean pmsRandomPreferringSameTypeRadioButton = Boolean.valueOf(splitSettings[61]);
        Boolean pmsRandomCompletelyRadioButton = Boolean.valueOf(splitSettings[62]);
        Boolean pmsMetronomeOnlyModeRadioButton = Boolean.valueOf(splitSettings[63]);
        Boolean pmsGuaranteedLevel1MovesCheckBox = Boolean.valueOf(splitSettings[64]);
        Integer pmsGuaranteedLevel1MovesSlider = Integer.valueOf(splitSettings[65]);
        Boolean pmsReorderDamagingMovesCheckBox = Boolean.valueOf(splitSettings[66]);
        Boolean pmsForceGoodDamagingCheckBox = Boolean.valueOf(splitSettings[67]);
        Integer pmsForceGoodDamagingSlider = Integer.valueOf(splitSettings[68]);
        Boolean pmsEvolutionMovesCheckBox = Boolean.valueOf(splitSettings[69]);
        Boolean tpUnchangedRadioButton = Boolean.valueOf(splitSettings[70]);
        Boolean tpRandomRadioButton = Boolean.valueOf(splitSettings[71]);
        Boolean tpRandomEvenDistributionRadioButton = Boolean.valueOf(splitSettings[72]);
        Boolean tpRandomEvenDistributionMainRadioButton = Boolean.valueOf(splitSettings[73]);
        Boolean tpTypeThemedRadioButton = Boolean.valueOf(splitSettings[74]);
        Boolean tpSimilarStrengthCheckBox = Boolean.valueOf(splitSettings[75]);
        Boolean tpRivalCarriesStarterCheckBox = Boolean.valueOf(splitSettings[76]);
        Boolean tpWeightTypesCheckBox = Boolean.valueOf(splitSettings[77]);
        Boolean tpDontUseLegendariesCheckBox = Boolean.valueOf(splitSettings[78]);
        Boolean tpNoEarlyWonderGuardCheckBox = Boolean.valueOf(splitSettings[79]);
        Boolean tpForceFullyEvolvedAtCheckBox = Boolean.valueOf(splitSettings[80]);
        Integer tpForceFullyEvolvedAtSlider = Integer.valueOf(splitSettings[81]);
        Boolean tpPercentageLevelModifierCheckBox = Boolean.valueOf(splitSettings[82]);
        Integer tpPercentageLevelModifierSlider = Integer.valueOf(splitSettings[83]);
        Boolean tpAllowAlternateFormesCheckBox = Boolean.valueOf(splitSettings[84]);
        Boolean tpSwapMegaEvosCheckBox = Boolean.valueOf(splitSettings[85]);
        Boolean tpDoubleBattleModeCheckBox = Boolean.valueOf(splitSettings[86]);
        Boolean tpBossTrainersCheckBox = Boolean.valueOf(splitSettings[87]);
        Integer tpBossTrainersSpinner = Integer.valueOf(splitSettings[88]);
        Boolean tpImportantTrainersCheckBox = Boolean.valueOf(splitSettings[89]);
        Integer tpImportantTrainersSpinner = Integer.valueOf(splitSettings[90]);
        Boolean tpRegularTrainersCheckBox = Boolean.valueOf(splitSettings[91]);
        Integer tpRegularTrainersSpinner = Integer.valueOf(splitSettings[93]);
        Boolean tpRandomShinyTrainerPokemonCheckBox = Boolean.valueOf(splitSettings[94]);
        Boolean tpBossTrainersItemsCheckBox = Boolean.valueOf(splitSettings[95]);
        Boolean tpImportantTrainersItemsCheckBox = Boolean.valueOf(splitSettings[96]);
        Boolean tpRegularTrainersItemsCheckBox = Boolean.valueOf(splitSettings[97]);
        Boolean tpConsumableItemsOnlyCheckBox = Boolean.valueOf(splitSettings[98]);
        Boolean tpSensibleItemsCheckBox = Boolean.valueOf(splitSettings[99]);
        Boolean tpHighestLevelGetsItemCheckBox = Boolean.valueOf(splitSettings[100]);
        Boolean totpUnchangedRadioButton = Boolean.valueOf(splitSettings[101]);
        Boolean totpRandomRadioButton = Boolean.valueOf(splitSettings[102]);
        Boolean totpRandomSimilarStrengthRadioButton = Boolean.valueOf(splitSettings[103]);
        Boolean totpAllyUnchangedRadioButton = Boolean.valueOf(splitSettings[104]);
        Boolean totpAllyRandomRadioButton = Boolean.valueOf(splitSettings[105]);
        Boolean totpAllyRandomSimilarStrengthRadioButton = Boolean.valueOf(splitSettings[106]);
        Boolean totpAuraUnchangedRadioButton = Boolean.valueOf(splitSettings[107]);
        Boolean totpAuraRandomRadioButton = Boolean.valueOf(splitSettings[108]);
        Boolean totpAuraRandomSameStrengthRadioButton = Boolean.valueOf(splitSettings[109]);
        Boolean totpRandomizeHeldItemsCheckBox = Boolean.valueOf(splitSettings[110]);
        Boolean totpAllowAltFormesCheckBox = Boolean.valueOf(splitSettings[111]);
        Boolean totpPercentageLevelModifierCheckBox = Boolean.valueOf(splitSettings[112]);
        Integer totpPercentageLevelModifierSlider = Integer.valueOf(splitSettings[113]);
        Boolean wpUnchangedRadioButton = Boolean.valueOf(splitSettings[114]);
        Boolean wpRandomRadioButton = Boolean.valueOf(splitSettings[115]);
        Boolean wpArea1To1RadioButton = Boolean.valueOf(splitSettings[116]);
        Boolean wpGlobal1To1RadioButton = Boolean.valueOf(splitSettings[117]);
        Boolean wpARNoneRadioButton = Boolean.valueOf(splitSettings[118]);
        Boolean wpARSimilarStrengthRadioButton = Boolean.valueOf(splitSettings[119]);
        Boolean wpARCatchEmAllModeRadioButton = Boolean.valueOf(splitSettings[120]);
        Boolean wpARTypeThemeAreasRadioButton = Boolean.valueOf(splitSettings[121]);
        Boolean wpUseTimeBasedEncountersCheckBox = Boolean.valueOf(splitSettings[122]);
        Boolean wpSetMinimumCatchRateCheckBox = Boolean.valueOf(splitSettings[123]);
        Integer wpSetMinimumCatchRateSlider = Integer.valueOf(splitSettings[124]);
        Boolean wpDontUseLegendariesCheckBox = Boolean.valueOf(splitSettings[125]);
        Boolean wpRandomizeHeldItemsCheckBox = Boolean.valueOf(splitSettings[126]);
        Boolean wpBanBadItemsCheckBox = Boolean.valueOf(splitSettings[127]);
        Boolean wpBalanceShakingGrassPokemonCheckBox = Boolean.valueOf(splitSettings[128]);
        Boolean wpPercentageLevelModifierCheckBox = Boolean.valueOf(splitSettings[129]);
        Integer wpPercentageLevelModifierSlider = Integer.valueOf(splitSettings[130]);
        Boolean wpAllowAltFormesCheckBox = Boolean.valueOf(splitSettings[131]);
        Boolean stpUnchangedRadioButton = Boolean.valueOf(splitSettings[132]);
        Boolean stpSwapLegendariesSwapStandardsRadioButton = Boolean.valueOf(splitSettings[133]);
        Boolean stpRandomCompletelyRadioButton = Boolean.valueOf(splitSettings[134]);
        Boolean stpRandomSimilarStrengthRadioButton = Boolean.valueOf(splitSettings[135]);
        Boolean stpLimitMainGameLegendariesCheckBox = Boolean.valueOf(splitSettings[136]);
        Boolean stpRandomize600BSTCheckBox = Boolean.valueOf(splitSettings[137]);
        Boolean stpAllowAltFormesCheckBox = Boolean.valueOf(splitSettings[138]);
        Boolean stpSwapMegaEvosCheckBox = Boolean.valueOf(splitSettings[139]);
        Boolean stpPercentageLevelModifierCheckBox = Boolean.valueOf(splitSettings[140]);
        Integer stpPercentageLevelModifierSlider = Integer.valueOf(splitSettings[141]);
        Boolean stpFixMusicCheckBox = Boolean.valueOf(splitSettings[142]);
        Boolean tmUnchangedRadioButton = Boolean.valueOf(splitSettings[143]);
        Boolean tmRandomRadioButton = Boolean.valueOf(splitSettings[144]);
        Boolean thcUnchangedRadioButton = Boolean.valueOf(splitSettings[145]);
        Boolean thcRandomPreferSameTypeRadioButton = Boolean.valueOf(splitSettings[146]);
        Boolean thcRandomCompletelyRadioButton = Boolean.valueOf(splitSettings[147]);
        Boolean thcFullCompatibilityRadioButton = Boolean.valueOf(splitSettings[148]);
        Boolean tmLevelupMoveSanityCheckBox = Boolean.valueOf(splitSettings[149]);
        Boolean tmKeepFieldMoveTMsCheckBox = Boolean.valueOf(splitSettings[150]);
        Boolean tmFullHMCompatibilityCheckBox = Boolean.valueOf(splitSettings[151]);
        Boolean tmForceGoodDamagingCheckBox = Boolean.valueOf(splitSettings[152]);
        Integer tmForceGoodDamagingSlider = Integer.valueOf(splitSettings[153]);
        Boolean tmNoGameBreakingMovesCheckBox = Boolean.valueOf(splitSettings[154]);
        Boolean tmFollowEvolutionsCheckBox = Boolean.valueOf(splitSettings[155]);
        Boolean mtUnchangedRadioButton = Boolean.valueOf(splitSettings[156]);
        Boolean mtRandomRadioButton = Boolean.valueOf(splitSettings[157]);
        Boolean mtcUnchangedRadioButton = Boolean.valueOf(splitSettings[158]);
        Boolean mtcRandomPreferSameTypeRadioButton = Boolean.valueOf(splitSettings[159]);
        Boolean mtcRandomCompletelyRadioButton = Boolean.valueOf(splitSettings[160]);
        Boolean mtcFullCompatibilityRadioButton = Boolean.valueOf(splitSettings[161]);
        Boolean mtLevelupMoveSanityCheckBox = Boolean.valueOf(splitSettings[162]);
        Boolean mtKeepFieldMoveTutorsCheckBox = Boolean.valueOf(splitSettings[163]);
        Boolean mtForceGoodDamagingCheckBox = Boolean.valueOf(splitSettings[164]);
        Integer mtForceGoodDamagingSlider = Integer.valueOf(splitSettings[165]);
        Boolean mtNoGameBreakingMovesCheckBox = Boolean.valueOf(splitSettings[166]);
        Boolean mtFollowEvolutionsCheckBox = Boolean.valueOf(splitSettings[167]);
        Boolean igtUnchangedRadioButton = Boolean.valueOf(splitSettings[168]);
        Boolean igtRandomizeGivenPokemonOnlyRadioButton = Boolean.valueOf(splitSettings[169]);
        Boolean igtRandomizeBothRequestedGivenRadioButton = Boolean.valueOf(splitSettings[170]);
        Boolean igtRandomizeItemsCheckBox = Boolean.valueOf(splitSettings[171]);
        Boolean igtRandomizeIVsCheckBox = Boolean.valueOf(splitSettings[172]);
        Boolean igtRandomizeNicknamesCheckBox = Boolean.valueOf(splitSettings[173]);
        Boolean igtRandomizeOTsCheckBox = Boolean.valueOf(splitSettings[174]);
        Boolean fiUnchangedRadioButton = Boolean.valueOf(splitSettings[175]);
        Boolean fiShuffleRadioButton = Boolean.valueOf(splitSettings[176]);
        Boolean fiRandomRadioButton = Boolean.valueOf(splitSettings[177]);
        Boolean fiRandomEvenDistributionRadioButton = Boolean.valueOf(splitSettings[178]);
        Boolean fiBanBadItemsCheckBox = Boolean.valueOf(splitSettings[179]);
        Boolean shUnchangedRadioButton = Boolean.valueOf(splitSettings[180]);
        Boolean shShuffleRadioButton = Boolean.valueOf(splitSettings[181]);
        Boolean shRandomRadioButton = Boolean.valueOf(splitSettings[182]);
        Boolean shBanBadItemsCheckBox = Boolean.valueOf(splitSettings[183]);
        Boolean shBanRegularShopItemsCheckBox = Boolean.valueOf(splitSettings[184]);
        Boolean shBanOverpoweredShopItemsCheckBox = Boolean.valueOf(splitSettings[185]);
        Boolean shBalanceShopItemPricesCheckBox = Boolean.valueOf(splitSettings[186]);
        Boolean shGuaranteeEvolutionItemsCheckBox = Boolean.valueOf(splitSettings[187]);
        Boolean shGuaranteeXItemsCheckBox = Boolean.valueOf(splitSettings[188]);
        Boolean puUnchangedRadioButton = Boolean.valueOf(splitSettings[189]);
        Boolean puRandomRadioButton = Boolean.valueOf(splitSettings[190]);
        Boolean puBanBadItemsCheckBox = Boolean.valueOf(splitSettings[191]);
        List<String> tweakList = new ArrayList<>(List.of(splitSettings[192].split("%")));
        Settings settings = new Settings();
        settings.setRomName(romHandler.getROMName());

        settings.setLimitPokemon(limitPokemonCheckBox);
        //TODO: Implement this function.
        //settings.setCurrentRestrictions(currentRestrictions);
        settings.setRaceMode(raceModeCheckBox);

        settings.setChangeImpossibleEvolutions(peChangeImpossibleEvosCheckBox && peChangeImpossibleEvosCheckBox);
        settings.setUpdateMoves(mdUpdateMovesCheckBox && mdUpdateMovesCheckBox);
        settings.setUpdateMovesToGeneration(mdUpdateComboBox + (romHandler.generationOfPokemon()+1));
        settings.setRandomizeTrainerNames(tpRandomizeTrainerNamesCheckBox);
        settings.setRandomizeTrainerClassNames(tpRandomizeTrainerClassNamesCheckBox);

        settings.setBaseStatisticsMod(pbsUnchangedRadioButton, pbsShuffleRadioButton,
                pbsRandomRadioButton);
        settings.setBaseStatsFollowEvolutions(pbsFollowEvolutionsCheckBox);
        settings.setUpdateBaseStats(pbsUpdateBaseStatsCheckBox && pbsUpdateBaseStatsCheckBox);
        settings.setUpdateBaseStatsToGeneration(pbsUpdateComboBox + (Math.max(6,romHandler.generationOfPokemon()+1)));
        settings.setStandardizeEXPCurves(pbsStandardizeEXPCurvesCheckBox);
        settings.setExpCurveMod(pbsLegendariesSlowRadioButton, pbsStrongLegendariesSlowRadioButton,
                pbsAllMediumFastRadioButton);
        ExpCurve[] expCurves = getEXPCurvesForGeneration(romHandler.generationOfPokemon());
        settings.setSelectedEXPCurve(expCurves[pbsEXPCurveComboBox]);
        settings.setBaseStatsFollowMegaEvolutions(pbsFollowMegaEvosCheckBox && pbsFollowMegaEvosCheckBox);
        settings.setAssignEvoStatsRandomly(pbsAssignEvoStatsRandomlyCheckBox && pbsAssignEvoStatsRandomlyCheckBox);

        settings.setAbilitiesMod(paUnchangedRadioButton, paRandomRadioButton);
        settings.setAllowWonderGuard(paAllowWonderGuardCheckBox);
        settings.setAbilitiesFollowEvolutions(paFollowEvolutionsCheckBox);
        settings.setBanTrappingAbilities(paTrappingAbilitiesCheckBox);
        settings.setBanNegativeAbilities(paNegativeAbilitiesCheckBox);
        settings.setBanBadAbilities(paBadAbilitiesCheckBox);
        settings.setAbilitiesFollowMegaEvolutions(paFollowMegaEvosCheckBox);
        settings.setWeighDuplicateAbilitiesTogether(paWeighDuplicatesTogetherCheckBox);

        settings.setTypesMod(ptUnchangedRadioButton, ptRandomFollowEvolutionsRadioButton,
                ptRandomCompletelyRadioButton);
        settings.setTypesFollowMegaEvolutions(ptFollowMegaEvosCheckBox && ptFollowMegaEvosCheckBox);
        settings.setBlockBrokenMovesetMoves(pmsNoGameBreakingMovesCheckBox);

        settings.setMakeEvolutionsEasier(peMakeEvolutionsEasierCheckBox);
        settings.setRemoveTimeBasedEvolutions(peRemoveTimeBasedEvolutionsCheckBox);

        settings.setStartersMod(spUnchangedRadioButton, spCustomRadioButton, spRandomCompletelyRadioButton,
                spRandomTwoEvosRadioButton);
        settings.setRandomizeStartersHeldItems(spRandomizeStarterHeldItemsCheckBox && spRandomizeStarterHeldItemsCheckBox);
        settings.setBanBadRandomStarterHeldItems(spBanBadItemsCheckBox && spBanBadItemsCheckBox);
        settings.setAllowStarterAltFormes(spAllowAltFormesCheckBox && spAllowAltFormesCheckBox);

        int[] customStarters = new int[] { spComboBox1 + 1,
                spComboBox2 + 1, spComboBox3 + 1 };
        settings.setCustomStarters(customStarters);

        settings.setEvolutionsMod(peUnchangedRadioButton, peRandomRadioButton);
        settings.setEvosSimilarStrength(peSimilarStrengthCheckBox);
        settings.setEvosSameTyping(peSameTypingCheckBox);
        settings.setEvosMaxThreeStages(peLimitEvolutionsToThreeCheckBox);
        settings.setEvosForceChange(peForceChangeCheckBox);
        settings.setEvosAllowAltFormes(peAllowAltFormesCheckBox && peAllowAltFormesCheckBox);

        settings.setRandomizeMoveAccuracies(mdRandomizeMoveAccuracyCheckBox);
        settings.setRandomizeMoveCategory(mdRandomizeMoveCategoryCheckBox);
        settings.setRandomizeMovePowers(mdRandomizeMovePowerCheckBox);
        settings.setRandomizeMovePPs(mdRandomizeMovePPCheckBox);
        settings.setRandomizeMoveTypes(mdRandomizeMoveTypesCheckBox);

        settings.setMovesetsMod(pmsUnchangedRadioButton, pmsRandomPreferringSameTypeRadioButton,
                pmsRandomCompletelyRadioButton, pmsMetronomeOnlyModeRadioButton);
        settings.setStartWithGuaranteedMoves(pmsGuaranteedLevel1MovesCheckBox && pmsGuaranteedLevel1MovesCheckBox);
        settings.setGuaranteedMoveCount(pmsGuaranteedLevel1MovesSlider);
        settings.setReorderDamagingMoves(pmsReorderDamagingMovesCheckBox);

        settings.setMovesetsForceGoodDamaging(pmsForceGoodDamagingCheckBox);
        settings.setMovesetsGoodDamagingPercent(pmsForceGoodDamagingSlider);
        settings.setBlockBrokenMovesetMoves(pmsNoGameBreakingMovesCheckBox);
        settings.setEvolutionMovesForAll(pmsEvolutionMovesCheckBox);

        settings.setTrainersMod(tpUnchangedRadioButton, tpRandomRadioButton, tpRandomEvenDistributionRadioButton, tpRandomEvenDistributionMainRadioButton, tpTypeThemedRadioButton);
        settings.setTrainersUsePokemonOfSimilarStrength(tpSimilarStrengthCheckBox);
        settings.setRivalCarriesStarterThroughout(tpRivalCarriesStarterCheckBox);
        settings.setTrainersMatchTypingDistribution(tpWeightTypesCheckBox);
        settings.setTrainersBlockLegendaries(tpDontUseLegendariesCheckBox);
        settings.setTrainersBlockEarlyWonderGuard(tpNoEarlyWonderGuardCheckBox);
        settings.setTrainersForceFullyEvolved(tpForceFullyEvolvedAtCheckBox);
        settings.setTrainersForceFullyEvolvedLevel(tpForceFullyEvolvedAtSlider);
        settings.setTrainersLevelModified(tpPercentageLevelModifierCheckBox);
        settings.setTrainersLevelModifier(tpPercentageLevelModifierSlider);
        settings.setAllowTrainerAlternateFormes(tpAllowAlternateFormesCheckBox && tpAllowAlternateFormesCheckBox);
        settings.setSwapTrainerMegaEvos(tpSwapMegaEvosCheckBox && tpSwapMegaEvosCheckBox);
        settings.setDoubleBattleMode(tpDoubleBattleModeCheckBox && tpDoubleBattleModeCheckBox);
        settings.setAdditionalBossTrainerPokemon(tpBossTrainersCheckBox && tpBossTrainersCheckBox ? (int)tpBossTrainersSpinner : 0);
        settings.setAdditionalImportantTrainerPokemon(tpImportantTrainersCheckBox && tpImportantTrainersCheckBox ? (int)tpImportantTrainersSpinner : 0);
        settings.setAdditionalRegularTrainerPokemon(tpRegularTrainersCheckBox && tpRegularTrainersCheckBox ? (int)tpRegularTrainersSpinner : 0);
        settings.setShinyChance(tpRandomShinyTrainerPokemonCheckBox && tpRandomShinyTrainerPokemonCheckBox);
        settings.setRandomizeHeldItemsForBossTrainerPokemon(tpBossTrainersItemsCheckBox && tpBossTrainersItemsCheckBox);
        settings.setRandomizeHeldItemsForImportantTrainerPokemon(tpImportantTrainersItemsCheckBox && tpImportantTrainersItemsCheckBox);
        settings.setRandomizeHeldItemsForRegularTrainerPokemon(tpRegularTrainersItemsCheckBox && tpRegularTrainersItemsCheckBox);
        settings.setConsumableItemsOnlyForTrainers(tpConsumableItemsOnlyCheckBox && tpConsumableItemsOnlyCheckBox);
        settings.setSensibleItemsOnlyForTrainers(tpSensibleItemsCheckBox && tpSensibleItemsCheckBox);
        settings.setHighestLevelGetsItemsForTrainers(tpHighestLevelGetsItemCheckBox && tpHighestLevelGetsItemCheckBox);

        settings.setTotemPokemonMod(totpUnchangedRadioButton, totpRandomRadioButton, totpRandomSimilarStrengthRadioButton);
        settings.setAllyPokemonMod(totpAllyUnchangedRadioButton, totpAllyRandomRadioButton, totpAllyRandomSimilarStrengthRadioButton);
        settings.setAuraMod(totpAuraUnchangedRadioButton, totpAuraRandomRadioButton, totpAuraRandomSameStrengthRadioButton);
        settings.setRandomizeTotemHeldItems(totpRandomizeHeldItemsCheckBox);
        settings.setAllowTotemAltFormes(totpAllowAltFormesCheckBox);
        settings.setTotemLevelsModified(totpPercentageLevelModifierCheckBox);
        settings.setTotemLevelModifier(totpPercentageLevelModifierSlider);

        settings.setWildPokemonMod(wpUnchangedRadioButton, wpRandomRadioButton, wpArea1To1RadioButton,
                wpGlobal1To1RadioButton);
        settings.setWildPokemonRestrictionMod(wpARNoneRadioButton, wpARSimilarStrengthRadioButton,
                wpARCatchEmAllModeRadioButton, wpARTypeThemeAreasRadioButton);
        settings.setUseTimeBasedEncounters(wpUseTimeBasedEncountersCheckBox);
        settings.setUseMinimumCatchRate(wpSetMinimumCatchRateCheckBox);
        settings.setMinimumCatchRateLevel(wpSetMinimumCatchRateSlider);
        settings.setBlockWildLegendaries(wpDontUseLegendariesCheckBox);
        settings.setRandomizeWildPokemonHeldItems(wpRandomizeHeldItemsCheckBox && wpRandomizeHeldItemsCheckBox);
        settings.setBanBadRandomWildPokemonHeldItems(wpBanBadItemsCheckBox && wpBanBadItemsCheckBox);
        settings.setBalanceShakingGrass(wpBalanceShakingGrassPokemonCheckBox && wpBalanceShakingGrassPokemonCheckBox);
        settings.setWildLevelsModified(wpPercentageLevelModifierCheckBox);
        settings.setWildLevelModifier(wpPercentageLevelModifierSlider);
        settings.setAllowWildAltFormes(wpAllowAltFormesCheckBox && wpAllowAltFormesCheckBox);

        settings.setStaticPokemonMod(stpUnchangedRadioButton, stpSwapLegendariesSwapStandardsRadioButton,
                stpRandomCompletelyRadioButton, stpRandomSimilarStrengthRadioButton);
        settings.setLimitMainGameLegendaries(stpLimitMainGameLegendariesCheckBox && stpLimitMainGameLegendariesCheckBox);
        settings.setLimit600(stpRandomize600BSTCheckBox);
        settings.setAllowStaticAltFormes(stpAllowAltFormesCheckBox && stpAllowAltFormesCheckBox);
        settings.setSwapStaticMegaEvos(stpSwapMegaEvosCheckBox && stpSwapMegaEvosCheckBox);
        settings.setStaticLevelModified(stpPercentageLevelModifierCheckBox);
        settings.setStaticLevelModifier(stpPercentageLevelModifierSlider);
        settings.setCorrectStaticMusic(stpFixMusicCheckBox && stpFixMusicCheckBox);

        settings.setTmsMod(tmUnchangedRadioButton, tmRandomRadioButton);

        settings.setTmsHmsCompatibilityMod(thcUnchangedRadioButton, thcRandomPreferSameTypeRadioButton,
                thcRandomCompletelyRadioButton, thcFullCompatibilityRadioButton);
        settings.setTmLevelUpMoveSanity(tmLevelupMoveSanityCheckBox);
        settings.setKeepFieldMoveTMs(tmKeepFieldMoveTMsCheckBox);
        settings.setFullHMCompat(tmFullHMCompatibilityCheckBox && tmFullHMCompatibilityCheckBox);
        settings.setTmsForceGoodDamaging(tmForceGoodDamagingCheckBox);
        settings.setTmsGoodDamagingPercent(tmForceGoodDamagingSlider);
        settings.setBlockBrokenTMMoves(tmNoGameBreakingMovesCheckBox);
        settings.setTmsFollowEvolutions(tmFollowEvolutionsCheckBox);

        settings.setMoveTutorMovesMod(mtUnchangedRadioButton, mtRandomRadioButton);
        settings.setMoveTutorsCompatibilityMod(mtcUnchangedRadioButton, mtcRandomPreferSameTypeRadioButton,
                mtcRandomCompletelyRadioButton, mtcFullCompatibilityRadioButton);
        settings.setTutorLevelUpMoveSanity(mtLevelupMoveSanityCheckBox);
        settings.setKeepFieldMoveTutors(mtKeepFieldMoveTutorsCheckBox);
        settings.setTutorsForceGoodDamaging(mtForceGoodDamagingCheckBox);
        settings.setTutorsGoodDamagingPercent(mtForceGoodDamagingSlider);
        settings.setBlockBrokenTutorMoves(mtNoGameBreakingMovesCheckBox);
        settings.setTutorFollowEvolutions(mtFollowEvolutionsCheckBox);

        settings.setInGameTradesMod(igtUnchangedRadioButton, igtRandomizeGivenPokemonOnlyRadioButton, igtRandomizeBothRequestedGivenRadioButton);
        settings.setRandomizeInGameTradesItems(igtRandomizeItemsCheckBox);
        settings.setRandomizeInGameTradesIVs(igtRandomizeIVsCheckBox);
        settings.setRandomizeInGameTradesNicknames(igtRandomizeNicknamesCheckBox);
        settings.setRandomizeInGameTradesOTs(igtRandomizeOTsCheckBox);

        settings.setFieldItemsMod(fiUnchangedRadioButton, fiShuffleRadioButton, fiRandomRadioButton, fiRandomEvenDistributionRadioButton);
        settings.setBanBadRandomFieldItems(fiBanBadItemsCheckBox);

        settings.setShopItemsMod(shUnchangedRadioButton, shShuffleRadioButton, shRandomRadioButton);
        settings.setBanBadRandomShopItems(shBanBadItemsCheckBox);
        settings.setBanRegularShopItems(shBanRegularShopItemsCheckBox);
        settings.setBanOPShopItems(shBanOverpoweredShopItemsCheckBox);
        settings.setBalanceShopPrices(shBalanceShopItemPricesCheckBox);
        settings.setGuaranteeEvolutionItems(shGuaranteeEvolutionItemsCheckBox);
        settings.setGuaranteeXItems(shGuaranteeXItemsCheckBox);

        settings.setPickupItemsMod(puUnchangedRadioButton, puRandomRadioButton);
        settings.setBanBadRandomPickupItems(puBanBadItemsCheckBox);

        AtomicInteger currentMiscTweaks = new AtomicInteger();
        int mtCount = MiscTweak.allTweaks.size();
        MiscTweak.allTweaks.forEach(tweak -> {
            //TODO: This is how I will find out each tweaks...
            //printSuccess(tweak.getTweakName());
            if (tweakList.contains(tweak.getTweakName()))
            {
                currentMiscTweaks.updateAndGet(v -> v | tweak.getValue());
            }
        });
//        for (int mti = 0; mti < mtCount; mti++) {
//            MiscTweak mt = MiscTweak.allTweaks.get(mti);
//            JCheckBox mtCB = tweakCheckBoxes.get(mti);
//            if (mtCB) {
//                currentMiscTweaks.updateAndGet(v -> v | mt.getValue());
//            }
//        }

        settings.setCurrentMiscTweaks(currentMiscTweaks.get());

       // settings.setCustomNames(customNames);

        return settings;
    }

    private static ExpCurve[] getEXPCurvesForGeneration(int generation) {
        ExpCurve[] result;
        if (generation < 3) {
            result = new ExpCurve[]{ ExpCurve.MEDIUM_FAST, ExpCurve.MEDIUM_SLOW, ExpCurve.FAST, ExpCurve.SLOW };
        } else {
            result = new ExpCurve[]{ ExpCurve.MEDIUM_FAST, ExpCurve.MEDIUM_SLOW, ExpCurve.FAST, ExpCurve.SLOW, ExpCurve.ERRATIC, ExpCurve.FLUCTUATING };
        }
        return result;
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

    private static void printUsage() {
        System.err.println("Usage: java [-Xmx4096M] -jar PokeRandoZX.jar cli -s <path to settings file> " +
                "-i <path to source ROM> -o <path for new ROM> [-d][-u <path to 3DS game update>][-l]");
        System.err.println("-d: Save 3DS game as directory (LayeredFS)");
    }
}

